"""ShelfScan backend: a thin proxy between the Android app, Claude, and Google Books."""
from __future__ import annotations

import asyncio
import logging
import os
import re
from contextlib import asynccontextmanager

import httpx
from fastapi import Depends, FastAPI, File, Header, HTTPException, Response, UploadFile
from pydantic import ValidationError

from .cache import TTL_S, Cache, NullCache, SingleFlight, create_cache, recommendations_key, series_key
from .guard import suspicious_reason
from .identity import merge_duplicates
from .series import plan_gaps, verify_gaps
from .catalog import Catalog, _authors_match, _titles_match, _volume_to_book, guess_language, volume_language
from .llm import LLM, PROVIDER, RECOMMEND_PROMPT_VERSION, SERIES_PROMPT_VERSION, TEXT_MODEL, create_llm
from .models import (
    Book,
    BookRef,
    CandidatesResponse,
    IsbnRequest,
    IsbnResponse,
    SearchRequest,
    SearchResponse,
    CoverResult,
    CoversRequest,
    CoversResponse,
    DetailsRequest,
    DetailsResponse,
    IdentifyResponse,
    OwnedBook,
    Recommendation,
    RecommendRequest,
    RecommendResponse,
    SeriesRequest,
    SeriesResponse,
    UpcomingRequest,
    UpcomingResponse,
)

MAX_IMAGES = 6
MAX_IMAGE_BYTES = 5 * 1024 * 1024
ALLOWED_MIME = {"image/jpeg", "image/png", "image/webp"}
APP_TOKEN = os.getenv("APP_TOKEN")  # optional shared secret the app sends as X-App-Token
# What to do with a book the model read but the catalog has never heard of (a likely misread or invention):
# "drop" leaves it out and says so in `notes`; "keep" returns it anyway.
UNVERIFIED_POLICY = os.getenv("IDENTIFY_UNVERIFIED", "drop").strip().lower()

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger("shelfscan")


class State:
    http: httpx.AsyncClient
    catalog: Catalog
    llm: LLM
    cache: Cache = NullCache()
    flight: SingleFlight = SingleFlight()


state = State()


@asynccontextmanager
async def lifespan(app: FastAPI):
    state.http = httpx.AsyncClient()
    state.catalog = Catalog(state.http)
    if not hasattr(state, "llm"):
        state.llm = create_llm()
    state.cache = create_cache()
    yield
    await state.cache.aclose()
    await state.http.aclose()


app = FastAPI(title="ShelfScan", lifespan=lifespan)


def check_token(x_app_token: str | None = Header(default=None)):
    if APP_TOKEN and x_app_token != APP_TOKEN:
        raise HTTPException(401, "Invalid app token")


def _key(title: str, author: str) -> str:
    return re.sub(r"[^a-z0-9]", "", (title + author).lower())


@app.get("/health")
async def health():
    return {"ok": True}


@app.post("/identify", response_model=IdentifyResponse, dependencies=[Depends(check_token)])
async def identify(images: list[UploadFile] = File(...)):
    if not images or len(images) > MAX_IMAGES:
        raise HTTPException(400, f"Send 1-{MAX_IMAGES} images")
    payload: list[tuple[bytes, str]] = []
    for img in images:
        if img.content_type not in ALLOWED_MIME:
            raise HTTPException(415, f"Unsupported image type: {img.content_type}")
        data = await img.read()
        if len(data) > MAX_IMAGE_BYTES:
            raise HTTPException(413, "Image too large (max 5 MB); resize before upload")
        payload.append((data, img.content_type))

    try:
        books, notes = await state.llm.identify(payload)
    except httpx.TimeoutException:
        raise HTTPException(504, "The AI model took too long to answer") from None
    except httpx.HTTPError as e:
        log.error("LLM call failed: %r", e)
        raise HTTPException(502, "The AI model server failed; check the server log") from None
    log.info(
        "LLM read %d book(s) from %d photo(s): %s | notes: %s",
        len(books), len(payload),
        "; ".join(f"{b.title} / {b.author} ({b.confidence:.2f})" for b in books) or "none", notes,
    )
    # Guardrails: a read only counts if it passes sanity checks AND the catalog knows the book.
    rejected: list[str] = []
    candidates = []
    for b in books:
        if (why := suspicious_reason(b.title, b.author)):
            log.info("rejected %r by %r: %s", b.title, b.author, why)
            rejected.append(b.title)
        else:
            candidates.append(b)
    # One entry per book however many photos or spellings it came from: merge before the lookups (saves
    # queries) and again after them (snapping a misread title can make two reads identical).
    checked = await state.catalog.check_all(merge_duplicates(candidates))
    kept: list[Book] = []
    unchecked = 0
    for b, status in checked:
        if status == "unverified":
            if UNVERIFIED_POLICY != "keep":
                rejected.append(b.title)
                continue
            b = await state.catalog.with_best_cover(b)
        unchecked += status == "unchecked"
        kept.append(b)
    unique = merge_duplicates(kept)
    notes = _with_guard_notes(notes, rejected, unchecked)
    log.info("kept %d of %d read; left out: %s", len(unique), len(books), "; ".join(rejected) or "none")
    return IdentifyResponse(books=unique, notes=notes)


