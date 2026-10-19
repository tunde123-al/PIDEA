import json
import socket
import threading
from unittest.mock import patch

import pytest
import requests

import pidea_data.rest_adapter as rest_adapter
from pidea_data.rest_adapter import (
    MAX_PAGE_BYTES,
    PermanentSourceError,
    TransientSourceError,
    _PinnedIPAdapter,
    _safe_endpoint,
    read_records,
)


def public_resolver(host, port, type):
    return [(socket.AF_INET, type, 6, "", ("8.8.8.8", port))]


class JsonResponse:
    def __init__(self, payload, status=200, content_type="application/json"):
        self.status_code = status
        self.headers = {"Content-Type": content_type}
        self.body = json.dumps(payload).encode()

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False

    def iter_content(self, chunk_size):
        yield self.body


class RawResponse(JsonResponse):
    def __init__(self, body):
        super().__init__([])
        self.body = body


def test_pages_and_bearer_token_are_applied_without_logging_secret():
    calls = []
    pages = [
        {"records": [{"customer_id": "C1"}], "hasMore": True},
        {"records": [{"customer_id": "C2"}], "hasMore": False},
    ]

    def request(url, **kwargs):
        calls.append((url, kwargs))
        return JsonResponse(pages[len(calls) - 1])

    records = read_records(
        {"baseUrl": "https://api.example.test/customers", "pageSize": 1},
        "PIDEA_TEST_TOKEN",
        environment={
            "PIDEA_HTTP_API_ALLOWED_HOSTS": "api.example.test",
            "PIDEA_TEST_TOKEN": "test-secret",
        },
        request=request,
        resolver=public_resolver,
    )
    assert records == [("page 1 record 0", {"customer_id": "C1"}),
                       ("page 2 record 0", {"customer_id": "C2"})]
    assert len(calls) == 2
    assert "page=1" in calls[0][0] and "size=1" in calls[0][0]
    assert calls[0][1]["headers"]["Authorization"] == "Bearer test-secret"
    assert calls[0][1]["allow_redirects"] is False
    assert calls[0][1]["timeout"] == (5, 15)


@pytest.mark.parametrize("endpoint", [
    "http://api.example.test/data",
    "https://user:password@api.example.test/data",
    "https://api.example.test:8443/data",
])
def test_rejects_unsafe_endpoint_shapes(endpoint):
    with pytest.raises(PermanentSourceError):
        read_records({"baseUrl": endpoint}, None,
                     environment={"PIDEA_HTTP_API_ALLOWED_HOSTS": "api.example.test"},
                     resolver=public_resolver)


def test_rejects_unapproved_or_private_hosts_before_request():
    requests_made = []
    with pytest.raises(PermanentSourceError, match="allow-list"):
        read_records(
            {"baseUrl": "https://outside.example/data"},
            "PIDEA_TEST_TOKEN",
            environment={
                "PIDEA_HTTP_API_ALLOWED_HOSTS": "api.example.test",
                "PIDEA_TEST_TOKEN": "fixture-token",
            },
            request=lambda url, **kwargs: requests_made.append(url),
            resolver=public_resolver,
        )
    assert requests_made == []
    with pytest.raises(PermanentSourceError, match="non-public"):
        read_records({"baseUrl": "https://api.example.test/data"}, None,
                     environment={"PIDEA_HTTP_API_ALLOWED_HOSTS": "api.example.test"},
                     resolver=lambda *args, **kwargs: [
                         (socket.AF_INET, socket.SOCK_STREAM, 6, "", ("127.0.0.1", 443))
                     ])


def test_retries_are_left_to_worker_for_server_errors_and_timeouts():
    with pytest.raises(TransientSourceError, match="status 503"):
        read_records({"baseUrl": "https://api.example.test/data"}, None,
                     environment={"PIDEA_HTTP_API_ALLOWED_HOSTS": "api.example.test"},
                     request=lambda *args, **kwargs: JsonResponse({}, status=503),
                     resolver=public_resolver)

    import requests

    def timeout(*args, **kwargs):
        raise requests.Timeout()

    with pytest.raises(TransientSourceError, match="timed out"):
        read_records({"baseUrl": "https://api.example.test/data"}, None,
                     environment={"PIDEA_HTTP_API_ALLOWED_HOSTS": "api.example.test"},
                     request=timeout, resolver=public_resolver)

    class InterruptedResponse(JsonResponse):
        def iter_content(self, chunk_size):
            import requests

            raise requests.exceptions.ChunkedEncodingError("untrusted response diagnostic")

    with pytest.raises(TransientSourceError, match="response stream was interrupted") as exc:
        read_records({"baseUrl": "https://api.example.test/data"}, None,
                     environment={"PIDEA_HTTP_API_ALLOWED_HOSTS": "api.example.test"},
                     request=lambda *args, **kwargs: InterruptedResponse([]),
                     resolver=public_resolver)
    assert "untrusted response diagnostic" not in str(exc.value)


