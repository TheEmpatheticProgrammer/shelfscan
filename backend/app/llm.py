"""Claude calls: identify books in photos, and recommend reads. Uses tool use for structured JSON."""
from __future__ import annotations

import base64
import io
import json
import logging
import os
import asyncio
import re
import time
from typing import Protocol

import httpx
from anthropic import AsyncAnthropic

from .models import Book, OwnedBook

PROVIDER = os.getenv("LLM_PROVIDER", "anthropic").lower()  # "anthropic" or "openai" (any OpenAI-compatible server)
MODEL = os.getenv("LLM_MODEL", "claude-sonnet-5-5")  # model (or gateway alias) that reads photos
TEXT_MODEL = os.getenv("LLM_TEXT_MODEL") or MODEL  # model (or gateway alias) for recommendations
BASE_URL = os.getenv("LLM_BASE_URL", "http://localhost:8000/v1")  # OpenAI-compatible only
CLIENT_ID = os.getenv("LLM_CLIENT_ID", "shelfscan")  # sent as x-client-id, for gateway usage logs
# Photos go to the model one per request: smaller, faster calls, and gateways often cap images per request.
CONCURRENCY = max(1, int(os.getenv("LLM_CONCURRENCY", "2")))
API_KEY = os.getenv("LLM_API_KEY", "")  # OpenAI-compatible only; leave empty if your gateway has no auth
TIMEOUT = float(os.getenv("LLM_TIMEOUT", "240"))  # keep below the app's 300 s read timeout
# Local vision models get much slower with image size (Qwen-VL spends ~1 token per 28x28 px patch).
# 1024 px is about 2.5x fewer image tokens than the app's 1600 px and still reads most spines. 0 = don't resize.
MAX_IMAGE_EDGE = int(os.getenv("LLM_MAX_IMAGE_EDGE", "1024"))
MAX_TOKENS = int(os.getenv("LLM_MAX_TOKENS", "2048"))
# Extra JSON merged into each request, e.g. '{"chat_template_kwargs": {"enable_thinking": false}}' for Qwen3
EXTRA_BODY = json.loads(os.getenv("LLM_EXTRA_BODY", "") or "{}")
log = logging.getLogger("shelfscan.llm")

IDENTIFY_TOOL = {
    "name": "report_books",
    "description": "Report every book visible in the photos.",
    "input_schema": {
        "type": "object",
        "properties": {
            "books": {
                "type": "array",
                "items": {
                    "type": "object",
                    "properties": {
                        "title": {"type": "string"},
                        "author": {"type": "string", "description": "Full author name; 'Unknown' if unreadable"},
                        "genres": {"type": "array", "items": {"type": "string"}, "description": "1-3 broad genres"},
                        "confidence": {"type": "number", "description": "0-1, how sure you are of title+author"},
                    },
                    "required": ["title", "author", "genres", "confidence"],
                },
            },
            "notes": {"type": "string", "description": "Optional: anything the user should know (blurry spines, partial titles)"},
        },
        "required": ["books"],
    },
}

IDENTIFY_PROMPT = """These photos show physical books the user owns — covers, spines on a shelf, or stacks.
Identify every distinct book whose title you can read. Copy each title as printed; never invent or
complete a title you can't fully see. You may supply the author when only the title is visible and you are
sure of it. An author's name is not a title. Don't list the same book twice across photos.
Skip anything that isn't a book or that you would be guessing at. Call report_books with the results."""

RECOMMEND_TOOL = {
    "name": "recommend_books",
    "description": "Recommend books the user does not already own.",
    "input_schema": {
        "type": "object",
        "properties": {
            "recommendations": {
                "type": "array",
                "items": {
                    "type": "object",
                    "properties": {
                        "title": {"type": "string"},
                        "author": {"type": "string"},
                        "genres": {"type": "array", "items": {"type": "string"}},
                        "reason": {"type": "string", "description": "One sentence tying it to books they own"},
                    },
                    "required": ["title", "author", "genres", "reason"],
                },
            }
        },
        "required": ["recommendations"],
    },
}


