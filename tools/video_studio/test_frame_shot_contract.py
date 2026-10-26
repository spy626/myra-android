"""Strict security, scene-integrity and storyboard tests for Slice 22."""
import copy
import unittest

from frame_shot_contract import approved_frame_shot, verify_frame_manifest
from render_job import make_request


def request(target="30", floors="1", color="blue", roof="gable", garden="no"):
    return make_request("house_slice22", floors, color, roof, garden, target)


def manifest(req):
    return {
        **approved_frame_shot(req),
        "actual_shot_rendered": True,
        "object_count": 26,
        "geometry": {
            "ground_floor_columns": 4,
            "top_beams": 4,
            "partial_walls": 3,
            "animated_objects": 11,
            "new_structure_not_finished_house": True,
        },
    }


class StructureShotTest(unittest.TestCase):
    def test_thirty_second_plan_selects_structure_scene(self):
        x = approved_frame_shot(request())
        self.assertEqual((x["scene_id"], x["stage"], x["camera"]),
                         ("structure", "structure", "orbit_left"))
        self.assertEqual((x["storyboard_start_seconds"], x["storyboard_start_frame"],
                          x["storyboard_end_frame"]), (12, 145, 216))

    def test_sixty_second_plan_selects_columns_scene(self):
        x = approved_frame_shot(request("60"))
        self.assertEqual((x["scene_id"], x["stage"], x["camera"]),
                         ("columns", "structure", "orbit_left"))
        self.assertEqual((x["storyboard_start_seconds"], x["storyboard_start_frame"],
                          x["storyboard_end_frame"]), (18, 217, 288))

    def test_actual_shot_contract_always_six_seconds(self):
        for target in ("30", "60"):
            with self.subTest(target=target):
                x = approved_frame_shot(request(target))
                self.assertEqual((x["fps"], x["expected_frames"], x["duration_seconds"]),
                                 (12, 72, 6))
                self.assertFalse(x["video_rendered_in_contract"])

    def test_style_survives_from_existing_request_schema(self):
        for floors, color, roof, garden in (
            ("1", "blue", "gable", "no"), ("2", "cream", "flat", "yes"),
            ("2", "terracotta", "gable", "no")
        ):
            with self.subTest(style=color):
                x = approved_frame_shot(request("30", floors, color, roof, garden))
                self.assertEqual(x["spec"], {
                    "floors": int(floors), "wall_color": color,
                    "roof_style": roof, "garden": garden == "yes"
                })

    def test_unchanged_request_generates_identical_shot_fingerprint(self):
        req = request("60")
        self.assertEqual(approved_frame_shot(req), approved_frame_shot(copy.deepcopy(req)))
        self.assertRegex(approved_frame_shot(req)["storyboard_sha256"], r"^[0-9a-f]{64}$")

    def test_no_cloud_deployment_or_dispatch(self):
        x = approved_frame_shot(request())
        self.assertFalse(x["staging_deployed"])
        self.assertFalse(x["workflow_dispatched"])
        self.assertEqual(x["kind"], "real_blender_structural_frame_shot_proof")

    def test_illegal_inputs_and_arbitrary_script_rejected(self):
        for key, value in (("project_id", "../x"), ("renderer", "remote"),
                           ("target_duration_seconds", 120), ("mode", "command"),
                           ("fps", 24), ("width", 1024)):
            req = request()
            req[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                approved_frame_shot(req)
        for key in ("api_key", "prompt", "external_asset", "command", "video_url"):
            req = request()
            req[key] = "bad"
            with self.subTest(key=key), self.assertRaises(ValueError):
                approved_frame_shot(req)

    def test_valid_manifest_proof(self):
        req = request()
        verify_frame_manifest(req, manifest(req))

    def test_reject_forged_scene_metadata(self):
        req = request()
        for key, value in (
            ("scene_id", "foundation"), ("stage", "finished_house"),
            ("camera", "drone"), ("duration_seconds", 30),
            ("storyboard_sha256", "a"*64), ("width", 500),
            ("engine", "CYCLES"), ("actual_shot_rendered", False),
            ("staging_deployed", True), ("workflow_dispatched", True)
        ):
            m = manifest(req)
            m[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                verify_frame_manifest(req, m)

    def test_reject_mismatched_owner_render_request(self):
        req = request()
        m = manifest(req)
        for field, value in (("target_duration_seconds", 60), ("project_id", "another")):
            modified = copy.deepcopy(req)
            modified[field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                verify_frame_manifest(modified, m)

    def test_reject_missing_or_extra_manifest_keys(self):
        req = request()
        for update in (lambda m: m.pop("geometry"),
                       lambda m: m.update(fake_output=True)):
            m = manifest(req)
            update(m)
            with self.assertRaises(ValueError):
                verify_frame_manifest(req, m)

    def test_reject_missing_geometry_or_fake_animation(self):
        req = request()
        for key, value in (
            ("ground_floor_columns", 3), ("top_beams", 0),
            ("partial_walls", 2), ("animated_objects", 10),
            ("new_structure_not_finished_house", False)
        ):
            m = manifest(req)
            m["geometry"][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                verify_frame_manifest(req, m)
        m = manifest(req)
        m["object_count"] = 3
        with self.assertRaises(ValueError):
            verify_frame_manifest(req, m)


if __name__ == "__main__":
    unittest.main()
