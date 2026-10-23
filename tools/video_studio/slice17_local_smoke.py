#!/usr/bin/env python3
"""No-credential loopback smoke test. Never sends OAuth data to any external host."""
import argparse
import json
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

CASES = [
    ("GET", "/oauth/github/start"),
    ("GET", "/oauth/github/callback?code=unused&state=unused"),
    ("POST", "/oauth/github/logout"),
    ("GET", "/github/dispatch"),
    ("GET", "/health"),
]


def request_once(url: str, method: str):
    try:
        with urlopen(Request(url, method=method), timeout=3) as response:
            return response.status, dict(response.headers.items()), response.read(2048)
    except HTTPError as error:
        with error:
            return error.code, dict(error.headers.items()), error.read(2048)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://127.0.0.1:8787")
    parser.add_argument("--ready-seconds", type=int, default=90)
    args = parser.parse_args()
    if args.base != "http://127.0.0.1:8787" or not 1 <= args.ready_seconds <= 120:
        print("Refusing non-loopback or unbounded smoke run", file=sys.stderr)
        return 2
    deadline = time.monotonic() + args.ready_seconds
    while True:
        try:
            request_once(args.base + CASES[0][1], "GET")
            break
        except (URLError, TimeoutError, ConnectionError):
            if time.monotonic() >= deadline:
                print("Local Worker did not become ready", file=sys.stderr)
                return 1
            time.sleep(1)

    for method, path in CASES:
        code, headers, body = request_once(args.base + path, method)
        try:
            result = json.loads(body)
        except (ValueError, UnicodeError):
            raise AssertionError("Local Worker did not return JSON") from None
        normalized = {str(key).lower(): value for key, value in headers.items()}
        assert code == 503, f"Unsafe status on {method} {path}: {code}"
        assert result == {"error": "video_auth_not_deployed"}, f"Unsafe body on {method} {path}"
        assert normalized.get("cache-control") == "no-store", "Missing no-store"
        assert not normalized.get("set-cookie"), "Unexpected cookie without auth"
        print(f"PASS disabled staging: {method} {path} -> 503")
    print("PASS: local runtime stays disabled; no OAuth, dispatch, cookies, or live deployment")
    return 0


if __name__ == "__main__":
    sys.exit(main())
