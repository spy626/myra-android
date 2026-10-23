"""Offline preflight: positive/negative test cases, no real credentials or API calls."""
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from check_staging_readiness import ROOT, STAGING, audit_manifest, audit_runtime, report


class Slice15StagingPreflightTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.example = (ROOT / STAGING).read_text(encoding="utf-8")

    def test_checked_in_template_is_disabled_and_contract_passes(self):
        self.assertEqual(audit_manifest(self.example), [])
        result = report(ROOT)
        self.assertTrue(result["contract_passed"])
        self.assertEqual(result["staging_activation"], "BLOCKED")
        self.assertFalse(result["deployed"])
        self.assertFalse(result["workflow_dispatched"])
        self.assertGreaterEqual(len(result["required_before_any_staging_activation"]), 4)
        self.assertEqual(audit_runtime(ROOT), [])

    def test_enabled_flag_and_unreviewed_variables_fail_closed(self):
        activated = self.example.replace('VIDEO_AUTH_ENABLED = "false"', 'VIDEO_AUTH_ENABLED = "true"')
        self.assertIn("NON_DISABLED_OR_UNREVIEWED_VARIABLES", audit_manifest(activated))
        addition = self.example.replace('VIDEO_AUTH_ENABLED = "false"', 'VIDEO_AUTH_ENABLED = "false"\nUNTRUSTED = "yes"')
        self.assertIn("NON_DISABLED_OR_UNREVIEWED_VARIABLES", audit_manifest(addition))

    def test_secret_in_vars_is_detected_and_not_echoed(self):
        secret = "a-secret-must-not-appear-in-report"
        leaked = self.example.replace('VIDEO_AUTH_ENABLED = "false"', f'VIDEO_AUTH_ENABLED = "false"\nVIDEO_GITHUB_CLIENT_SECRET = "{secret}"')
        findings = audit_manifest(leaked)
        self.assertIn("SECRET_KEY_EMBEDDED_IN_MANIFEST", findings)
        self.assertNotIn(secret, json.dumps(findings))

    def test_wrong_entrypoint_and_public_dev_route_fail(self):
        wrong = self.example.replace('main = "src/video-auth-worker-cloudflare.mjs"', 'main = "src/index.js"')
        self.assertIn("UNSAFE_ENTRYPOINT", audit_manifest(wrong))
        dev = self.example.replace('workers_dev = false', 'workers_dev = true')
        self.assertIn("WORKERS_DEV_NOT_DISABLED", audit_manifest(dev))
        routed = self.example.replace('[vars]', 'route = "test.example.com/*"\n\n[vars]')
        self.assertIn("UNREVIEWED_DEPLOYMENT_SURFACE", audit_manifest(routed))

    def test_durable_binding_migration_and_replica_changes_rejected(self):
        binding = self.example.replace('class_name = "VideoOAuthVault"', 'class_name = "UnknownVault"')
        self.assertIn("DURABLE_BINDINGS_MISMATCH", audit_manifest(binding))
        migration = self.example.replace('tag = "video-auth-staging-v2"', 'tag = "video-auth-staging-v0"')
        self.assertIn("MIGRATION_SEQUENCE_MISMATCH", audit_manifest(migration))
        duplicate = self.example + '\n[[migrations]]\ntag = "bad"\nnew_sqlite_classes = ["Unknown"]\n'
        self.assertIn("MIGRATION_SEQUENCE_MISMATCH", audit_manifest(duplicate))

    def test_invalid_toml_and_malformed_migrations_fail(self):
        self.assertIn("INVALID_TOML", audit_manifest('[vars\ninvalid'))
        self.assertIn("MIGRATION_SEQUENCE_MISMATCH", audit_manifest(self.example.replace('[[migrations]]', '[[not_migrations]]')))

    def test_missing_runtime_and_manifest_are_blockers(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            finding = report(root)
            self.assertFalse(finding["contract_passed"])
            self.assertIn("MISSING_STAGING_MANIFEST", finding["diagnostic_codes"])
            self.assertIn("MISSING_STAGING_SOURCE", finding["diagnostic_codes"])

    def test_runtime_source_guard_and_exports_are_required(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for relative in (
                "connector-broker/src/video-auth-worker-cloudflare.mjs",
                "connector-broker/src/video-auth-worker.mjs",
            ):
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("export default {};", encoding="utf-8")
            self.assertIn("RPC_OR_ALARM_WRAPPER_NOT_READY", audit_runtime(root))
            self.assertIn("DISABLED_STAGING_GUARD_MISSING", audit_runtime(root))

    def test_command_returns_success_but_reports_staging_blocked(self):
        completed = subprocess.run(
            [sys.executable, str(ROOT / "tools/video_studio/check_staging_readiness.py"), "--root", str(ROOT)],
            capture_output=True, text=True, check=False,
        )
        self.assertEqual(completed.returncode, 0, completed.stderr)
        data = json.loads(completed.stdout)
        self.assertTrue(data["contract_passed"])
        self.assertEqual(data["staging_activation"], "BLOCKED")


if __name__ == "__main__":
    unittest.main()
