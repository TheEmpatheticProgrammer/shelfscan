import os

# Most tests mock only Google Books; keep them from reaching for Open Library unless a test opts in.
os.environ.setdefault("SHELFSCAN_OPEN_LIBRARY", "0")


import pytest  # noqa: E402


@pytest.fixture(autouse=True)
def _no_backoff(monkeypatch):
    """Retries after a 429 wait a moment in real life; tests shouldn't."""
    async def instant(_seconds):
        return None
    monkeypatch.setattr("app.catalog._sleep", instant)
