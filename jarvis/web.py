import json
import urllib.error
import urllib.parse
import urllib.request
from html.parser import HTMLParser
from typing import Any

USER_AGENT = (
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0 Safari/537.36 Jarvis/0.2"
)


def _fetch(
    url: str,
    data: bytes | None = None,
    headers: dict[str, str] | None = None,
    timeout: float = 25.0,
) -> tuple[bytes, str, int]:
    request = urllib.request.Request(
        url,
        data=data,
        headers={"User-Agent": USER_AGENT, **(headers or {})},
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return (
                response.read(),
                response.headers.get("content-type", ""),
                response.status,
            )
    except urllib.error.HTTPError as exc:
        body = exc.read()
        return body, exc.headers.get("content-type", ""), exc.code
    except urllib.error.URLError as exc:
        raise RuntimeError(f"cannot reach {url}: {exc.reason}") from exc
    except TimeoutError as exc:
        raise RuntimeError(f"request to {url} timed out") from exc


def _clean_url(url: str) -> str:
    if url.startswith("//"):
        url = "https:" + url
    parsed = urllib.parse.urlparse(url)
    if "duckduckgo.com" in parsed.netloc and parsed.path.startswith("/l/"):
        query = urllib.parse.parse_qs(parsed.query)
        if "uddg" in query:
            return query["uddg"][0]
    return url


class _SearchParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.results: list[dict[str, str]] = []
        self._mode: str | None = None
        self._buffer: list[str] = []
        self._href = ""

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        attributes = dict(attrs)
        classes = attributes.get("class") or ""
        if tag == "a" and "result__a" in classes:
            self._mode = "title"
            self._buffer = []
            self._href = attributes.get("href") or ""
        elif "result__snippet" in classes:
            self._mode = "snippet"
            self._buffer = []

    def handle_data(self, data: str) -> None:
        if self._mode:
            self._buffer.append(data)

    def handle_endtag(self, tag: str) -> None:
        if self._mode == "title" and tag == "a":
            title = " ".join("".join(self._buffer).split())
            self.results.append(
                {"title": title, "url": _clean_url(self._href), "snippet": ""}
            )
            self._mode = None
        elif self._mode == "snippet" and tag in ("a", "div", "span"):
            if self.results:
                self.results[-1]["snippet"] = " ".join("".join(self._buffer).split())
            self._mode = None


class _TextParser(HTMLParser):
    SKIP = {"script", "style", "noscript", "svg", "head"}
    BREAK = {
        "p", "br", "div", "li", "tr", "h1", "h2", "h3", "h4",
        "h5", "h6", "section", "article", "pre", "table",
    }

    def __init__(self) -> None:
        super().__init__()
        self.parts: list[str] = []
        self._skip = 0

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag in self.SKIP:
            self._skip += 1
        elif tag in self.BREAK and not self._skip:
            self.parts.append("\n")

    def handle_endtag(self, tag: str) -> None:
        if tag in self.SKIP and self._skip:
            self._skip -= 1
        elif tag in self.BREAK and not self._skip:
            self.parts.append("\n")

    def handle_data(self, data: str) -> None:
        if not self._skip:
            self.parts.append(data)


def _html_to_text(html: str) -> str:
    parser = _TextParser()
    parser.feed(html)
    lines = [" ".join(line.split()) for line in "".join(parser.parts).splitlines()]
    return "\n".join(line for line in lines if line)


def web_search(query: str, max_results: int = 5) -> str:
    max_results = max(1, min(10, int(max_results)))
    url = "https://html.duckduckgo.com/html/?q=" + urllib.parse.quote_plus(query)
    try:
        raw, _, _ = _fetch(url)
        parser = _SearchParser()
        parser.feed(raw.decode("utf-8", errors="replace"))
        results = [r for r in parser.results if r["title"]][:max_results]
    except Exception:
        results = []

    if not results:
        return _instant_answer(query)

    lines = []
    for index, result in enumerate(results, 1):
        lines.append(f"{index}. {result['title']}")
        lines.append(f"   {result['url']}")
        if result["snippet"]:
            lines.append(f"   {result['snippet']}")
    return "\n".join(lines)


def _instant_answer(query: str) -> str:
    try:
        url = (
            "https://api.duckduckgo.com/?q="
            + urllib.parse.quote_plus(query)
            + "&format=json&no_html=1&skip_disambig=1"
        )
        raw, _, _ = _fetch(url)
        data = json.loads(raw.decode("utf-8", errors="replace"))
        if data.get("AbstractText"):
            source = data.get("AbstractURL") or ""
            return f"{data['AbstractText']}\n{source}".strip()
        topics = []
        for item in data.get("RelatedTopics", []):
            if "Text" in item:
                topics.append(item["Text"])
            for nested in item.get("Topics", []) if "Topics" in item else []:
                if "Text" in nested:
                    topics.append(nested["Text"])
        if topics:
            return "\n".join(f"- {topic}" for topic in topics[:5])
    except Exception:
        pass
    return f"No web results found for {query!r}."


def fetch_url(url: str, max_chars: int = 8000) -> str:
    if not url.startswith(("http://", "https://")):
        url = "https://" + url
    raw, content_type, status = _fetch(url)
    text = raw.decode("utf-8", errors="replace")
    if "html" in content_type.lower() or text.lstrip().startswith("<"):
        text = _html_to_text(text)
    text = text.strip()
    if len(text) > max_chars:
        text = text[:max_chars] + "\n[truncated]"
    return f"HTTP {status} | {content_type}\n\n{text}"


def http_request(
    method: str = "GET",
    url: str = "",
    headers: str = "",
    body: str = "",
    timeout: int = 30,
) -> str:
    if not url:
        return "A URL is required."
    if not url.startswith(("http://", "https://")):
        url = "https://" + url
    request_headers: dict[str, str] = {}
    if headers.strip():
        try:
            parsed = json.loads(headers)
        except json.JSONDecodeError as exc:
            return f"Headers must be valid JSON: {exc}"
        if not isinstance(parsed, dict):
            return "Headers must be a JSON object."
        request_headers = {str(key): str(value) for key, value in parsed.items()}

    method = method.upper()
    payload = body.encode() if body and method not in ("GET", "HEAD") else None
    try:
        raw, content_type, status = _fetch(
            url,
            data=payload,
            headers=request_headers,
            timeout=float(timeout),
        )
    except Exception as exc:
        return f"Request failed: {exc}"
    text = raw.decode("utf-8", errors="replace")
    if "html" in content_type.lower():
        text = _html_to_text(text)
    if len(text) > 5000:
        text = text[:5000] + "\n[truncated]"
    return f"HTTP {status} | {content_type}\n\n{text}"