def test_missing_token_bad_json_and_page_limit_fail_safely():
    config = {"baseUrl": "https://api.example.test/data"}
    environment = {"PIDEA_HTTP_API_ALLOWED_HOSTS": "api.example.test"}
    with pytest.raises(PermanentSourceError, match="credential is not configured"):
        read_records(config, "MISSING_TOKEN", environment=environment, resolver=public_resolver)

    with pytest.raises(PermanentSourceError, match="valid UTF-8 JSON"):
        read_records(config, None, environment=environment,
                     request=lambda *args, **kwargs: RawResponse(b"not json"),
                     resolver=public_resolver)


def test_refuses_pagination_beyond_configured_bound():
    with pytest.raises(PermanentSourceError, match="maximum page count"):
        read_records(
            {"baseUrl": "https://api.example.test/data", "pageSize": 1, "maxPages": 1},
            None,
            environment={"PIDEA_HTTP_API_ALLOWED_HOSTS": "api.example.test"},
            request=lambda *args, **kwargs: JsonResponse(
                {"records": [{"customer_id": "C1"}], "hasMore": True}
            ),
            resolver=public_resolver,
        )


@pytest.mark.parametrize("address", [
    "8.8.8.8",
    "2606:4700:4700::1111",
    "::ffff:8.8.8.8",
])
def test_accepts_global_ipv4_ipv6_and_mapped_public_addresses(address):
    result = _safe_endpoint(
        "https://api.example.test/data",
        {"api.example.test"},
        resolver=lambda host, port, type: [
            (socket.AF_INET6 if ":" in address else socket.AF_INET,
             type, 6, "", (address, port))
        ],
    )
    assert result[-1] == ["8.8.8.8" if address == "::ffff:8.8.8.8" else address]


@pytest.mark.parametrize("address", [
    "127.0.0.1",
    "10.0.0.1",
    "172.16.0.1",
    "192.168.1.1",
    "169.254.169.254",
    "224.0.0.1",
    "192.0.2.1",
    "198.18.0.1",
    "100.64.0.1",
    "0.0.0.0",
    "255.255.255.255",
    "::1",
    "::",
    "fc00::1",
    "fe80::1",
    "ff02::1",
    "2001:db8::1",
    "::ffff:127.0.0.1",
])
def test_rejects_prohibited_ipv4_ipv6_and_mapped_addresses(address):
    with pytest.raises(PermanentSourceError, match="non-public"):
        _safe_endpoint(
            "https://api.example.test/data",
            {"api.example.test"},
            resolver=lambda host, port, type: [
                (socket.AF_INET6 if ":" in address else socket.AF_INET,
                 type, 6, "", (address, port))
            ],
        )


def test_rejects_mixed_public_and_prohibited_dns_answers():
    with pytest.raises(PermanentSourceError, match="non-public"):
        _safe_endpoint(
            "https://api.example.test/data",
            {"api.example.test"},
            resolver=lambda host, port, type: [
                (socket.AF_INET, type, 6, "", ("8.8.8.8", port)),
                (socket.AF_INET, type, 6, "", ("10.0.0.1", port)),
            ],
        )


def test_pins_the_address_returned_by_the_final_dns_validation():
    current_address = ["8.8.8.8"]
    resolver_calls = []

    def changing_resolver(host, port, type):
        address = current_address[0]
        resolver_calls.append(address)
        return [(socket.AF_INET, type, 6, "", (address, port))]

    connected_addresses = []

    def request(session, url, **kwargs):
        assert session.trust_env is False
        current_address[0] = "127.0.0.1"
        prepared = requests.Request("GET", url, headers=kwargs["headers"]).prepare()
        adapter = session.get_adapter(url)
        adapter.add_headers(prepared)
        with patch.object(adapter.poolmanager, "connection_from_host", return_value=object()) as connect:
            adapter.get_connection_with_tls_context(
                prepared, verify=kwargs["verify"], proxies={}
            )
        connected_addresses.append(connect.call_args.args[0])
        return JsonResponse([])

    with patch.object(requests.Session, "get", request):
        read_records(
            {"baseUrl": "https://api.example.test/data"},
            None,
            environment={"PIDEA_HTTP_API_ALLOWED_HOSTS": "api.example.test"},
            resolver=changing_resolver,
        )
    assert resolver_calls == ["8.8.8.8"]
    assert connected_addresses == ["8.8.8.8"]


