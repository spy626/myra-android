"""Cross-language contract test for Slice 8 approved manual render fields.

This golden fingerprint is also asserted in VideoStudioSubmissionPackageTest.kt.
It is an equality marker, never proof of user identity or authorization.
"""
import unittest
from render_job import make_request, approved_input_fingerprint, create_success_receipt

EXPECTED = "7a15c74a1779d3aa9ac0ccb6519d8d605a079840f5d25d3b86e0520924c6782e"

class ApprovedSubmissionFingerprintTest(unittest.TestCase):
    def test_matches_kotlin_approved_workflow_inputs(self):
        request = make_request("mini_house_demo", "1", "blue", "gable", "no", "30")
        self.assertEqual(approved_input_fingerprint(request), EXPECTED)

    def test_each_input_changes_reference_fingerprint(self):
        original = make_request("mini_house_demo", "1", "blue", "gable", "no", "30")
        variations = [
            ("different", "1", "blue", "gable", "no", "30"),
            ("mini_house_demo", "2", "blue", "gable", "no", "30"),
            ("mini_house_demo", "1", "cream", "gable", "no", "30"),
            ("mini_house_demo", "1", "blue", "flat", "no", "30"),
            ("mini_house_demo", "1", "blue", "gable", "yes", "30"),
            ("mini_house_demo", "1", "blue", "gable", "no", "60"),
        ]
        for parts in variations:
            with self.subTest(parts=parts):
                self.assertNotEqual(
                    approved_input_fingerprint(make_request(*parts)),
                    approved_input_fingerprint(original)
                )

    def test_only_verified_render_receipt_contains_fingerprint(self):
        req = make_request("mini_house_demo", "1", "blue", "gable", "no", "30")
        scene = {"spec": req["spec"], "expected_frames": 72, "fps": 12}
        done = create_success_receipt(req, scene, b"test" * 5000,
                                      "12345", "a" * 40)
        self.assertEqual(done["approved_input_fingerprint"], EXPECTED)
        self.assertEqual(done["rendered_seconds"], 6)
        with self.assertRaises(ValueError):
            create_success_receipt(req, scene, b"short",
                                   "12345", "a" * 40)

if __name__ == "__main__":
    unittest.main()
