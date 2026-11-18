# ShelfScan

An Android app that turns photos of your physical books into a searchable digital library, then suggests what to read next and flags upcoming releases from authors you own.

```
Android app (Kotlin, Compose, Room)          Backend (FastAPI)                  External
┌──────────────────────────────┐   HTTPS   ┌───────────────────────┐
│ Scan: camera / photo picker  │ ────────▶ │ POST /identify         │ ──▶ Claude (vision, tool use)
│   → downscale to 1600px JPEG │           │   dedupe + enrich      │ ──▶ Google Books (ISBN, cover, genres)
│ Review & fix → Room DB       │           │ POST /recommendations  │ ──▶ Claude → Google Books
│ Library: All/Author/Genre    │           │ POST /upcoming         │ ──▶ Google Books (future pub dates)
│ Discover: For you / Coming   │           └───────────────────────┘
└──────────────────────────────┘
```

The library lives on-device (Room). The backend is stateless and holds the API key, so no secret ships in the APK.

## 1. Run the backend

```bash
cd backend
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env            # add your ANTHROPIC_API_KEY
set -a; source .env; set +a
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

Check it: `curl localhost:8000/health`. Or `docker build -t shelfscan . && docker run --env-file .env -p 8000:8000 shelfscan`.

Tests (LLM and Google Books mocked): `pip install pytest respx && pytest -q`

On Windows, once `backend\.env` exists (copy `.env.example`), `backend\start-server.ps1` loads it and starts the
server on port 8001 in one step.

## 2. Run the app

1. Open `android/` in Android Studio (Ladybug or newer) and let Gradle sync.
2. Server address is in `android/gradle.properties`:
   - Emulator: leave `http://10.0.2.2:8000/` (that's your computer from the emulator).
   - Physical phone on the same Wi-Fi: use your computer's LAN IP, e.g. `http://192.168.1.20:8000/`.
3. Run the `app` configuration.

Debug builds allow plain HTTP for local testing; release builds require HTTPS, so deploy the backend behind TLS before shipping.

## How it works

**Scanning.** Up to 6 photos per batch. The app downscales each to 1600px on the long edge with EXIF rotation applied, then uploads. The backend asks Claude to list every book via a forced tool call (structured JSON with a per-book confidence), then matches each against Google Books for ISBN, cover, description, page count and categories. Unmatched books keep the LLM's data rather than being dropped.

**One book at a time.** *Barcode* uses Google's code scanner (no camera permission). The ISBN is looked up in
Google Books, then Open Library, and the book goes through the same review step as a photo. *Type a title* searches as you type
(Google Books, then Open Library) and adds a result with one tap.

**Offline.** If the server can't be reached when you scan, the photos are kept on the phone and read automatically once
it's back (WorkManager, retrying with backoff); they then wait under Add books, and a banner in the library, for review.
Discover and Similar books keep their last results on the phone and show them, marked offline, when the server is down.

**Review.** Low-confidence matches (<0.6) start unchecked and are flagged; books already on your shelf are marked and can't be re-added. Tap the pencil to fix a misread title before saving. Duplicates are also blocked at the database level by a normalized title+author key.

**Library.** Grid view plus Author and Genre shelves (co-authored books appear under each author). Search covers title, author and genre. Tapping a genre chip on a book opens the library filtered to it. Books marked *Reading* get their own row at the top.
The sort-and-filter button orders books by recently added, title (ignoring "The"/"A") or author (shelved by last name,
so Le Guin is under L), and shows all books or only Unread / Reading / Read. Long-press a book to select several, then
mark them as read, reading or unread, or remove them (with Undo).

**Backup.** The ⋮ menu exports the library and the Want to read list to a JSON file you save anywhere (Drive,
Downloads), and imports one back, adding only what's missing. Use it to move to a new phone.