SERIES_TOOL = {
    "name": "report_series",
    "description": "Report the numbered series the user's books belong to, with every published main volume.",
    "input_schema": {
        "type": "object",
        "properties": {
            "series": {
                "type": "array",
                "items": {
                    "type": "object",
                    "properties": {
                        "name": {"type": "string"},
                        "author": {"type": "string"},
                        "volumes": {
                            "type": "array",
                            "items": {
                                "type": "object",
                                "properties": {"number": {"type": "integer"}, "title": {"type": "string"}},
                                "required": ["number", "title"],
                            },
                        },
                    },
                    "required": ["name", "author", "volumes"],
                },
            }
        },
        "required": ["series"],
    },
}


SINGLE_PHOTO_PROMPT = """This photo shows physical books the user owns: covers, spines on a shelf, or a stack.
Identify every distinct book whose title you can read. Copy each title exactly as printed; never invent or
complete a title you can't fully see. You may supply the author when only the title is visible and you are
sure of it, otherwise write Unknown. An author's name is not a title: if a spine shows only an author, skip it.
Skip anything that isn't a book. Leave out any book you would be guessing at; a shorter, correct list is better."""

JSON_IDENTIFY_SUFFIX = """
Reply with ONLY a JSON object, no prose and no code fences, in exactly this shape:
{"books": [{"title": "...", "author": "...", "genres": ["..."], "confidence": 0.0}], "notes": "optional"}
- author: full name, or "Unknown" if you can't tell
- genres: 1-3 broad genres
- confidence: 0 to 1, how sure you are of title and author
If you see no books, return {"books": [], "notes": "why"}."""

# One short line per book costs about 20 tokens; the JSON shape above costs about 60. Local models read
# dense shelves, so the reply has to be cheap enough to list 80+ books without hitting max_tokens.
LINE_IDENTIFY_SUFFIX = """
Reply with ONLY one line per book, in exactly this format, with no prose, numbering or code fences:
title | author | genre
- author: full name, or Unknown if you can't tell
- genre: one broad genre, such as Thriller or Science Fiction
If you see no books, reply with the single word NONE."""

CUT_OFF_NOTE = "The model's reply was cut off, so some books may be missing."
FAILED_PHOTO_NOTE = "couldn't be processed; try it again."


def identify_was_degraded(notes: str | None) -> bool:
    """True when the notes say the read is incomplete (a cut-off reply or a failed photo). Such an answer
    is shown to the user but never cached, so rescanning the photo gets a fresh attempt."""
    return bool(notes) and (CUT_OFF_NOTE in notes or FAILED_PHOTO_NOTE in notes)


def _fingerprint(*parts: str) -> str:
    import hashlib
    return hashlib.sha256("\x1f".join(parts).encode()).hexdigest()[:12]


# Changes whenever a photo prompt is edited, so cached photo reads never outlive the prompt that made them.
IDENTIFY_PROMPT_FINGERPRINT = _fingerprint(IDENTIFY_PROMPT, SINGLE_PHOTO_PROMPT, LINE_IDENTIFY_SUFFIX, JSON_IDENTIFY_SUFFIX)

# Bump when recommend_prompt or the recommendation reply format changes, so cached answers are not reused.
RECOMMEND_PROMPT_VERSION = "2"  # 2: "similar to this book" focus; picks carry Open Library covers

JSON_RECOMMEND_SUFFIX = """
Reply with ONLY a JSON object, no prose and no code fences, in exactly this shape:
{"recommendations": [{"title": "...", "author": "...", "genres": ["..."], "reason": "one short sentence"}]}"""


def parse_json_reply(text: str) -> dict:
    """Pull the JSON object out of a model reply, tolerating code fences, <think> blocks and chatter."""
    text = re.sub(r"<think>.*?</think>", "", text, flags=re.S).strip()
    fenced = re.search(r"```(?:json)?\s*(.*?)```", text, flags=re.S)
    if fenced:
        text = fenced.group(1)
    start, end = text.find("{"), text.rfind("}")
    if start == -1 or end <= start:
        return {}
    try:
        return json.loads(text[start : end + 1])
    except json.JSONDecodeError:
        return {}


_BULLET = re.compile(r"^\s*(?:[-*\u2022]|\d+[.)])\s+")


