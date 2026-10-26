"""Fail-closed proofs for 0-6s site preparation and 24-30s house reveal."""
import copy
import unittest

from render_job import make_request
from edge_shot_contract import approved_edge_shot, verify_edge_manifest


def req(floors="1", garden="no", target="30", roof="gable"):
    return make_request("slice25_proof", floors, "blue", roof, garden, target)


def proof(request, name):
    expected = approved_edge_shot(request, name)
    return {
        **expected, "actual_shot_rendered": True,
        "object_count": 35,
        "geometry": ({
            "site_clearing": 1, "survey_guides": 2, "excavation_edges": 4,
            "survey_stakes": 4, "foundation_not_poured": True
        } if name == "lot" else {
            "existing_columns": 4, "existing_beams": 4, "completed_walls": 6,
            "roof_panels": 2 if request["spec"]["roof_style"] == "gable" else 1,
            "existing_front_details": 4, "animated_finishing_details": 6
        }),
    }


class EdgeShotContractTest(unittest.TestCase):
    def test_correct_scene_alignment(self):
        self.assertEqual((approved_edge_shot(req(), "lot")["storyboard_start_frame"],
                          approved_edge_shot(req(), "lot")["storyboard_end_frame"]), (1, 72))
        self.assertEqual((approved_edge_shot(req(), "reveal")["storyboard_start_frame"],
                          approved_edge_shot(req(), "reveal")["storyboard_end_frame"]), (289, 360))
        self.assertEqual(approved_edge_shot(req(), "lot")["storyboard_sha256"],
                         approved_edge_shot(req(), "reveal")["storyboard_sha256"])

    def test_manifest_exact_and_deterministic(self):
        for shot in ("lot", "reveal"):
            data = req()
            self.assertEqual(approved_edge_shot(data, shot), approved_edge_shot(copy.deepcopy(data), shot))
            verify_edge_manifest(data, shot, proof(data, shot))
            self.assertFalse(approved_edge_shot(data, shot)["workflow_dispatched"])

    def test_flat_and_gable_proven_contract(self):
        for style in ("flat", "gable"):
            data = req(roof=style)
            verify_edge_manifest(data, "reveal", proof(data, "reveal"))

    def test_unsupported_variants_fail_closed(self):
        for options in ({"floors": "2"}, {"target": "60"}, {"garden": "yes"}):
            for shot in ("lot", "reveal"):
                with self.subTest(options=options, shot=shot), self.assertRaises(ValueError):
                    approved_edge_shot(req(**options), shot)

    def test_no_fake_or_injected_scene(self):
        for shot in ("something", "", "facade", "../lot"):
            with self.assertRaises(ValueError):
                approved_edge_shot(req(), shot)
        for key, val in (
            ("project_id", "../outside"), ("fps", 24),
            ("target_duration_seconds", 18), ("renderer", "script")
        ):
            request = req()
            request[key] = val
            with self.assertRaises(ValueError):
                approved_edge_shot(request, "lot")
        for key in ("url", "command", "python", "api_key", "workflow"):
            request = req()
            request[key] = "arbitrary"
            with self.assertRaises(ValueError):
                approved_edge_shot(request, "reveal")

    def test_detect_forged_proof(self):
        data = req()
        for shot in ("lot", "reveal"):
            for key, val in (
                ("scene_id", "other"), ("stage", "fake"), ("storyboard_start_frame", 9),
                ("storyboard_sha256", "0" * 64), ("expected_frames", 360),
                ("duration_seconds", 30), ("actual_shot_rendered", False),
                ("workflow_dispatched", True)
            ):
                x = proof(data, shot)
                x[key] = val
                with self.subTest(shot=shot, key=key), self.assertRaises(ValueError):
                    verify_edge_manifest(data, shot, x)
            for key in proof(data, shot)["geometry"]:
                x = proof(data, shot)
                x["geometry"][key] = False if type(x["geometry"][key]) is bool else 0
                with self.subTest(shot=shot, geometry=key), self.assertRaises(ValueError):
                    verify_edge_manifest(data, shot, x)
            x = proof(data, shot)
            x["unknown_secret"] = "not_allowed"
            with self.assertRaises(ValueError):
                verify_edge_manifest(data, shot, x)
            x = proof(data, shot)
            x["object_count"] = 3
            with self.assertRaises(ValueError):
                verify_edge_manifest(data, shot, x)

    def test_cross_project_and_shot_spoofing(self):
        for shot in ("lot", "reveal"):
            x = proof(req(), shot)
            other = make_request("different_project", "1", "blue", "gable", "no", "30")
            with self.assertRaises(ValueError):
                verify_edge_manifest(other, shot, x)
            with self.assertRaises(ValueError):
                verify_edge_manifest(req(), "reveal" if shot == "lot" else "lot", x)


if __name__ == "__main__":
    unittest.main()