**Book page.** Unread / Reading / Read, a large cover (tap *Change cover* to pick another edition's artwork), a 2–3 sentence spoiler-free synopsis with the full description one tap away, and a
*Similar books* row. The synopsis is condensed from the publisher's (or Open Library's) description, so the model never
invents a plot; short descriptions are shown as they are, and if the model fails the description's opening is used.
It is saved on the device. A background job (WorkManager, one book at a time, retried if the server is down) fetches
the synopsis and large cover for newly added books, and their similar books, right after they're added, so a book page
is usually complete the first time it's opened. Similar books are kept on the phone for a week; an older copy is shown
instantly while a fresh one loads. *More by {author}* opens Discover on that author.

**Discover.**
- *For you*: chips for **All**, your top genres and your most-owned authors. An author shows *More by {author}*
  (real books from Google Books: editions merged, study guides and unreleased books skipped) and *If you like {author}*
  (other writers). A genre shows picks within it. Tapping a pick opens a sheet with the reason and description.
- Every model suggestion is checked against Google Books; titles it can't find are dropped as likely invented, and books
  you own are removed. If Google Books is unreachable, picks are shown unchecked rather than hidden.
- *Coming soon*: for your top 40 authors, Google Books is queried newest-first and filtered to publication dates after today.
- *Want to read*: suggestions you saved with the bookmark button. *I have this book* moves one into the library, and
  scanning a book you saved removes it from the list. Saved books aren't suggested again.

**Covers.** Open Library's large covers (matched on title and author, addressed by cover id, so not rate limited), else
Google Books' largest image, else the app's own placeholder. Scanned titles are also tried without series decorations
("(Stormlight Archive, #1)", "Book 1:"), and by title alone when the author couldn't be read. Books still without a cover
are looked up again on launch, at most every three days each. Downloaded covers are kept in a 300 MB on-device cache,
so they load instantly and offline. Set `SHELFSCAN_OPEN_LIBRARY=0` to use Google Books only.

## Caching recommendations and photo reads (Redis, optional)

`/recommendations` takes a model call plus a Google Books check per pick, and the answer rarely changes. Set `REDIS_URL`
and the finished response is stored for a week; leave it unset and nothing is cached.

| Setting | Default | Meaning |
|---|---|---|
| `REDIS_URL` | empty (off) | e.g. `redis://localhost:6379/0` |
| `RECS_CACHE_TTL_S` | 604800 (7 days) | how long an answer is kept |
| `RECS_CACHE_VERSION` | `1` | bump to discard every stored answer (for example after changing the model behind the gateway alias) |
| `REDIS_TIMEOUT_S` / `REDIS_COOLDOWN_S` | 0.25 / 30 | a down Redis is skipped for 30 s after an error, so requests never wait on it |

- The key covers the books the model sees, the author/genre focus, the limit, the model and the prompt version. Adding or
  removing a book changes the key, so the next request recomputes.
- Each response has an `X-Cache` header: `HIT`, `MISS` or `BYPASS`. Send `"refresh": true` in the body to force a new answer.
- Answers are not stored when the model call failed or Google Books was unreachable, so a degraded result is never kept.
- If Redis is down or missing, requests work exactly as before.

**Photo reads (`/identify`).** The same photo is read by the model once. The key is a hash of the exact photo bytes
plus the model, the prompt and the guardrail version, so a rescan, or a retry after the phone gave up waiting, returns
the stored books instantly (`X-Cache: HIT`). A retry that arrives while the first scan is still running waits for it
and then gets the same answer. Pass `?refresh=true` to read the photo again. A result is not stored when it is empty,
when the reply was cut off or a photo failed, or when Google Books couldn't be reached, so rescanning always gets a
fresh attempt after a bad read. Editing a photo prompt invalidates stored reads automatically; bump
`IDENTIFY_LOGIC_VERSION` in `main.py` when the guardrails or book matching change.

| Setting | Default | Meaning |
|---|---|---|
| `IDENTIFY_CACHE_TTL_S` | 2592000 (30 days) | how long a photo's result is kept |

