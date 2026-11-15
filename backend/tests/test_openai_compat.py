import asyncio
import json

import httpx
import respx

from app.llm import OpenAICompatLLM, parse_book_lines, parse_json_reply, salvage_json_reply
from app.models import OwnedBook

URL = "http://gateway.local:8080/v1/chat/completions"


def reply(text):
    return httpx.Response(200, json={"choices": [{"message": {"role": "assistant", "content": text}}]})


def test_parse_json_reply_handles_fences_and_think():
    raw = '<think>let me look</think>Sure!\n```json\n{"books": [{"title": "Dune"}]}\n```'
    assert parse_json_reply(raw) == {"books": [{"title": "Dune"}]}
    assert parse_json_reply("no json here") == {}


@respx.mock
def test_identify_sends_images_and_parses_books():
    route = respx.post(URL).mock(return_value=reply(json.dumps({
        "books": [
            {"title": "Dune", "author": "Frank Herbert", "genres": ["Science Fiction"], "confidence": "0.9"},
            {"title": "", "author": "x"},
            {"title": "Mystery", "confidence": "high"},
        ],
        "notes": "one spine blurry",
    })))
    llm = OpenAICompatLLM(base_url="http://gateway.local:8080/v1", model="qwen2.5-vl", api_key="k")
    books, notes = asyncio.run(llm.identify([(b"\xff\xd8", "image/jpeg")]))

    sent = json.loads(route.calls[0].request.content)
    assert sent["model"] == "qwen2.5-vl"
    assert sent["messages"][0]["content"][0]["image_url"]["url"].startswith("data:image/jpeg;base64,")
    assert route.calls[0].request.headers["Authorization"] == "Bearer k"
    assert [(b.title, b.author, b.confidence) for b in books] == [
        ("Dune", "Frank Herbert", 0.9), ("Mystery", "Unknown", 0.7)]
    assert notes == "one spine blurry"


@respx.mock
def test_recommend_without_auth_header():
    route = respx.post(URL).mock(return_value=reply(
        '{"recommendations": [{"title": "Hyperion", "author": "Dan Simmons", "reason": "Epic SF"}]}'))
    llm = OpenAICompatLLM(base_url="http://gateway.local:8080/v1/", model="qwen", api_key="")
    recs = asyncio.run(llm.recommend([OwnedBook(title="Dune", author="Frank Herbert")], 5))
    assert "Authorization" not in route.calls[0].request.headers
    assert [(b.title, why) for b, why in recs] == [("Hyperion", "Epic SF")]


def test_shrink_image_downscales_large_photos():
    import io

    from PIL import Image

    from app.llm import shrink_image

    buf = io.BytesIO()
    Image.new("RGB", (3000, 2000), "white").save(buf, "PNG")
    data, mime = shrink_image(buf.getvalue(), "image/png", max_edge=1024)
    assert mime == "image/jpeg"
    assert max(Image.open(io.BytesIO(data)).size) == 1024

    small = io.BytesIO()
    Image.new("RGB", (800, 600)).save(small, "JPEG")
    assert shrink_image(small.getvalue(), "image/jpeg", max_edge=1024)[0] == small.getvalue()
    assert shrink_image(b"not an image", "image/jpeg", max_edge=1024) == (b"not an image", "image/jpeg")


@respx.mock
def test_one_request_per_photo_with_client_id_and_aliases():
    route = respx.post(URL).mock(side_effect=[
        reply('{"books": [{"title": "Dune", "author": "Frank Herbert", "confidence": 0.9}]}'),
        reply('{"books": [{"title": "Emma", "author": "Jane Austen", "confidence": 0.9}]}'),
    ])
    llm = OpenAICompatLLM(base_url="http://gateway.local:8080/v1", model="books-vision",
                          text_model="books-recommend", api_key="", concurrency=1)
    books, _ = asyncio.run(llm.identify([(b"a", "image/jpeg"), (b"b", "image/jpeg")]))
    assert sorted(b.title for b in books) == ["Dune", "Emma"]
    assert len(route.calls) == 2
    for call in route.calls:
        sent = json.loads(call.request.content)
        assert sent["model"] == "books-vision"
        assert sum(p["type"] == "image_url" for p in sent["messages"][0]["content"]) == 1
        assert call.request.headers["x-client-id"] == "shelfscan"


@respx.mock
def test_one_failed_photo_keeps_the_rest():
    respx.post(URL).mock(side_effect=[
        reply('{"books": [{"title": "Dune", "author": "Frank Herbert"}]}'),
        httpx.Response(502, json={"detail": "provider error: local: timeout"}),
    ])
    llm = OpenAICompatLLM(base_url="http://gateway.local:8080/v1", model="m", api_key="", concurrency=1)
    books, notes = asyncio.run(llm.identify([(b"a", "image/jpeg"), (b"b", "image/jpeg")]))
    assert [b.title for b in books] == ["Dune"]
    assert "Photo 2" in notes