def parse_book_lines(text: str, truncated: bool = False) -> list[dict]:
    """Read `title | author | genre` lines. Skips prose, a repeated header, and repeated books.

    When the reply was cut off (`truncated`), the last line may be half written, so it is dropped.
    Unlike JSON, every complete line is usable on its own, so a cut-off loses at most one book.
    """
    text = re.sub(r"<think>.*?</think>", "", text, flags=re.S)
    text = re.sub(r"```[a-zA-Z]*", "", text)
    lines = [ln for ln in text.splitlines() if ln.strip()]
    if truncated and lines:
        lines = lines[:-1]
    seen: set[str] = set()
    books: list[dict] = []
    for line in lines:
        line = _BULLET.sub("", line).strip()
        if "|" not in line:
            continue  # prose such as "Here are the books:", or NONE
        parts = [p.strip().strip("*\"") for p in line.split("|")]
        title = parts[0]
        if not title or title.lower() == "title":
            continue
        author = parts[1] if len(parts) > 1 and parts[1] else "Unknown"
        genre = parts[2] if len(parts) > 2 and parts[2] and parts[2].lower() != "unknown" else None
        key = re.sub(r"[^a-z0-9]", "", (title + author).lower())
        if key in seen:
            continue  # a looping model repeats itself; keep the first
        seen.add(key)
        books.append({"title": title, "author": author, "genres": [genre] if genre else []})
    return books


def salvage_json_reply(text: str) -> dict:
    """Recover the complete items from a reply that was cut off mid-list (the model hit max_tokens).

    A truncated reply is not valid JSON, so parse_json_reply gives up on it and the whole result is lost.
    This keeps every fully written book, recommendation or series before the cut. Returns {} if there is none.
    """
    text = re.sub(r"<think>.*?</think>", "", text, flags=re.S)
    decoder, found = json.JSONDecoder(), {}
    for key in ("books", "recommendations", "series"):
        opened = re.search(r'"%s"\s*:\s*\[' % key, text)
        if not opened:
            continue
        pos, items = opened.end(), []
        while True:
            pos = re.compile(r"[\s,]*").match(text, pos).end()
            if pos >= len(text) or text[pos] != "{":
                break
            try:
                item, pos = decoder.raw_decode(text, pos)
            except json.JSONDecodeError:
                break  # the item the reply was cut in the middle of
            items.append(item)
        if items:
            found[key] = items
    return found


def recommend_prompt(owned: list[OwnedBook], limit: int, author: str | None = None,
                     genre: str | None = None, similar_to: OwnedBook | None = None) -> str:
    """One prompt for every model. A focus narrows the ask; the shelf is still sent for taste."""
    shelf = "\n".join(f"- {b.title} by {b.author}" + (f" [{', '.join(b.genres)}]" if b.genres else "") for b in owned)
    rules = (
        "Only recommend real, published books you are confident exist, with the author's full name. "
        "Never recommend a book from the list above. "
        "Each reason is one short sentence (under 15 words) naming what connects it to their books."
    )
    if similar_to:
        ask = (
            f"Recommend {limit} books similar to {similar_to.title} by {similar_to.author}: close in theme, "
            f"tone or style. At most one may be by {similar_to.author}; the rest by other authors."
        )
        rules = rules.replace("connects it to their books", f"it shares with {similar_to.title}")
    elif author:
        ask = (
            f"They like {author}. Recommend {limit} books by OTHER authors that a reader of {author} would "
            f"love. Do not include anything written by {author}."
        )
    elif genre:
        ask = (
            f"Recommend {limit} {genre} books they don't own. Mix well-loved essentials of the genre with "
            "a few less obvious picks that fit their taste."
        )
    else:
        ask = (
            f"Recommend {limit} books they don't own, mixing close matches with a few that stretch their "
            "taste. Favor authors they don't own yet."
        )
    return f"Here is the user's personal library:\n{shelf}\n\n{ask}\n{rules}"


# Bump when series_prompt or the series reply format changes, so cached answers are not reused.
SERIES_PROMPT_VERSION = "1"

JSON_SERIES_SUFFIX = """
Reply with ONLY a JSON object, no prose and no code fences, in exactly this shape:
{"series": [{"name": "...", "author": "...", "volumes": [{"number": 1, "title": "..."}]}]}"""