Run Redis on Windows (pick one, and keep the data and installs on D:):
- **Memurai Developer** (Windows-native, Redis-compatible): choose a folder on D: in the installer.
- **Docker**: `docker run -d --name shelfscan-redis -p 6379:6379 redis:7-alpine`. Docker Desktop keeps its disk image on C: by
  default; move it in Settings > Resources.
- **WSL2**: `sudo apt install redis-server && sudo service redis-server start`.

Then `REDIS_URL=redis://localhost:6379/0` before starting the backend. Don't expose port 6379 beyond this PC.

## Guardrails against made-up books

A small vision model will sometimes "read" a book that isn't on the shelf, or report an author's name as a
title. `/identify` therefore only returns a book when it passes two checks:

1. **Sanity rules** (`app/guard.py`): no empty or placeholder titles, and a title that is just the author's
   name or surname ("Brown" by Dan Brown) is rejected without a lookup.
2. **Catalog check**: Google Books must have a record with the same title and author. No record means the
   read is probably wrong, so the book is left out and `notes` says which spines were skipped.

If Google Books can't be reached the books are kept as read and `notes` says they weren't checked, because
"couldn't verify" isn't "wrong". Set `IDENTIFY_UNVERIFIED=keep` to return unmatched books anyway (the old
behaviour). Trade-off: self-published or very obscure books the catalog lacks are skipped, so use `keep` if
your shelf is full of them.

**Near-misses and duplicates** (`app/identity.py`): if the exact title isn't in the catalog but one by the
same author is almost identical ("Shadow of Wars" vs "StarCraft: Shadow Wars"), the book is corrected to the
catalog's title instead of being dropped. Numbers must match exactly, so "Book 2" never becomes "Book 1".
Each book is then returned once: reads are merged when they share an ISBN, or have the same title (a
subtitle or series prefix doesn't matter) and a compatible author ("C. Golden", "Golden, Christie" and
"Unknown" all fit "Christie Golden"). Merging is idempotent, so running it again changes nothing.

## API

| Endpoint | Body | Returns |
|---|---|---|
| `POST /identify` | multipart `images` (1–6, jpeg/png/webp, ≤5 MB) | `{books: Book[], notes?}` |
| `POST /recommendations` | `{books: [{title, author, genres}], limit?, author?, genre?, similar_to?, refresh?}` | `{recommendations: [{book, reason, section}]}` |
| `POST /book/details` | `{title, author, isbn?, description?}` | `{cover_url, description, synopsis, isbn, published_date, page_count}` |
| `POST /covers` | `{books: [{title, author, isbn?}]}` (≤50) | `{covers: [{cover_url, isbn}]}` in request order |
| `POST /covers/candidates` | `{title, author, isbn?}` | `{candidates: [url]}`, best first |
| `POST /lookup/isbn` | `{isbn}` | `{book: Book \| null}` |
| `POST /search` | `{query, limit?}` | `{books: Book[]}`, best match first |
| `POST /upcoming` | `{authors: string[]}` | `{books: Book[]}` sorted by date |

Set `APP_TOKEN` on the server and `shelfscan.appToken` in the app to require an `X-App-Token` header. That keeps casual traffic off your API key; for a public release, replace it with real auth and rate limiting.

## Releasing

Signed builds for Google Play's internal testing track: see [docs/RELEASE.md](docs/RELEASE.md). Release builds read
the upload key and the HTTPS server address from `android/release.properties` (git-ignored; start from
`android/release.properties.example`) and refuse to build without them. Store art, listing text and a draft privacy
policy are in `docs/`.

## Known limits and next steps

- Google Books' future-release coverage is decent for major publishers and spotty for small presses. Adding Open Library or a publisher feed would widen it.
- Upcoming-release *notifications* would be a WorkManager job that calls `/upcoming` weekly and diffs against what it has seen.
- The Room database is at schema v3 (v2 adds the saved synopsis; v3 adds reading status, the cover-retry time and the
  Want to read table). Add a migration with every entity change.
- A "want to read" list from Discover, and reading status (read / reading / to read) on owned books, are natural next features.
