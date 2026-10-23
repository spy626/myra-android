#!/usr/bin/env python3
"""Strict *offline* LYRA Video OAuth staging preflight. Never deploys or logs secrets."""
from __future__ import annotations

import argparse
import json
import re
import sys
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
STAGING = Path("connector-broker/wrangler.video-auth.example.toml")
EXPECTED_BINDINGS = {
    "VIDEO_OAUTH_VAULT": "VideoOAuthVault",
    "VIDEO_OWNER_LEDGER": "VideoRenderOwnerDurableObject",
    "VIDEO_AUTH_RATE_GATE": "VideoOAuthRateGate",
}
EXPECTED_MIGRATIONS = [
    ("video-auth-staging-v1", ["VideoOAuthVault", "VideoRenderOwnerDurableObject"]),
    ("video-auth-staging-v2", ["VideoOAuthRateGate"]),
]
SECRET_KEYS = frozenset({
    "VIDEO_GITHUB_CLIENT_SECRET", "VIDEO_SESSION_KEY_HEX",
    "VIDEO_AUTH_RATE_KEY_HEX", "GITHUB_TOKEN", "GH_TOKEN",
    "GITHUB_ACTIONS_TOKEN", "CLOUDFLARE_API_TOKEN",
})
REQUIRED_RUNTIME = (
    "connector-broker/src/video-auth-worker-cloudflare.mjs",
    "connector-broker/src/video-auth-worker.mjs",
    "connector-broker/src/video-oauth-flow.mjs",
    "connector-broker/src/video-oauth-vault.mjs",
    "connector-broker/src/video-auth-rate-limit.mjs",
    "connector-broker/src/video-owner-durable-object.mjs",
)
BLOCKERS = [
    "Owner-approved isolated HTTPS staging domain and fixed callback not configured",
    "GitHub OAuth App credentials and server-only encrypted secrets not provisioned",
    "Cloudflare plan quotas, free-tier billing and operational safeguards not verified",
    "Real Cloudflare Durable Object RPC, alarms, and rate-limit behavior not tested",
    "Staging browser consent/logout flow and physical Android acceptance not tested",
    "GitHub Actions write permission and automatic dispatch deliberately unavailable",
]


def _keys(obj: object) -> list[str]:
    """Recursively find sensitive key names without touching values or logging data."""
    if isinstance(obj, dict):
        return [str(key) for key, value in obj.items() if str(key).upper() in SECRET_KEYS] + [
            found for value in obj.values() for found in _keys(value)
        ]
    if isinstance(obj, list):
        return [found for value in obj for found in _keys(value)]
    return []


def audit_manifest(text: str) -> list[str]:
    """Return only fixed diagnostic codes. No untrusted config values."""
    errors: list[str] = []
    try:
        manifest = tomllib.loads(text)
    except (tomllib.TOMLDecodeError, ValueError):
        return ["INVALID_TOML"]

    if manifest.get("name") != "lyra-video-auth-staging":
        errors.append("UNEXPECTED_STAGING_NAME")
    if manifest.get("main") != "src/video-auth-worker-cloudflare.mjs":
        errors.append("UNSAFE_ENTRYPOINT")
    if manifest.get("workers_dev") is not False:
        errors.append("WORKERS_DEV_NOT_DISABLED")
    if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", str(manifest.get("compatibility_date", ""))):
        errors.append("MISSING_COMPATIBILITY_DATE")

    if any(key in manifest for key in (
        "route", "routes", "triggers", "workers_dev_url", "tail_consumers",
        "build", "unsafe", "assets", "site", "placement", "observability",
    )):
        errors.append("UNREVIEWED_DEPLOYMENT_SURFACE")

    vars_value = manifest.get("vars")
    if vars_value != {"VIDEO_AUTH_ENABLED": "false", "VIDEO_AUTH_DEPLOYMENT_APPROVED": "false"}:
        errors.append("NON_DISABLED_OR_UNREVIEWED_VARIABLES")
    if _keys(manifest):
        errors.append("SECRET_KEY_EMBEDDED_IN_MANIFEST")

    durable = manifest.get("durable_objects")
    bindings = durable.get("bindings") if isinstance(durable, dict) else None
    if not isinstance(bindings, list) or any(not isinstance(b, dict) for b in bindings):
        errors.append("INVALID_DURABLE_BINDINGS")
    else:
        pairs = [(b.get("name"), b.get("class_name")) for b in bindings]
        if len(pairs) != len(EXPECTED_BINDINGS) or set(pairs) != set(EXPECTED_BINDINGS.items()) or any(set(b) != {"name", "class_name"} for b in bindings):
            errors.append("DURABLE_BINDINGS_MISMATCH")

    migrations = manifest.get("migrations")
    if not isinstance(migrations, list) or len(migrations) != len(EXPECTED_MIGRATIONS):
        errors.append("MIGRATION_SEQUENCE_MISMATCH")
    else:
        for actual, (tag, classes) in zip(migrations, EXPECTED_MIGRATIONS):
            if not isinstance(actual, dict) or actual.get("tag") != tag or actual.get("new_sqlite_classes") != classes or set(actual) != {"tag", "new_sqlite_classes"}:
                errors.append("MIGRATION_SEQUENCE_MISMATCH")
                break
    return errors


def audit_runtime(root: Path) -> list[str]:
    errors = []
    for path in REQUIRED_RUNTIME:
        if not (root / path).is_file():
            errors.append("MISSING_STAGING_SOURCE")
    entry = root / "connector-broker/src/video-auth-worker-cloudflare.mjs"
    if entry.is_file():
        source = entry.read_text(encoding="utf-8")
        if ('from "cloudflare:workers"' not in source or
            any(f"class {cls} extends DurableObject" not in source for cls in EXPECTED_BINDINGS.values()) or
            "alarm()" not in source):
            errors.append("RPC_OR_ALARM_WRAPPER_NOT_READY")
    worker = root / "connector-broker/src/video-auth-worker.mjs"
    if worker.is_file():
        source = worker.read_text(encoding="utf-8")
        if ('VIDEO_AUTH_ENABLED!=="true"' not in source or
            'VIDEO_AUTH_DEPLOYMENT_APPROVED!=="true"' not in source or
            '"/oauth/github/logout"' not in source):
            errors.append("DISABLED_STAGING_GUARD_MISSING")
    return errors


def report(root: Path = ROOT) -> dict[str, object]:
    try:
        manifest = (root / STAGING).read_text(encoding="utf-8")
    except (OSError, UnicodeError):
        errors = ["MISSING_STAGING_MANIFEST"]
    else:
        errors = audit_manifest(manifest)
    errors.extend(audit_runtime(root))
    return {
        "slice": 15,
        "mode": "offline_static_preflight_only",
        "contract_passed": not errors,
        "diagnostic_codes": sorted(set(errors)),
        "staging_activation": "BLOCKED",
        "deployed": False,
        "workflow_dispatched": False,
        "required_before_any_staging_activation": BLOCKERS,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Audit disabled staging template; NEVER deploy")
    parser.add_argument("--root", type=Path, default=ROOT)
    args = parser.parse_args()
    result = report(args.root)
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0 if result["contract_passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