def series_prompt(owned: list[OwnedBook]) -> str:
    """Ask for the full published run of each series on the shelf. Which volumes are owned is worked out by the
    server, not the model, and every missing volume is checked against the catalog before it is shown."""
    shelf = "\n".join(f"- {b.title} by {b.author}" for b in owned)
    return (
        f"Here is the user's personal library:\n{shelf}\n\n"
        "Which of these books belong to a numbered book series? For each such series, list every main volume "
        "that has already been published, in reading order, with the number the series gives it. Use each "
        "volume's usual English title (or its original title if it was never translated) and the author's full name.\n"
        "Rules:\n"
        "- Only series you are sure of, and only volumes that really exist and are already out. Leave out announced books.\n"
        "- Main numbered volumes only: no novellas, short-story collections, companions or box sets unless the series "
        "itself gives them a whole number.\n"
        "- Include a series even if the user owns only one of its books.\n"
        "- Skip standalone books."
    )


def synopsis_prompt(title: str, author: str, source: str) -> str:
    return (
        f"Below is the publisher's description of {title} by {author}.\n\n<description>\n{source[:4000]}\n</description>\n\n"
        "Write a spoiler-free synopsis in 2 or 3 plain sentences, at most 60 words. Say what the book is about: "
        "the premise, the main character or subject, and the central question or stakes. Use only facts from the "
        "description. No praise, review quotes, awards, sales figures or calls to read it. "
        "Reply with only the synopsis."
    )


def clean_synopsis(text: str) -> str:
    """Strip reasoning blocks, fences, a leading label and wrapping quotes from a model's synopsis."""
    text = re.sub(r"<think>.*?</think>", "", text, flags=re.S)
    text = re.sub(r"```[a-zA-Z]*", "", text).strip()
    text = re.sub(r"^(synopsis|summary)\s*:\s*", "", text, flags=re.I).strip()
    return text.strip('"\u201c\u201d ').strip()


class LLM(Protocol):
    async def identify(self, images: list[tuple[bytes, str]]) -> tuple[list[Book], str | None]: ...
    async def recommend(self, owned: list[OwnedBook], limit: int, author: str | None = None,
                        genre: str | None = None, similar_to: OwnedBook | None = None) -> list[tuple[Book, str]]: ...
    async def summarize(self, title: str, author: str, source: str) -> str: ...
    async def series(self, owned: list[OwnedBook]) -> tuple[list[dict], bool]: ...


class ClaudeLLM:
    def __init__(self, client: AsyncAnthropic | None = None):
        self.client = client or AsyncAnthropic()

    async def summarize(self, title: str, author: str, source: str) -> str:
        return await _claude_summarize(self.client, title, author, source)

    async def _tool_call(self, tool: dict, content: list[dict], max_tokens: int = 4096) -> dict:
        msg = await self.client.messages.create(
            model=MODEL,
            max_tokens=max_tokens,
            tools=[tool],
            tool_choice={"type": "tool", "name": tool["name"]},
            messages=[{"role": "user", "content": content}],
        )
        for block in msg.content:
            if block.type == "tool_use":
                return block.input
        return {}

    async def identify(self, images: list[tuple[bytes, str]]) -> tuple[list[Book], str | None]:
        content: list[dict] = [
            {
                "type": "image",
                "source": {"type": "base64", "media_type": mime, "data": base64.b64encode(data).decode()},
            }
            for data, mime in images
        ]
        content.append({"type": "text", "text": IDENTIFY_PROMPT})
        result = await self._tool_call(IDENTIFY_TOOL, content)
        books = [Book(**b) for b in result.get("books", []) if b.get("title")]
        return books, result.get("notes")

    async def recommend(self, owned: list[OwnedBook], limit: int, author: str | None = None,
                        genre: str | None = None, similar_to: OwnedBook | None = None) -> list[tuple[Book, str]]:
        prompt = recommend_prompt(owned, limit, author, genre, similar_to) + "\nCall recommend_books."
        result = await self._tool_call(RECOMMEND_TOOL, [{"type": "text", "text": prompt}])
        return [
            (Book(title=r["title"], author=r["author"], genres=r.get("genres", [])), r.get("reason", ""))
            for r in result.get("recommendations", [])
        ]

    async def series(self, owned: list[OwnedBook]) -> tuple[list[dict], bool]:
        """The series on the shelf, and whether the answer is complete."""
        prompt = series_prompt(owned) + "\nCall report_series."
        result = await self._tool_call(SERIES_TOOL, [{"type": "text", "text": prompt}], max_tokens=8192)
        return list(result.get("series", []) or []), bool(result)


async def _claude_summarize(client: AsyncAnthropic, title: str, author: str, source: str) -> str:
    msg = await client.messages.create(
        model=MODEL, max_tokens=300,
        messages=[{"role": "user", "content": synopsis_prompt(title, author, source)}],
    )
    return clean_synopsis("".join(b.text for b in msg.content if b.type == "text"))


