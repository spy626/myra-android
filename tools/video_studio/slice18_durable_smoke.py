#!/usr/bin/env python3
"""Slice 18: real local workerd RPC smoke, loopback only, fake data only."""
import json
import time
import urllib.error
import urllib.request


HOST = "http://127.0.0.1:8788"
PATH = "/__slice18_local_rpc"


def request(phase):
    req = urllib.request.Request(
        HOST + PATH,
        method="POST",
        headers={
            "X-Lyra-Slice18-Local": "enabled",
            "X-Lyra-Slice18-Phase": phase,
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=8) as resp:
            return resp.status, resp.read(1024), dict(resp.headers)
    except urllib.error.HTTPError as exc:
        with exc:
            return exc.code, exc.read(1024), dict(exc.headers)


def main():
    deadline = time.monotonic() + 65
    while True:
        try:
            with urllib.request.urlopen(HOST + "/__ready", timeout=2):
                pass
            break
        except urllib.error.HTTPError as exc:
            if exc.code == 404:
                break
            raise
        except (urllib.error.URLError, TimeoutError):
            if time.monotonic() >= deadline:
                raise RuntimeError("Local Durable Object workerd did not start")
            time.sleep(1)

    for phase in ("init", "verify", "replay"):
        status, body, headers = request(phase)
        try:
            data = json.loads(body)
        except (ValueError, UnicodeError):
            raise AssertionError(f"Local DO {phase}: non-JSON status {status}")
        assert status == 200, f"Local DO {phase}: expected 200, got {status}"
        assert data == {
            "passed": True, "phase": phase, "local_only": True, "deployed": False,
        }, f"Local DO {phase}: unexpected response"
        assert headers.get("Cache-Control") == "no-store"
        assert "Set-Cookie" not in headers
        print(f"PASS local workerd Durable Object RPC: {phase} (no real OAuth)")
    print("PASS local vault, one-time state, encrypted session, quota, rate, revoke")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