@respx.mock
def test_recommend_uses_text_alias():
    route = respx.post(URL).mock(return_value=reply('{"recommendations": []}'))
    llm = OpenAICompatLLM(base_url="http://gateway.local:8080/v1", model="books-vision",
                          text_model="books-recommend", api_key="")
    asyncio.run(llm.recommend([OwnedBook(title="Dune", author="Frank Herbert")], 3))
    assert json.loads(route.calls[0].request.content)["model"] == "books-recommend"


CUT_OFF = (
    '{"books": [{"title": "Dune", "author": "Frank Herbert", "genres": ["Science Fiction"], "confidence": 0.9},'
    ' {"title": "Emma", "author": "Jane Austen", "genres": ["Classic"], "confidence": 0.8},'
    ' {"title": "The Hou'
)


def test_salvage_keeps_complete_items_from_a_cut_off_reply():
    assert parse_json_reply(CUT_OFF) == {}  # the strict parser gives up on it
    got = salvage_json_reply(CUT_OFF)
    assert [b["title"] for b in got["books"]] == ["Dune", "Emma"]
    assert salvage_json_reply("no json here") == {}
    assert salvage_json_reply('{"books": [{"title": "Cut') == {}


@respx.mock
def test_identify_returns_books_read_before_the_reply_was_cut_off():
    respx.post(URL).mock(return_value=httpx.Response(200, json={"choices": [
        {"finish_reason": "length", "message": {"role": "assistant", "content": CUT_OFF}}]}))
    llm = OpenAICompatLLM(base_url="http://gateway.local:8080/v1", model="m", api_key="")
    books, notes = asyncio.run(llm.identify([(b"\xff\xd8", "image/jpeg")]))
    assert [b.title for b in books] == ["Dune", "Emma"]
    assert "cut off" in notes


LINES = """Here are the books:
title | author | genre
1. Dune | Frank Herbert | Science Fiction
- **The Housemaid** | Freida McFadden | Thriller
Emma | | Classic
dune | Frank Herbert | Science Fiction
The Housemaid | Freida McFadden | Thriller
Neuromancer | William Gibs"""


def test_parse_book_lines_skips_prose_header_bullets_and_repeats():
    got = parse_book_lines(LINES, truncated=False)
    assert [(b["title"], b["author"], b["genres"]) for b in got] == [
        ("Dune", "Frank Herbert", ["Science Fiction"]),
        ("The Housemaid", "Freida McFadden", ["Thriller"]),
        ("Emma", "Unknown", ["Classic"]),
        ("Neuromancer", "William Gibs", []),
    ]
    assert parse_book_lines("NONE") == []


def test_parse_book_lines_drops_the_half_written_last_line_when_cut_off():
    got = parse_book_lines(LINES, truncated=True)
    assert [b["title"] for b in got] == ["Dune", "The Housemaid", "Emma"]


@respx.mock
def test_identify_uses_the_short_line_format_and_reads_it():
    route = respx.post(URL).mock(return_value=reply("Dune | Frank Herbert | Science Fiction\nEmma | Jane Austen | Classic"))
    llm = OpenAICompatLLM(base_url="http://gateway.local:8080/v1", model="m", api_key="")
    books, notes = asyncio.run(llm.identify([(b"\xff\xd8", "image/jpeg")]))
    prompt = json.loads(route.calls[0].request.content)["messages"][0]["content"][1]["text"]
    assert "title | author | genre" in prompt and "confidence" not in prompt
    assert [(b.title, b.author, b.genres) for b in books] == [
        ("Dune", "Frank Herbert", ["Science Fiction"]), ("Emma", "Jane Austen", ["Classic"])]
    assert notes is None


@respx.mock
def test_identify_keeps_complete_lines_and_adds_a_note_when_cut_off():
    respx.post(URL).mock(return_value=httpx.Response(200, json={"choices": [{
        "finish_reason": "length",
        "message": {"role": "assistant", "content": "Dune | Frank Herbert | SF\nEmma | Jane Aus"}}]}))
    llm = OpenAICompatLLM(base_url="http://gateway.local:8080/v1", model="m", api_key="")
    books, notes = asyncio.run(llm.identify([(b"\xff\xd8", "image/jpeg")]))
    assert [b.title for b in books] == ["Dune"] and "cut off" in notes


@respx.mock
def test_identify_none_reply_is_an_empty_shelf_not_an_error():
    respx.post(URL).mock(return_value=reply("NONE"))
    llm = OpenAICompatLLM(base_url="http://gateway.local:8080/v1", model="m", api_key="")
    assert asyncio.run(llm.identify([(b"\xff\xd8", "image/jpeg")])) == ([], None)
