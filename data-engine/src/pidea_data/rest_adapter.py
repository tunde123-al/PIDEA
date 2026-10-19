"""Bounded, read-only HTTPS adapter for JSON HTTP_API sources."""

from __future__ import annotations

import ipaddress
import json
import os
import re
import socket
import threading
from collections.abc import Callable
from decimal import Decimal
from typing import Any
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit

import requests
from requests.adapters import HTTPAdapter

MAX_PAGE_BYTES = 5 * 1024 * 1024
MAX_RECORDS = 100_000
_PARAMETER = re.compile(r"^[A-Za-z][A-Za-z0-9_-]{0,49}$")
_DNS_LOOKUP_TIMEOUT_SECONDS = 5
_DNS_LOOKUP_SLOTS = threading.BoundedSemaphore(8)


class PermanentSourceError(ValueError):
    """The source configuration or response cannot be fixed by retrying the same request."""


class TransientSourceError(Exception):
    """The source returned a transient error or did not respond in time."""


def _resolve_host(host: str, port: int, *, type: int) -> list[tuple[Any, ...]]:
    if not _DNS_LOOKUP_SLOTS.acquire(blocking=False):
        raise OSError("DNS lookup capacity is exhausted")
    completed = threading.Event()
    answers: list[tuple[Any, ...]] = []
    errors: list[OSError] = []

    def lookup() -> None:
        try:
            answers.extend(socket.getaddrinfo(host, port, type=type))
        except OSError as error:
            errors.append(error)
        finally:
            completed.set()
            _DNS_LOOKUP_SLOTS.release()

    resolver_thread = threading.Thread(target=lookup, name="pidea-http-dns", daemon=True)
    try:
        resolver_thread.start()
    except RuntimeError:
        _DNS_LOOKUP_SLOTS.release()
        raise OSError("DNS lookup could not start") from None
    if not completed.wait(_DNS_LOOKUP_TIMEOUT_SECONDS):
        raise TimeoutError("DNS lookup timed out")
    if errors:
        raise errors[0]
    return answers


class _PinnedIPAdapter(HTTPAdapter):
    def __init__(self, hostname: str, address: str, port: int = 443) -> None:
        super().__init__(max_retries=0)
        self.hostname = hostname
        self.address = address
        self.port = port

    def get_connection_with_tls_context(self, request, verify, proxies=None, cert=None):
        parsed = urlsplit(request.url)
        if (parsed.scheme != "https" or parsed.hostname != self.hostname
                or parsed.port not in (None, self.port)):
            raise requests.exceptions.InvalidURL("HTTP_API connection target changed")
        _, pool_kwargs = self.build_connection_pool_key_attributes(request, verify, cert)
        pool_kwargs["assert_hostname"] = self.hostname
        pool_kwargs["server_hostname"] = self.hostname
        return self.poolmanager.connection_from_host(
            self.address, port=self.port, scheme="https", pool_kwargs=pool_kwargs
        )

    def add_headers(self, request, **kwargs):
        request.headers["Host"] = self.hostname


def _canonical_hostname(hostname: str) -> str:
    hostname = hostname.rstrip(".").lower()
    try:
        address = ipaddress.ip_address(hostname)
    except ValueError:
        try:
            hostname = hostname.encode("idna").decode("ascii")
        except UnicodeError:
            raise PermanentSourceError("HTTP_API hostname is invalid") from None
        if (len(hostname) > 253
                or any(not label or len(label) > 63
                       or re.fullmatch(r"[a-z0-9](?:[a-z0-9-]*[a-z0-9])?", label) is None
                       for label in hostname.split("."))):
            raise PermanentSourceError("HTTP_API hostname is invalid") from None
        return hostname
    return address.compressed.lower()


