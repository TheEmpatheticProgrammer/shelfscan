"""Series gaps: "You own Stormlight 1, 2 and 4. #3 is missing."

The model lists the published volumes of each series on the shelf. Everything after that is decided here,
so a model's slip can't put a made-up book on the screen: which volumes are owned is matched against the
user's own titles, a series is only shown when something is really missing, and every missing volume must
be found in the catalog (and already be out) before it is shown.
"""
from __future__ import annotations

import asyncio
import logging
from dataclasses import dataclass, field
from datetime import date

from .catalog import _authors_match, _titles_match
from .models import Book, OwnedBook, SeriesGap, SeriesVolume

MAX_SERIES = 20  # more than a screenful; the rest are usually one-book-owned series
MAX_LOOKUPS = 60  # catalog checks per request, so a huge shelf can't fire hundreds of rate-limited lookups
LOOKUP_CONCURRENCY = 6

log = logging.getLogger("shelfscan.series")


@dataclass
class Plan:
    name: str
    author: str
    volumes: list[SeriesVolume] = field(default_factory=list)

    @property
    def owned(self) -> list[int]:
        return [v.number for v in self.volumes if v.owned]

    @property
    def gaps(self) -> list[SeriesVolume]:
        """Missing volumes that come before the last one owned: the holes in the set."""
        top = max(self.owned, default=0)
        return [v for v in self.volumes if not v.owned and v.number < top]

    @property
    def missing(self) -> list[SeriesVolume]:
        return [v for v in self.volumes if not v.owned]


def owns(title: str, author: str, shelf: list[OwnedBook]) -> bool:
    """Is this volume on the shelf? Titles may carry series decorations or subtitles on either side."""
    for b in shelf:
        if author and b.author.strip().lower() not in ("", "unknown") and not _authors_match(author, [b.author]):
            continue
        if _titles_match(title, b.title) or _titles_match(b.title, title):
            return True
    return False


def _number(value) -> int | None:
    """Whole volume numbers only: 2.5 is a novella, and text or negatives are a malformed reply."""
    try:
        f = float(value)
    except (TypeError, ValueError):
        return None
    return int(f) if f == int(f) and 0 <= f < 1000 else None


def plan_gaps(raw: list[dict], shelf: list[OwnedBook]) -> list[Plan]:
    """The series worth showing, most interesting first. A series qualifies when it has a hole before the last
    volume owned, or when at least two volumes are owned and later ones are out. Owning only book 1 of a
    series isn't a gap, and showing every such series would bury the real ones."""
    plans: list[Plan] = []
    seen_names: set[str] = set()
    for s in raw:
        if not isinstance(s, dict):
            continue
        name, author = str(s.get("name") or "").strip(), str(s.get("author") or "").strip()
        if not name or name.lower() in seen_names:
            continue
        volumes: dict[int, SeriesVolume] = {}
        for v in s.get("volumes") or []:
            if not isinstance(v, dict):
                continue
            n, title = _number(v.get("number")), str(v.get("title") or "").strip()
            if n is None or not title or n in volumes:
                continue
            volumes[n] = SeriesVolume(number=n, title=title, owned=owns(title, author, shelf))
        plan = Plan(name, author, [volumes[n] for n in sorted(volumes)])
        if not plan.owned or not plan.missing:
            continue
        if not plan.gaps and len(plan.owned) < 2:
            continue
        seen_names.add(name.lower())
        plans.append(plan)
    plans.sort(key=lambda p: (not p.gaps, -len(p.owned), p.name.lower()))
    return plans[:MAX_SERIES]


def _not_out_yet(book: Book, today: date) -> bool:
    published = (book.published_date or "")[:10]
    return bool(published) and published > today.isoformat()


async def verify_gaps(plans: list[Plan], catalog, today: date | None = None) -> list[SeriesGap]:
    """Check each missing volume against the catalog and attach its record. A volume the catalog has never
    heard of, or one that isn't out yet, is left out; a series with nothing left missing is dropped."""
    today = today or date.today()
    # Holes first, then the volumes after the last one owned, until the lookup budget runs out.
    wanted = [(p, v) for p in plans for v in p.gaps] + [(p, v) for p in plans for v in p.missing if v not in p.gaps]
    wanted = wanted[:MAX_LOOKUPS]
    sem = asyncio.Semaphore(LOOKUP_CONCURRENCY)

    async def check(plan: Plan, vol: SeriesVolume) -> Book | None:
        async with sem:
            return await catalog.verify(Book(title=vol.title, author=plan.author or "Unknown"))

    found = await asyncio.gather(*(check(p, v) for p, v in wanted))
    records = {id(v): book for (_, v), book in zip(wanted, found)}

    out: list[SeriesGap] = []
    for plan in plans:
        volumes: list[SeriesVolume] = []
        for v in plan.volumes:
            if v.owned:
                volumes.append(v)
                continue
            book = records.get(id(v))
            if book is None or _not_out_yet(book, today):
                continue
            volumes.append(v.model_copy(update={"book": book}))
        if any(not v.owned for v in volumes):
            out.append(SeriesGap(name=plan.name, author=plan.author, volumes=volumes))
    log.info("series gaps: %d series shown (%d planned, %d volumes checked)", len(out), len(plans), len(wanted))
    return out
