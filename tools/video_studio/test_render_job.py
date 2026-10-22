"""No unsafe input passes an owner-authorized render job boundary."""
import copy
import hashlib
import json
import unittest
from render_job import make_request, run_url, create_success_receipt
from render_request import validate_request


class VideoRenderJobTest(unittest.TestCase):
    def test_manual_choice_request_matches_android_json_contract(self):
        request = make_request("house_01", "1", "blue", "gable", "no", "60")
        spec, _ = validate_request(request)
        self.assertEqual(spec.manifest(), {
            "floors": 1, "wall_color": "blue", "roof_style": "gable", "garden": False,
        })
        self.assertEqual(request["target_duration_seconds"], 60)
        self.assertEqual(request["preview_duration_seconds"], 6)
        self.assertNotIn("token", json.dumps(request))

    def test_invalid_dispatch_choices_rejected(self):
        cases = [
            ("../escape", "1", "blue", "gable", "no", "30"),
            ("house", "3", "cream", "flat", "yes", "30"),
            ("house", "1", "purple", "flat", "yes", "30"),
            ("house", "1", "blue", "flat;rm", "no", "30"),
            ("house", "1", "blue", "flat", "unknown", "30"),
            ("house", "1", "blue", "flat", "no", "120"),
        ]
        for values in cases:
            with self.subTest(values=values), self.assertRaises(ValueError):
                make_request(*values)

    def test_run_id_is_not_an_external_url_or_path(self):
        self.assertEqual(run_url("123456"),
            "https://github.com/spy626/myra-android/actions/runs/123456")
        for bad in ("", "0", "-1", "1/evil", "abc", "https://evil.com", "1\n",
                    "99999999999999999999"):
            with self.subTest(value=bad), self.assertRaises(ValueError):
                run_url(bad)

    def test_receipt_only_for_matching_verified_output(self):
        request = make_request("house_01", "1", "blue", "gable", "no", "30")
        spec, _ = validate_request(request)
        manifest = {"spec": spec.manifest(), "expected_frames": 72, "fps": 12}
        fixture = b"data" * 5000
        receipt = create_success_receipt(request, manifest, fixture, "123", "a" * 40)
        self.assertEqual(receipt["state"], "SUCCEEDED")
        self.assertEqual(receipt["mp4_sha256"], hashlib.sha256(fixture).hexdigest())
        self.assertEqual(receipt["rendered_seconds"], 6)
        self.assertEqual(receipt["target_duration_seconds"], 30)
        wrong = copy.deepcopy(manifest)
        wrong["spec"]["floors"] = 2
        for meta, data, runid, sha in (
            (wrong, fixture, "123", "a" * 40),
            (manifest, b"too small", "123", "a" * 40),
            (manifest, fixture, "0", "a" * 40),
            (manifest, fixture, "123", "notsha"),
        ):
            with self.assertRaises(ValueError):
                create_success_receipt(request, meta, data, runid, sha)


if __name__ == "__main__":
    unittest.main()