class OpenAICompatLLM:
    """Any server speaking the OpenAI /chat/completions API: LLM gateways, vLLM, Ollama, LM Studio,
    llama.cpp. Asks for plain JSON instead of tool calls, since tool support varies across local models.
    Photo identification needs a vision model (e.g. Qwen2.5-VL / Qwen3-VL), not a text-only Qwen."""

    def __init__(self, base_url: str = BASE_URL, model: str = MODEL, api_key: str = API_KEY,
                 client: httpx.AsyncClient | None = None, text_model: str | None = None,
                 concurrency: int = CONCURRENCY):
        self.url = base_url.rstrip("/") + "/chat/completions"
        self.model = model
        self.text_model = text_model or (TEXT_MODEL if model == MODEL else model)
        self.headers = {"x-client-id": CLIENT_ID}
        if api_key:
            self.headers["Authorization"] = f"Bearer {api_key}"
        self.client = client or httpx.AsyncClient(timeout=TIMEOUT)
        self.limit = asyncio.Semaphore(concurrency)

    async def _complete(self, content: list[dict] | str, max_tokens: int = MAX_TOKENS,
                        model: str | None = None) -> tuple[str, str | None]:
        """One chat call. Returns the reply text and the finish reason ("length" means it was cut off)."""
        body = {
            "model": model or self.model,
            "messages": [{"role": "user", "content": content}],
            "max_tokens": max_tokens,
            "temperature": 0.1,
            **EXTRA_BODY,
        }
        started = time.monotonic()
        try:
            async with self.limit:
                r = await self.client.post(self.url, json=body, headers=self.headers)
        except httpx.TimeoutException:
            log.error("LLM server didn't answer within %.0f s (LLM_TIMEOUT)", TIMEOUT)
            raise
        except httpx.ConnectError:
            log.error("Can't connect to the LLM server at %s; is it running?", self.url)
            raise
        usage = r.json().get("usage", {}) if r.status_code < 400 else {}
        finish = r.json()["choices"][0].get("finish_reason") if r.status_code < 400 else None
        log.info("LLM call took %.1f s (prompt=%s tokens, completion=%s tokens, finish=%s)",
                 time.monotonic() - started, usage.get("prompt_tokens", "?"), usage.get("completion_tokens", "?"), finish)
        if r.status_code >= 400:
            log.error("LLM server returned %s: %s", r.status_code, r.text[:500])
        r.raise_for_status()
        return r.json()["choices"][0]["message"].get("content") or "", finish

    @staticmethod
    def _decode_json(text: str, finish: str | None, max_tokens: int) -> dict:
        data = parse_json_reply(text)
        if not data:
            data = salvage_json_reply(text)
            if data:
                kept = sum(len(v) for v in data.values())
                log.warning("Model reply was cut off (finish=%s, max_tokens=%s); kept %d complete item(s)", finish, max_tokens, kept)
                data.setdefault("notes", CUT_OFF_NOTE)
            else:
                log.warning("Couldn't parse JSON from model reply (finish=%s): %s", finish, text[:500])
        return data

    async def _chat(self, content: list[dict] | str, max_tokens: int = MAX_TOKENS, model: str | None = None) -> dict:
        text, finish = await self._complete(content, max_tokens, model)
        return self._decode_json(text, finish, max_tokens)

    async def identify(self, images: list[tuple[bytes, str]]) -> tuple[list[Book], str | None]:
        outcomes = await asyncio.gather(
            *(self._identify_one(data, mime) for data, mime in images), return_exceptions=True)
        failed = [i + 1 for i, o in enumerate(outcomes) if isinstance(o, BaseException)]
        if len(failed) == len(outcomes):
            raise outcomes[0]  # nothing worked: surface the real error
        for i in failed:
            log.error("Photo %d failed: %r", i, outcomes[i - 1])
        results = [o for o in outcomes if not isinstance(o, BaseException)]
        books = [b for found, _ in results for b in found]  # duplicates across photos are merged later
        notes = list(dict.fromkeys(n for _, n in results if n))
        if failed:
            notes.append(f"Photo {', '.join(map(str, failed))} {FAILED_PHOTO_NOTE}")
        return books, " ".join(notes) or None

    async def _identify_one(self, data: bytes, mime: str) -> tuple[list[Book], str | None]:
        data, mime = shrink_image(data, mime)
        content: list[dict] = [
            {"type": "image_url", "image_url": {"url": f"data:{mime};base64,{base64.b64encode(data).decode()}"}},
            {"type": "text", "text": SINGLE_PHOTO_PROMPT + LINE_IDENTIFY_SUFFIX},
        ]
        text, finish = await self._complete(content)
        if '"books"' in text:  # the model answered in JSON anyway: still understand it
            result = self._decode_json(text, finish, MAX_TOKENS)
        else:
            truncated = finish == "length"
            lines = parse_book_lines(text, truncated)
            if not lines and text.strip() and text.strip().upper() != "NONE":
                log.warning("Couldn't read any book lines from the model reply (finish=%s): %s", finish, text[:500])
            if truncated:
                log.warning("Model reply was cut off (finish=%s, max_tokens=%s); kept %d book(s)", finish, MAX_TOKENS, len(lines))
            result = {"books": lines,
                      "notes": CUT_OFF_NOTE if truncated else None}
        books = []
        for b in result.get("books", []) or []:
            if not isinstance(b, dict) or not str(b.get("title", "")).strip():
                continue
            books.append(Book(
                title=str(b["title"]).strip(),
                author=str(b.get("author") or "Unknown").strip(),
                genres=[str(g) for g in (b.get("genres") or [])][:3],
                confidence=_as_float(b.get("confidence"), 0.7),
            ))
        return books, result.get("notes") or None

    async def recommend(self, owned: list[OwnedBook], limit: int, author: str | None = None,
                        genre: str | None = None, similar_to: OwnedBook | None = None) -> list[tuple[Book, str]]:
        prompt = recommend_prompt(owned, limit, author, genre, similar_to) + JSON_RECOMMEND_SUFFIX
        result = await self._chat(prompt, model=self.text_model)
        out = []
        for r in result.get("recommendations", []) or []:
            if isinstance(r, dict) and r.get("title") and r.get("author"):
                out.append((Book(title=r["title"], author=r["author"], genres=r.get("genres") or []), r.get("reason", "")))
        return out

    async def series(self, owned: list[OwnedBook]) -> tuple[list[dict], bool]:
        """The series on the shelf, and whether the answer is complete (False when the reply was cut off)."""
        max_tokens = max(MAX_TOKENS, 4096)  # a long run of series and volumes doesn't fit in a photo-sized reply
        text, finish = await self._complete(series_prompt(owned) + JSON_SERIES_SUFFIX, max_tokens, model=self.text_model)
        data = self._decode_json(text, finish, max_tokens)
        series = [s for s in (data.get("series") or []) if isinstance(s, dict)]
        return series, finish != "length" and bool(data)

    async def summarize(self, title: str, author: str, source: str) -> str:
        text, _ = await self._complete(synopsis_prompt(title, author, source), max_tokens=400, model=self.text_model)
        return clean_synopsis(text)