def _safe_endpoint(
    endpoint: str,
    allowed_hosts: set[str],
    resolver: Callable[..., list[tuple[Any, ...]]] = _resolve_host,
) -> tuple[str, str, str, str, list[str]]:
    try:
        parsed = urlsplit(endpoint)
        raw_host = parsed.hostname
        port = parsed.port
    except ValueError:
        raise PermanentSourceError("HTTP_API endpoint is invalid") from None
    if (parsed.scheme != "https" or not raw_host or parsed.username or parsed.password
            or parsed.fragment or port not in (None, 443) or "\\" in endpoint
            or any(ord(character) < 32 for character in endpoint)):
        raise PermanentSourceError("HTTP_API endpoint must be an HTTPS URL on port 443 without credentials")
    host = _canonical_hostname(raw_host)
    normalized_allow_list = {_canonical_hostname(value) for value in allowed_hosts}
    if host not in normalized_allow_list:
        raise PermanentSourceError("HTTP_API host is not in the configured outbound allow-list")
    try:
        resolved = resolver(host, 443, type=socket.SOCK_STREAM)
        addresses = set()
        for item in resolved:
            if len(item) < 5 or not item[4]:
                raise ValueError("malformed DNS answer")
            addresses.add(ipaddress.ip_address(item[4][0]))
    except (OSError, TypeError, ValueError, IndexError):
        raise TransientSourceError("HTTP_API host could not be resolved") from None
    if not addresses:
        raise TransientSourceError("HTTP_API host returned no usable addresses")
    normalized_addresses: set[ipaddress.IPv4Address | ipaddress.IPv6Address] = set()
    for address in addresses:
        if isinstance(address, ipaddress.IPv6Address) and address.ipv4_mapped is not None:
            address = address.ipv4_mapped
        normalized_addresses.add(address)
    prohibited = any(
        not address.is_global
        or address.is_private
        or address.is_loopback
        or address.is_link_local
        or address.is_multicast
        or address.is_reserved
        or address.is_unspecified
        for address in normalized_addresses
    )
    if prohibited:
        raise PermanentSourceError("HTTP_API host resolves to a non-public network address")
    return parsed.scheme, parsed.netloc, parsed.path or "/", host, sorted(
        address.compressed for address in normalized_addresses
    )


def _page_url(endpoint: str, page: int, page_size: int, page_param: str, size_param: str) -> str:
    parts = urlsplit(endpoint)
    query = dict(parse_qsl(parts.query, keep_blank_values=True))
    query[page_param] = str(page)
    query[size_param] = str(page_size)
    return urlunsplit((parts.scheme, parts.netloc, parts.path or "/", urlencode(query), ""))


def _read_response(response: requests.Response) -> Any:
    if response.status_code == 429 or response.status_code >= 500:
        raise TransientSourceError(f"HTTP_API returned transient status {response.status_code}")
    if response.status_code < 200 or response.status_code >= 300:
        raise PermanentSourceError(f"HTTP_API returned status {response.status_code}")
    content_length = response.headers.get("Content-Length")
    if content_length is not None:
        try:
            declared_size = int(content_length)
        except ValueError:
            raise PermanentSourceError("HTTP_API response content length is invalid") from None
        if declared_size < 0:
            raise PermanentSourceError("HTTP_API response content length is invalid")
        if declared_size > MAX_PAGE_BYTES:
            raise PermanentSourceError("HTTP_API response exceeds the 5 MiB page size limit")
    content_type = response.headers.get("Content-Type", "").split(";", maxsplit=1)[0].strip().lower()
    if content_type != "application/json" and not content_type.endswith("+json"):
        raise PermanentSourceError("HTTP_API response content type must be JSON")
    chunks: list[bytes] = []
    size = 0
    for chunk in response.iter_content(chunk_size=64 * 1024):
        size += len(chunk)
        if size > MAX_PAGE_BYTES:
            raise PermanentSourceError("HTTP_API response exceeds the 5 MiB page size limit")
        chunks.append(chunk)
    try:
        return json.loads(b"".join(chunks).decode("utf-8-sig"), parse_float=Decimal)
    except (UnicodeDecodeError, json.JSONDecodeError):
        raise PermanentSourceError("HTTP_API response is not valid UTF-8 JSON") from None