def test_default_resolver_uses_bounded_dns_lookup_before_request():
    requests_made = []
    with patch.object(
        rest_adapter,
        "_resolve_host",
        side_effect=TimeoutError("DNS lookup timed out"),
    ) as resolve:
        with pytest.raises(TransientSourceError, match="could not be resolved"):
            read_records(
                {"baseUrl": "https://api.example.test/data"},
                None,
                environment={"PIDEA_HTTP_API_ALLOWED_HOSTS": "api.example.test"},
                request=lambda *args, **kwargs: requests_made.append(args),
            )
    resolve.assert_called_once()
    assert requests_made == []


def test_dns_lookup_timeout_is_bounded(monkeypatch):
    started = threading.Event()
    release = threading.Event()

    def delayed_lookup(*args, **kwargs):
        started.set()
        release.wait()
        return []

    monkeypatch.setattr(rest_adapter.socket, "getaddrinfo", delayed_lookup)
    monkeypatch.setattr(rest_adapter, "_DNS_LOOKUP_TIMEOUT_SECONDS", 0.01)
    try:
        with pytest.raises(TimeoutError, match="DNS lookup timed out"):
            rest_adapter._resolve_host("api.example.test", 443, type=socket.SOCK_STREAM)
        assert started.is_set()
    finally:
        release.set()


def test_rejects_streamed_response_that_exceeds_page_limit():
    response = JsonResponse([])

    def oversized_body(chunk_size):
        for _ in range(MAX_PAGE_BYTES // chunk_size):
            yield b"x" * chunk_size
        yield b"x"

    response.iter_content = oversized_body
    with pytest.raises(PermanentSourceError, match="5 MiB"):
        read_records(
            {"baseUrl": "https://api.example.test/data"},
            None,
            environment={"PIDEA_HTTP_API_ALLOWED_HOSTS": "api.example.test"},
            request=lambda *args, **kwargs: response,
            resolver=public_resolver,
        )


def test_rejects_tls_certificate_verification_failure():
    def tls_failure(*args, **kwargs):
        raise requests.exceptions.SSLError("certificate hostname mismatch")

    with pytest.raises(PermanentSourceError, match="secure connection could not be verified"):
        read_records(
            {"baseUrl": "https://api.example.test/data"},
            None,
            environment={"PIDEA_HTTP_API_ALLOWED_HOSTS": "api.example.test"},
            request=tls_failure,
            resolver=public_resolver,
        )


def test_tls_pool_uses_original_hostname_for_sni_and_certificate_verification():
    adapter = _PinnedIPAdapter("api.example.test", "8.8.8.8")
    prepared = requests.Request("GET", "https://api.example.test/data").prepare()
    with patch.object(adapter.poolmanager, "connection_from_host", return_value=object()) as connect:
        adapter.get_connection_with_tls_context(prepared, verify=True)
        args, kwargs = connect.call_args
        assert args[0] == "8.8.8.8"
        assert kwargs["pool_kwargs"]["assert_hostname"] == "api.example.test"
        assert kwargs["pool_kwargs"]["server_hostname"] == "api.example.test"

    adapter.add_headers(prepared)
    assert prepared.headers["Host"] == "api.example.test"
    adapter.close()


def test_redirect_to_private_address_is_rejected_without_following_it():
    calls = []

    def request(url, **kwargs):
        calls.append(url)
        response = JsonResponse({}, status=302)
        response.headers["Location"] = "http://127.0.0.1/admin"
        return response

    with pytest.raises(PermanentSourceError, match="status 302"):
        read_records(
            {"baseUrl": "https://api.example.test/data"},
            "PIDEA_TEST_TOKEN",
            environment={
                "PIDEA_HTTP_API_ALLOWED_HOSTS": "api.example.test",
                "PIDEA_TEST_TOKEN": "test-secret",
            },
            request=request,
            resolver=public_resolver,
        )
    assert len(calls) == 1


def test_rejects_oversized_content_length_without_reading_body():
    response = JsonResponse([])
    response.headers["Content-Length"] = str(MAX_PAGE_BYTES + 1)
    response.iter_content = lambda chunk_size: pytest.fail("oversized body must not be read")

    with pytest.raises(PermanentSourceError, match="5 MiB"):
        read_records(
            {"baseUrl": "https://api.example.test/data"},
            None,
            environment={"PIDEA_HTTP_API_ALLOWED_HOSTS": "api.example.test"},
            request=lambda *args, **kwargs: response,
            resolver=public_resolver,
        )


def test_dns_failure_and_timeout_fail_closed_before_request():
    requests_made = []
    for resolver in (
        lambda *args, **kwargs: [],
        lambda *args, **kwargs: (_ for _ in ()).throw(TimeoutError()),
    ):
        with pytest.raises(TransientSourceError):
            read_records(
                {"baseUrl": "https://api.example.test/data"},
                None,
                environment={"PIDEA_HTTP_API_ALLOWED_HOSTS": "api.example.test"},
                request=lambda *args, **kwargs: requests_made.append(args),
                resolver=resolver,
            )
    assert requests_made == []
