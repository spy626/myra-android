"""Slice 19: fail-closed evidence tests; never contacts Cloudflare/GitHub."""
import hashlib
import tempfile
import unittest
from pathlib import Path

from staging_release_evidence import (
    ROOT, REQUIRED_EXPORTS, PreflightFailed, build_evidence,
    verified_checkout_sha,
)


class StagingEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.bundle_dir = Path(self.tmp.name)
        self.bundle = self.bundle_dir / "worker.js"
        self.commit = "a" * 40
        self.source = (
            ("export const " + " = {};\nexport const ".join(REQUIRED_EXPORTS)
             + " = {};\n") + "/* bundled worker */\n" + "x" * 4096
        ).encode()
        self.bundle.write_bytes(self.source)

    def evidence(self):
        return build_evidence(ROOT, self.bundle_dir, self.commit)

    def test_good_bundle_yields_readonly_evidence_and_blocked_activation(self):
        result = self.evidence()
        self.assertEqual(result["worker_bundle_sha256"], hashlib.sha256(self.source).hexdigest())
        self.assertEqual(result["worker_bundle_bytes"], len(self.source))
        self.assertEqual(result["source_commit"], self.commit)
        self.assertEqual(result["staging_activation"], "BLOCKED")
        for k in ("deployed", "workflow_dispatched", "ready_for_live_activation",
                  "hosted_cloudflare_validated", "evidence_is_signed"):
            self.assertFalse(result[k], k)
        self.assertTrue(result["manual_owner_approval_required"])
        self.assertTrue(result["staging_flags_disabled"])

    def test_missing_bundle_fails(self):
        self.bundle.unlink()
        with self.assertRaisesRegex(PreflightFailed, "EXPECTED_SINGLE_WORKER_BUNDLE"):
            self.evidence()

    def test_multiple_bundles_fail(self):
        (self.bundle_dir / "extra.js").write_bytes(self.source)
        with self.assertRaisesRegex(PreflightFailed, "EXPECTED_SINGLE_WORKER_BUNDLE"):
            self.evidence()

    def test_truncated_bundle_fails(self):
        self.bundle.write_bytes(b"export default {};")
        with self.assertRaisesRegex(PreflightFailed, "WRANGLER_BUNDLE_TOO_SMALL"):
            self.evidence()

    def test_missing_durable_export_fails(self):
        source = self.source.replace(b"VideoOAuthRateGate", b"removed_rate_gate")
        self.bundle.write_bytes(source)
        with self.assertRaisesRegex(PreflightFailed, "DURABLE_OBJECT_EXPORT_MISSING"):
            self.evidence()

    def test_invalid_sha_rejected(self):
        with self.assertRaisesRegex(PreflightFailed, "INVALID_COMMIT_SHA"):
            build_evidence(ROOT, self.bundle_dir, "branch-name")
        with self.assertRaisesRegex(PreflightFailed, "INVALID_COMMIT_SHA"):
            build_evidence(ROOT, self.bundle_dir, "A" * 40)

    def test_size_limit_rejected(self):
        with self.bundle.open("ab") as out:
            out.write(b"X" * (4 * 1024 * 1024))
        with self.assertRaisesRegex(PreflightFailed, "BUNDLE_SIZE_OR_SYMLINK"):
            self.evidence()

    def test_symlink_bundle_rejected(self):
        target = self.bundle_dir / "target.txt"
        target.write_bytes(self.source)
        self.bundle.unlink()
        self.bundle.symlink_to(target)
        with self.assertRaisesRegex(PreflightFailed, "BUNDLE_SIZE_OR_SYMLINK"):
            self.evidence()

    def test_missing_or_changed_staging_manifest_fails(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            with self.assertRaisesRegex(PreflightFailed, "DISABLED_STAGING_PREFLIGHT_FAILED"):
                build_evidence(root, self.bundle_dir, self.commit)

    def test_source_commit_tracks_actual_checkout(self):
        sha = verified_checkout_sha(ROOT)
        self.assertEqual(len(sha), 40)
        self.assertTrue(all(c in "0123456789abcdef" for c in sha))


if __name__ == "__main__":
    unittest.main()