def shrink_image(data: bytes, mime: str, max_edge: int = MAX_IMAGE_EDGE) -> tuple[bytes, str]:
    """Downscale so the long edge is at most max_edge. Returns the input unchanged if already small."""
    if max_edge <= 0:
        return data, mime
    from PIL import Image, ImageOps

    try:
        img = Image.open(io.BytesIO(data))
        if max(img.size) <= max_edge:
            return data, mime
        img = ImageOps.exif_transpose(img).convert("RGB")
        img.thumbnail((max_edge, max_edge))
        out = io.BytesIO()
        img.save(out, "JPEG", quality=85)
        return out.getvalue(), "image/jpeg"
    except Exception as e:  # unreadable image: send as-is and let the model try
        log.warning("Couldn't resize image (%s); sending original", e)
        return data, mime


def _as_float(v, default: float) -> float:
    try:
        return max(0.0, min(1.0, float(v)))
    except (TypeError, ValueError):
        return default


def create_llm() -> LLM:
    if PROVIDER in ("openai", "openai-compatible", "gateway", "local"):
        log.info("Using OpenAI-compatible LLM at %s (photos: %s, recommendations: %s)", BASE_URL, MODEL, TEXT_MODEL)
        return OpenAICompatLLM()
    log.info("Using Anthropic (model=%s)", MODEL)
    return ClaudeLLM()
