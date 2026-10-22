"""Contract tests for the reviewable Android-to-Blender offline bridge."""
import copy
import json
import unittest
from pathlib import Path
from render_request import load_render_request, validate_request

SAMPLE = Path(__file__).resolve().parent / "requests" / "sample-blue-house.json"

class RendererRequestTest(unittest.TestCase):
    def setUp(self):
        self.payload = json.loads(SAMPLE.read_text(encoding="utf-8"))

    def test_fixture_is_accepted_and_preserves_target_vs_preview(self):
        spec, meta = load_render_request(SAMPLE)
        self.assertEqual((spec.floors, spec.wall_color, spec.roof_style, spec.garden),
                         (1, "blue", "gable", False))
        self.assertEqual(meta["target_duration_seconds"], 30)
        self.assertEqual(meta["preview_duration_seconds"], 6)

    def test_no_extra_fields_or_executable_instructions(self):
        for extra in ("api_key", "prompt", "output_path", "script", "url"):
            x = copy.deepcopy(self.payload)
            x[extra] = "unsafe"
            with self.subTest(extra=extra), self.assertRaises(ValueError):
                validate_request(x)

    def test_reject_wrong_type_values_and_versions(self):
        for field,value in (("request_version", True),
                            ("request_version", 2),
                            ("target_duration_seconds", 6),
                            ("fps", "12"),
                            ("mode", "explainer"),
                            ("project_id", "../unsafe"),
                            ("project_id", ""),
                            ("preview_duration_seconds", 30)):
            x = copy.deepcopy(self.payload)
            x[field] = value
            with self.subTest(field=field,value=value), self.assertRaises(ValueError):
                validate_request(x)

    def test_reject_unsupported_3d_attributes(self):
        for key,value in (("floors", 3),("floors", True),("garden", 0),
                          ("wall_color", "purple"),("roof_style","domed")):
            x = copy.deepcopy(self.payload)
            x["spec"][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                validate_request(x)

    def test_missing_or_unknown_fields_rejected(self):
        x = copy.deepcopy(self.payload)
        del x["spec"]["garden"]
        with self.assertRaises(ValueError):
            validate_request(x)
        x = copy.deepcopy(self.payload)
        x["spec"]["image_url"] = "https://example.org/hidden"
        with self.assertRaises(ValueError):
            validate_request(x)

    def test_payload_does_not_contain_sensitive_user_content(self):
        text = SAMPLE.read_text()
        for key in ("prompt", "token", "password", "api_key", "server"):
            self.assertNotIn(key, text)

if __name__ == "__main__":
    unittest.main()
