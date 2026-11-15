from pydantic import BaseModel, Field


class Book(BaseModel):
    title: str
    author: str
    genres: list[str] = Field(default_factory=list)
    isbn: str | None = None
    cover_url: str | None = None
    description: str | None = None
    published_date: str | None = None
    page_count: int | None = None
    confidence: float = 1.0


class IdentifyResponse(BaseModel):
    books: list[Book]
    notes: str | None = None


class OwnedBook(BaseModel):
    title: str
    author: str
    genres: list[str] = Field(default_factory=list)


class RecommendRequest(BaseModel):
    books: list[OwnedBook]
    limit: int = Field(default=10, ge=1, le=25)
    author: str | None = None  # focus: more by this author, and readers of them also like...
    genre: str | None = None  # focus: picks within this genre
    similar_to: OwnedBook | None = None  # focus: books like this one (the book page's "Similar books")
    refresh: bool = False  # skip the server cache and compute a fresh answer (the new answer is cached)


class Recommendation(BaseModel):
    book: Book
    reason: str
    # "by_author": other books by the focused author (from the catalog)
    # "similar":   books by other authors (from the model, checked against the catalog)
    section: str = "similar"


class RecommendResponse(BaseModel):
    recommendations: list[Recommendation]


class UpcomingRequest(BaseModel):
    authors: list[str]


class UpcomingResponse(BaseModel):
    books: list[Book]


class BookRef(BaseModel):
    title: str
    author: str
    isbn: str | None = None


class CoversRequest(BaseModel):
    books: list[BookRef] = Field(max_length=50)


class CoverResult(BaseModel):
    cover_url: str | None = None
    isbn: str | None = None


class CoversResponse(BaseModel):
    covers: list[CoverResult]  # same order as the request


class DetailsRequest(BookRef):
    description: str | None = None  # what the app already has, if anything


class DetailsResponse(BaseModel):
    cover_url: str | None = None
    isbn: str | None = None
    description: str | None = None  # the full source text (publisher or Open Library)
    synopsis: str | None = None  # 2-3 spoiler-free sentences condensed from the description
    published_date: str | None = None
    page_count: int | None = None


class CandidatesResponse(BaseModel):
    candidates: list[str]  # cover URLs, best first


class IsbnRequest(BaseModel):
    isbn: str


class IsbnResponse(BaseModel):
    book: Book | None = None  # None when no catalog knows this ISBN


class SearchRequest(BaseModel):
    query: str = Field(min_length=2, max_length=200)
    limit: int = Field(default=15, ge=1, le=30)


class SearchResponse(BaseModel):
    books: list[Book]
