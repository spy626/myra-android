"""Slice24 tests: strict third-stage identity, frame continuity, negative cases."""
import copy
import unittest

from render_job import make_request
from facade_shot_contract import approved_facade_shot, verify_facade_manifest
from construction_sequence_contract import assembly_plan


def request(target="30", floors="1", roof="gable", garden="no"):
    return make_request("house_slice24", floors, "blue", roof, garden, target)


def manifest(req):
    proof = approved_facade_shot(req)
    return {
        **proof,
        "actual_shot_rendered": True,
        "object_count": 34,
        "geometry": {
            "existing_columns": 4,
            "existing_beams": 4,
            "existing_partial_walls": 3,
            "animated_upper_walls": 3,
            "animated_roof_panels": 2 if req["spec"]["roof_style"] == "gable" else 1,
            "animated_front_details": 4,
            "unfinished_house_not_full_reveal": True,
        },
    }


class FacadeShotContractTest(unittest.TestCase):
    def test_third_shot_is_exactly_next_in_30s_plan(self):
        req = request()
        before = assembly_plan(req)
        next_shot = approved_facade_shot(req)
        self.assertEqual((next_shot["scene_id"], next_shot["stage"], next_shot["camera"]),
                         ("details", "facade", "side_track"))
        self.assertEqual((next_shot["storyboard_start_frame"],
                          next_shot["storyboard_end_frame"],
                          next_shot["storyboard_start_seconds"]), (217, 288, 18))
        self.assertEqual(next_shot["storyboard_start_frame"], before["storyboard_end_frame"] + 1)

    def test_six_second_output_is_not_falsely_full_30s(self):
        x = approved_facade_shot(request())
        self.assertEqual((x["duration_seconds"], x["expected_frames"], x["fps"]), (6, 72, 12))
        self.assertFalse(x["video_rendered_in_contract"])

    def test_single_floor_flat_roof_is_explicitly_supported(self):
        x = approved_facade_shot(request(roof="flat"))
        self.assertEqual(x["spec"]["roof_style"], "flat")
        verify_facade_manifest(request(roof="flat"), manifest(request(roof="flat")))

    def test_colors_and_garden_preserved(self):
        req = make_request("style_24", "1", "terracotta", "gable", "yes", "30")
        x = approved_facade_shot(req)
        self.assertEqual(x["spec"], {
            "floors": 1, "wall_color": "terracotta", "roof_style": "gable", "garden": True
        })

    def test_two_floors_fail_closed_not_faked(self):
        with self.assertRaises(ValueError):
            approved_facade_shot(request(floors="2"))

    def test_sixty_second_storyboard_not_misrepresented(self):
        with self.assertRaises(ValueError):
            approved_facade_shot(request(target="60"))

    def test_deterministic_no_credentials_or_commands(self):
        req = request()
        self.assertEqual(approved_facade_shot(req), approved_facade_shot(copy.deepcopy(req)))
        x = approved_facade_shot(req)
        self.assertRegex(x["storyboard_sha256"], r"^[0-9a-f]{64}$")
        self.assertFalse(x["staging_deployed"])
        self.assertFalse(x["workflow_dispatched"])

    def test_exact_manifest_passes(self):
        r = request()
        verify_facade_manifest(r, manifest(r))

    def test_reject_forged_frame_stage_and_timestamps(self):
        r = request()
        for k, val in (
            ("scene_id", "reveal"), ("stage", "roof_only"), ("camera", "drone"),
            ("storyboard_start_frame", 1), ("storyboard_end_frame", 360),
            ("target_duration_seconds", 60), ("duration_seconds", 30),
            ("storyboard_sha256", "0"*64), ("width", 1080),
            ("workflow_dispatched", True), ("actual_shot_rendered", False)
        ):
            m = manifest(r)
            m[k] = val
            with self.subTest(key=k), self.assertRaises(ValueError):
                verify_facade_manifest(r, m)

    def test_reject_missing_real_geometry(self):
        r = request()
        for k, val in (
            ("existing_columns", 3), ("existing_beams", 5),
            ("existing_partial_walls", 0), ("animated_upper_walls", 2),
            ("animated_roof_panels", 1), ("animated_front_details", 3),
            ("unfinished_house_not_full_reveal", False)
        ):
            m = manifest(r)
            m["geometry"][k] = val
            with self.subTest(key=k), self.assertRaises(ValueError):
                verify_facade_manifest(r, m)

    def test_reject_extra_or_missing_manifest_fields(self):
        r = request()
        m = manifest(r)
        m["secret"] = "unsafe"
        with self.assertRaises(ValueError):
            verify_facade_manifest(r, m)
        m = manifest(r)
        del m["geometry"]
        with self.assertRaises(ValueError):
            verify_facade_manifest(r, m)

    def test_reject_cross_project_scene_claim(self):
        r = request()
        m = manifest(r)
        other = make_request("another_project", "1", "blue", "gable", "no", "30")
        with self.assertRaises(ValueError):
            verify_facade_manifest(other, m)

    def test_reject_bad_requests_no_script_or_remote_media(self):
        for k, value in (
            ("project_id", "../evil"), ("target_duration_seconds", 18),
            ("mode", "download"), ("fps", 30), ("width", 999),
        ):
            req = request()
            req[k] = value
            with self.subTest(field=k), self.assertRaises(ValueError):
                approved_facade_shot(req)
        for k in ("script", "url", "api_key", "render_path", "prompt", "token"):
            req = request()
            req[k] = "arbitrary"
            with self.subTest(field=k), self.assertRaises(ValueError):
                approved_facade_shot(req)


if __name__ == "__main__":
    unittest.main()
