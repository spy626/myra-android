#!/usr/bin/env python3
"""Slice 19: offline, non-deploying audit of an actual Wrangler dry-run bundle.

This is NOT an attestation that Cloudflare was deployed or that real OAuth works.
Only deterministic hash/size metadata is emitted; no secrets or source code.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path

from check_staging_readiness import ROOT, STAGING, report as preflight

MAX_BUNDLE_BYTES = 4 * 1024 * 1024
MIN_BUNDLE_BYTES = 2048
REQUIRED_EXPORTS = ("VideoOAuthVault", "VideoOAuthRateGate", "VideoRenderOwnerDurableObject")
SHA = re.compile(r"^[0-9a-f]{40}$")


class PreflightFailed(Exception):
    """Expected static failure; never include filesystem/source data in errors."""


def build_evidence(root: Path, bundle_dir: Path, commit: str) -> dict[str, object]:
    if not isinstance(commit, str) or not SHA.fullmatch(commit):
        raise PreflightFailed("INVALID_COMMIT_SHA")
    check = preflight(root)
    if check["contract_passed"] is not True or check["staging_activation"] != "BLOCKED":
        raise PreflightFailed("DISABLED_STAGING_PREFLIGHT_FAILED")
    try:
        manifest = (root / STAGING).read_bytes()
    except (OSError, ValueError):
        raise PreflightFailed("STAGING_MANIFEST_UNAVAILABLE") from None
    if not bundle_dir.is_dir():
        raise PreflightFailed("WRANGLER_BUNDLE_MISSING")
    candidates = sorted(p for p in bundle_dir.rglob("*.js") if p.is_file())
    if len(candidates) != 1:
        raise PreflightFailed("EXPECTED_SINGLE_WORKER_BUNDLE")
    bundle_path = candidates[0]
    if bundle_path.is_symlink() or bundle_path.stat().st_size > MAX_BUNDLE_BYTES:
        raise PreflightFailed("BUNDLE_SIZE_OR_SYMLINK_REJECTED")
    try:
        bundle = bundle_path.read_bytes()
    except OSError:
        raise PreflightFailed("WRANGLER_BUNDLE_UNREADABLE") from None
    if not MIN_BUNDLE_BYTES <= len(bundle) <= MAX_BUNDLE_BYTES:
        raise PreflightFailed("WRANGLER_BUNDLE_TOO_SMALL_OR_LARGE")
    if any(name.encode("ascii") not in bundle for name in REQUIRED_EXPORTS):
        raise PreflightFailed("DURABLE_OBJECT_EXPORT_MISSING")
    return {
        "slice": 19,
        "mode": "offline_wrangler_dry_run_only",
        "source_commit": commit,
        "staging_manifest_sha256": hashlib.sha256(manifest).hexdigest(),
        "worker_bundle_sha256": hashlib.sha256(bundle).hexdigest(),
        "worker_bundle_bytes": len(bundle),
        "durable_object_export_names_verified": list(REQUIRED_EXPORTS),
        "staging_flags_disabled": True,
        "static_preflight_passed": True,
        "local_runtime_validated_in_this_job": False,
        "hosted_cloudflare_validated": False,
        "staging_activation": "BLOCKED",
        "deployed": False,
        "workflow_dispatched": False,
        "evidence_is_signed": False,
        "ready_for_live_activation": False,
        "manual_owner_approval_required": True,
    }


def verified_checkout_sha(root: Path) -> str:
    try:
        completed = subprocess.run(
            ["git", "-C", str(root), "rev-parse", "HEAD"],
            check=True, capture_output=True, text=True, timeout=10,
        )
    except (OSError, subprocess.SubprocessError):
        raise PreflightFailed("GIT_COMMIT_UNAVAILABLE") from None
    sha = completed.stdout.strip()
    if not SHA.fullmatch(sha):
        raise PreflightFailed("GIT_COMMIT_INVALID")
    return sha


def main() -> int:
    parser = argparse.ArgumentParser(description="Produce safe, deploy-free bundle evidence")
    parser.add_argument("--bundle-dir", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--commit", required=True)
    args = parser.parse_args()
    try:
        actual_sha = verified_checkout_sha(ROOT)
        if args.commit != actual_sha:
            raise PreflightFailed("COMMIT_CHECKOUT_MISMATCH")
        evidence = build_evidence(ROOT, args.bundle_dir, actual_sha)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(
            json.dumps(evidence, sort_keys=True, indent=2) + "\n",
            encoding="utf-8",
        )
    except (PreflightFailed, OSError) as exc:
        code = str(exc) if isinstance(exc, PreflightFailed) else "EVIDENCE_WRITE_FAILED"
        print(json.dumps({"result": "BLOCKED", "code": code}), file=sys.stderr)
        return 1
    print(json.dumps({"result": "DRY_RUN_VERIFIED", "source_commit": actual_sha,
                      "staging_activation": "BLOCKED", "deployed": False}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
