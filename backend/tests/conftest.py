import os

# Most tests mock only Google Books; keep them from reaching for Open Library unless a test opts in.
os.environ.setdefault("SHELFSCAN_OPEN_LIBRARY", "0")