def _with_guard_notes(notes: str | None, rejected: list[str], unchecked: int) -> str | None:
    parts = [notes] if notes else []
    if rejected:
        shown = ", ".join(f'"{t}"' for t in rejected[:5]) + (f" and {len(rejected) - 5} more" if len(rejected) > 5 else "")
        parts.append(f"Left out {len(rejected)} spine(s) I couldn't confirm as real books: {shown}. Retake the photo closer to add them.")
    if unchecked:
        parts.append(f"{unchecked} book(s) couldn't be checked against the book catalog right now, so they may contain mistakes.")
    return " ".join(parts) or None


@app.post("/recommendations", response_model=RecommendResponse, dependencies=[Depends(check_token)])
async def recommendations(req: RecommendRequest, response: Response):
    if not req.books:
        return RecommendResponse(recommendations=[])
    seed = req.similar_to
    author = None if seed else ((req.author or "").strip() or None)
    genre = None if (seed or author) else ((req.genre or "").strip() or None)
    owned_titles = [b.title for b in req.books] + ([seed.title] if seed else [])

    # Lead the shelf with the books the focus is about, so a smaller local model sees them.
    def relevant(b: OwnedBook) -> bool:
        if seed:
            return _authors_match(seed.author, [b.author])
        if author:
            return _authors_match(author, [b.author])
        return bool(genre) and any(genre.lower() == g.lower() for g in b.genres)

    context = sorted(req.books, key=lambda b: not relevant(b))[:150]
    similar_limit = min(req.limit, 6) if author else min(req.limit, 8) if seed else req.limit

    # Same shelf + focus + limit + model => same answer, so serve the stored one. The key covers what the model sees.
    key = recommendations_key(
        ((b.title, b.author) for b in context), author=author, genre=genre, limit=req.limit,
        model=f"{PROVIDER}:{TEXT_MODEL}", prompt_version=RECOMMEND_PROMPT_VERSION,
        similar_to=(seed.title, seed.author) if seed else None,
    )
    if not req.refresh and (hit := await _cached(key)):
        response.headers["X-Cache"] = "HIT"
        return hit

    async with state.flight.lock(key):  # identical requests arriving together share one computation
        if not req.refresh and (hit := await _cached(key)):
            response.headers["X-Cache"] = "HIT"
            return hit
        failures_before = state.catalog.failures
        result, model_ok = await _recommend(req, author, genre, owned_titles, context, similar_limit)
        # Never store a degraded answer (model failed, or the catalog was unreachable so picks went unchecked).
        if model_ok and state.catalog.failures == failures_before:
            await state.cache.set(key, result.model_dump_json(), TTL_S)
    response.headers["X-Cache"] = "BYPASS" if req.refresh else "MISS"
    return result


async def _cached(key: str) -> RecommendResponse | None:
    raw = await state.cache.get(key)
    if not raw:
        return None
    try:
        return RecommendResponse.model_validate_json(raw)
    except ValidationError:  # stored by an older version of the app; treat as a miss and overwrite
        log.warning("Ignoring unreadable cache entry %s", key)
        return None