def _fetch_payload(
    url: str,
    hostname: str,
    pinned_ip: str,
    headers: dict[str, str],
    timeout: int,
    request: Callable[..., requests.Response] | None,
) -> Any:
    session: requests.Session | None = None
    try:
        request_options = {
            "headers": headers,
            "timeout": (min(timeout, 5), timeout),
            "allow_redirects": False,
            "stream": True,
        }
        if request is not None:
            response = request(url, pinned_ip=pinned_ip, **request_options)
        else:
            session = requests.Session()
            session.trust_env = False
            session.mount("https://", _PinnedIPAdapter(hostname, pinned_ip))
            response = session.get(url, verify=True, **request_options)
        with response:
            return _read_response(response)
    except requests.exceptions.InvalidHeader:
        raise PermanentSourceError("HTTP_API request headers are invalid") from None
    except (requests.exceptions.InvalidURL, requests.exceptions.SSLError):
        raise PermanentSourceError("HTTP_API secure connection could not be verified") from None
    except requests.exceptions.ChunkedEncodingError:
        raise TransientSourceError("HTTP_API response stream was interrupted") from None
    except requests.exceptions.ContentDecodingError:
        raise PermanentSourceError("HTTP_API response content encoding is invalid") from None
    except requests.Timeout:
        raise TransientSourceError("HTTP_API request or response timed out") from None
    except requests.ConnectionError:
        raise TransientSourceError("HTTP_API request or response connection failed") from None
    finally:
        if session is not None:
            session.close()


def read_records(
    config: dict[str, Any],
    secret_ref: str | None,
    *,
    environment: dict[str, str] | None = None,
    request: Callable[..., requests.Response] | None = None,
    resolver: Callable[..., list[tuple[Any, ...]]] | None = None,
) -> list[tuple[str, Any]]:
    endpoint = config.get("baseUrl")
    if not isinstance(endpoint, str):
        raise PermanentSourceError("HTTP_API source is missing baseUrl")
    environment = os.environ if environment is None else environment
    if resolver is None:
        resolver = _resolve_host
    allowed_hosts = {
        value.strip().lower().rstrip(".")
        for value in environment.get("PIDEA_HTTP_API_ALLOWED_HOSTS", "").split(",")
        if value.strip()
    }
    page_size = config.get("pageSize", 100)
    max_pages = config.get("maxPages", 100)
    timeout = config.get("timeoutSeconds", 15)
    page_param = config.get("pageParam", "page")
    size_param = config.get("pageSizeParam", "size")
    if (isinstance(page_size, bool) or not isinstance(page_size, int) or not 1 <= page_size <= 1000
            or isinstance(max_pages, bool) or not isinstance(max_pages, int) or not 1 <= max_pages <= 1000
            or isinstance(timeout, bool) or not isinstance(timeout, int) or not 1 <= timeout <= 60
            or not isinstance(page_param, str) or not _PARAMETER.fullmatch(page_param)
            or not isinstance(size_param, str) or not _PARAMETER.fullmatch(size_param)
            or page_param == size_param):
        raise PermanentSourceError("HTTP_API pagination or timeout configuration is invalid")

    headers: dict[str, str] = {"Accept": "application/json"}
    if secret_ref:
        if re.fullmatch(r"[A-Z][A-Z0-9_]{0,99}", secret_ref) is None:
            raise PermanentSourceError("HTTP_API credential reference is invalid")
        token = environment.get(secret_ref)
        if not token:
            raise PermanentSourceError("HTTP_API credential is not configured in the worker environment")
        headers["Authorization"] = f"Bearer {token}"

    records: list[tuple[str, Any]] = []
    for page in range(1, max_pages + 1):
        url = _page_url(endpoint, page, page_size, page_param, size_param)
        _, _, _, page_hostname, addresses = _safe_endpoint(url, allowed_hosts, resolver)
        pinned_ip = addresses[0]
        payload = _fetch_payload(
            url, page_hostname, pinned_ip, headers, timeout, request
        )

        has_more: bool | None = None
        if isinstance(payload, list):
            page_records = payload
        elif isinstance(payload, dict) and isinstance(payload.get("records"), list):
            page_records = payload["records"]
            raw_more = payload.get("hasMore")
            if raw_more is not None and not isinstance(raw_more, bool):
                raise PermanentSourceError("HTTP_API hasMore must be a boolean")
            has_more = raw_more
        else:
            raise PermanentSourceError("HTTP_API response must be an array or an object with a records array")

        if len(records) + len(page_records) > MAX_RECORDS:
            raise PermanentSourceError("HTTP_API response exceeds the 100000 record limit")
        records.extend((f"page {page} record {index}", item) for index, item in enumerate(page_records))
        if has_more is False or not page_records or (has_more is None and len(page_records) < page_size):
            return records

    raise PermanentSourceError("HTTP_API exceeded the configured maximum page count")