async def _recommend(req, author, genre, owned_titles, context, similar_limit) -> tuple[RecommendResponse, bool]:
    """Compute recommendations. The flag is False when the model call failed and the answer is partial."""
    seed = req.similar_to

    async def by_author() -> list:
        return await state.catalog.books_by_author(author, owned_titles) if author else []

    async def from_model():
        # Ask for a few extra: some get dropped as owned, duplicated or not found in the catalog.
        if seed:
            return await state.llm.recommend(context, similar_limit + 4, similar_to=seed)
        return await state.llm.recommend(context, similar_limit + 4, author=author, genre=genre)

    more_by, model_result = await asyncio.gather(by_author(), from_model(), return_exceptions=True)
    if isinstance(more_by, BaseException):
        log.error("Catalog lookup for %r failed: %r", author, more_by)
        more_by = []
    model_ok = True
    if isinstance(model_result, BaseException):
        log.error("LLM call failed: %r", model_result)
        if not more_by:  # nothing to show at all
            raise HTTPException(502, "The AI model server failed; check the server log") from None
        model_result, model_ok = [], False

    candidates = [
        (b, why) for b, why in model_result
        if not _is_owned(b.title, owned_titles) and not (author and _authors_match(author, [b.author]))
    ]
    verified = await state.catalog.verify_all([b for b, _ in candidates])
    seen = {_key(b.title, b.author) for b in more_by}
    similar: list[Recommendation] = []
    for book, (_, why) in zip(verified, candidates):
        if book is None or _is_owned(book.title, owned_titles):  # the catalog title can differ
            continue
        k = _key(book.title, book.author)
        if k in seen:
            continue
        seen.add(k)
        similar.append(Recommendation(book=book, reason=why, section="similar"))
    log.info(
        "recommendations (author=%r, genre=%r, similar_to=%r): %d by author, %d similar (%d suggested, %d dropped)",
        author, genre, seed.title if seed else None, len(more_by), len(similar), len(model_result), len(model_result) - len(similar),
    )
    return RecommendResponse(
        recommendations=[Recommendation(book=b, reason="", section="by_author") for b in more_by]
        + similar[:similar_limit]
    ), model_ok


def _is_owned(title: str, owned_titles: list[str]) -> bool:
    return any(_titles_match(t, title) or _titles_match(title, t) for t in owned_titles)


@app.post("/covers", response_model=CoversResponse, dependencies=[Depends(check_token)])
async def covers(req: CoversRequest):
    """Best cover for each book: Open Library's large cover, else Google Books' largest image."""
    async def one(ref) -> CoverResult:
        book = await state.catalog.with_best_cover(Book(title=ref.title, author=ref.author, isbn=ref.isbn))
        if book.cover_url:
            return CoverResult(cover_url=book.cover_url, isbn=book.isbn)
        item, _ = await state.catalog.lookup(book)
        found = _volume_to_book(item, fallback=book) if item else book
        return CoverResult(cover_url=found.cover_url, isbn=found.isbn or ref.isbn)

    return CoversResponse(covers=list(await asyncio.gather(*(one(r) for r in req.books))))


@app.post("/covers/candidates", response_model=CandidatesResponse, dependencies=[Depends(check_token)])
async def cover_candidates(ref: BookRef):
    """Covers to choose from when the automatic one is wrong or missing."""
    book = Book(title=ref.title, author=ref.author, isbn=ref.isbn)
    return CandidatesResponse(candidates=await state.catalog.cover_candidates(book))


@app.post("/search", response_model=SearchResponse, dependencies=[Depends(check_token)])
async def search(req: SearchRequest):
    """Find a book by typing its title or author (for adding one without a photo)."""
    books = await state.catalog.search(req.query.strip(), req.limit)
    return SearchResponse(books=books)


@app.post("/lookup/isbn", response_model=IsbnResponse, dependencies=[Depends(check_token)])
async def lookup_isbn(req: IsbnRequest):
    """The book behind a scanned barcode."""
    isbn = re.sub(r"[^0-9Xx]", "", req.isbn).upper()
    if len(isbn) not in (10, 13):
        raise HTTPException(400, "Not an ISBN: expected 10 or 13 digits")
    book = await state.catalog.by_isbn(isbn)
    log.info("ISBN %s -> %s", isbn, f"{book.title} / {book.author}" if book else "not found")
    return IsbnResponse(book=book)


@app.post("/book/details", response_model=DetailsResponse, dependencies=[Depends(check_token)])
async def book_details(req: DetailsRequest):
    """Cover, description and a short synopsis for one book. The synopsis only restates the description,
    so a model never has to invent what a book is about."""
    given = Book(title=req.title, author=req.author, isbn=req.isbn, description=req.description)
    item, _ = await state.catalog.lookup(given)
    book = _volume_to_book(item, fallback=given) if item else given
    work = await state.catalog.ol.find(req.title, req.author, book.isbn,
                                       lang=volume_language(item) or guess_language(req.title))
    description = _plain(req.description or book.description or "")
    if not description and work:
        description = _plain(await state.catalog.ol.description(work) or "")
    synopsis = await _synopsis(req.title, req.author, description) if description else None
    return DetailsResponse(
        cover_url=(work.cover_url("L") if work and work.cover_id else None) or book.cover_url,
        isbn=book.isbn,
        description=description or None,
        synopsis=synopsis,
        published_date=book.published_date,
        page_count=book.page_count,
    )


SHORT_ENOUGH = 300  # characters: about two sentences, already reads as a synopsis


def _plain(text: str) -> str:
    """Description text without HTML tags, Open Library's source notes or extra whitespace."""
    text = re.sub(r"<br\s*/?>|</p>", "\n", text, flags=re.I)
    text = re.sub(r"<[^>]+>", "", text)
    text = re.sub(r"\(\[source\]\[\d+\]\)|\[\d+\]:\s*\S+|-{3,}", "", text)  # Open Library footnotes
    text = (text.replace("&amp;", "&").replace("&quot;", '"').replace("&#39;", "'")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " "))
    return re.sub(r"[ \t]+", " ", re.sub(r"\n{2,}", "\n\n", text)).strip()


def _first_sentences(text: str, limit: int = 320) -> str:
    out = ""
    for sentence in re.split(r"(?<=[.!?])\s+", text.replace("\n", " ")):
        if out and len(out) + len(sentence) > limit:
            break
        out = f"{out} {sentence}".strip()
    return out if len(out) <= limit + 80 else out[:limit].rsplit(" ", 1)[0] + "…"


async def _synopsis(title: str, author: str, description: str) -> str:
    if len(description) <= SHORT_ENOUGH:
        return description
    try:
        text = await state.llm.summarize(title, author, description)
    except Exception as e:  # noqa: BLE001 - a synopsis is a nicety; never fail the page over it
        log.warning("Synopsis for %r failed (%r); using the description's opening", title, e)
        text = ""
    if not text or len(text) > 600 or len(text) < 40:
        return _first_sentences(description)
    return text


@app.post("/upcoming", response_model=UpcomingResponse, dependencies=[Depends(check_token)])
async def upcoming(req: UpcomingRequest):
    authors = list(dict.fromkeys(a.strip() for a in req.authors if a.strip() and a.lower() != "unknown"))[:40]
    return UpcomingResponse(books=await state.catalog.upcoming(authors))


@app.post("/series/gaps", response_model=SeriesResponse, dependencies=[Depends(check_token)])
async def series_gaps(req: SeriesRequest, response: Response):
    """Series the user has started or nearly finished, with the published volumes they're missing."""
    shelf = [b for b in req.books if b.title.strip()][:500]
    if not shelf:
        return SeriesResponse(series=[])
    key = series_key(((b.title, b.author) for b in shelf), model=f"{PROVIDER}:{TEXT_MODEL}",
                     prompt_version=SERIES_PROMPT_VERSION)
    if not req.refresh and (hit := await _cached_series(key)):
        response.headers["X-Cache"] = "HIT"
        return hit

    async with state.flight.lock(key):
        if not req.refresh and (hit := await _cached_series(key)):
            response.headers["X-Cache"] = "HIT"
            return hit
        failures_before = state.catalog.failures
        try:
            raw, complete = await state.llm.series(shelf)
        except Exception as e:  # noqa: BLE001
            log.error("Series call failed: %r", e)
            raise HTTPException(502, "The AI model server failed; check the server log") from None
        result = SeriesResponse(series=await verify_gaps(plan_gaps(raw, shelf), state.catalog))
        # Never store a degraded answer: a cut-off reply, or missing volumes that went unchecked.
        if complete and state.catalog.failures == failures_before:
            await state.cache.set(key, result.model_dump_json(), TTL_S)
    response.headers["X-Cache"] = "BYPASS" if req.refresh else "MISS"
    return result


async def _cached_series(key: str) -> SeriesResponse | None:
    raw = await state.cache.get(key)
    if not raw:
        return None
    try:
        return SeriesResponse.model_validate_json(raw)
    except ValidationError:
        log.warning("Ignoring unreadable cache entry %s", key)
        return None
