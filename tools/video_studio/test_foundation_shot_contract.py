"""No Blender dependency: negative/positive security contracts for Slice 21."""
import copy
import unittest

from render_job import make_request
from foundation_shot_contract import approved_foundation_shot, verify_shot_manifest


def request(floors="1", color="blue", roof="gable", garden="no", target="30"):
    return make_request("house_slice21", floors, color, roof, garden, target)


def sample_manifest(r):
    return {**approved_foundation_shot(r),
            "actual_shot_rendered": True, "object_count": 17,
            "geometry": {"foundation_slabs": 1, "foundation_columns": 4,
                         "rebar_rods": 4, "animated_objects": 9}}


class FoundationShotContractTest(unittest.TestCase):
    def test_thirty_target_has_dolly_foundation_stage(self):
        shot = approved_foundation_shot(request())
        self.assertEqual(shot["scene_id"], "foundation")
        self.assertEqual(shot["camera"], "dolly_in")
        self.assertEqual(shot["storyboard_start_seconds"], 6)
        self.assertEqual((shot["storyboard_start_frame"], shot["storyboard_end_frame"]), (73, 144))

    def test_sixty_target_has_front_wide_foundation_stage(self):
        shot = approved_foundation_shot(request(target="60"))
        self.assertEqual(shot["camera"], "front_wide")
        self.assertEqual(shot["storyboard_start_seconds"], 12)
        self.assertEqual((shot["storyboard_start_frame"], shot["storyboard_end_frame"]), (145, 216))

    def test_real_duration_only_six_seconds(self):
        for t in ("30", "60"):
            with self.subTest(target=t):
                shot = approved_foundation_shot(request(target=t))
                self.assertEqual((shot["duration_seconds"], shot["expected_frames"], shot["fps"]), (6, 72, 12))
                self.assertFalse(shot["video_rendered_in_contract"])

    def test_house_attributes_are_unmodified(self):
        for floors, color, roof, garden in (("1", "blue", "gable", "no"),
                                           ("2", "cream", "flat", "yes")):
            with self.subTest(floors=floors):
                shot = approved_foundation_shot(request(floors, color, roof, garden))
                self.assertEqual(shot["spec"], {"floors": int(floors), "wall_color": color,
                                               "roof_style": roof, "garden": garden == "yes"})

    def test_repeat_generates_stable_plan_hash(self):
        r = request(target="60")
        self.assertEqual(approved_foundation_shot(r), approved_foundation_shot(copy.deepcopy(r)))
        self.assertRegex(approved_foundation_shot(r)["storyboard_sha256"], r"^[0-9a-f]{64}$")

    def test_auth_dispatch_deployment_denied(self):
        r = request()
        shot = approved_foundation_shot(r)
        self.assertFalse(shot["staging_deployed"])
        self.assertFalse(shot["workflow_dispatched"])
        for extra in ("api_key", "prompt", "url", "script", "dispatch"):
            injected = copy.deepcopy(r)
            injected[extra] = "malicious"
            with self.subTest(extra=extra), self.assertRaises(ValueError):
                approved_foundation_shot(injected)

    def test_bad_request_rejected(self):
        for field, value in (("project_id", "../unsafe"), ("fps", 30),
                             ("target_duration_seconds", 90), ("mode", "shell")):
            r = request()
            r[field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                approved_foundation_shot(r)

    def test_manifest_full_positive(self):
        r = request()
        verify_shot_manifest(r, sample_manifest(r))

    def test_manifest_rejects_fake_finished_video(self):
        r = request()
        for key, value in (("scene_id", "reveal"), ("camera", "orbit_right"),
                           ("target_duration_seconds", 60), ("storyboard_sha256", "a" * 64),
                           ("engine", "CYCLES"), ("actual_shot_rendered", False),
                           ("width", 1080), ("workflow_dispatched", True)):
            m = sample_manifest(r)
            m[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                verify_shot_manifest(r, m)

    def test_manifest_requires_construction_geometry(self):
        r = request()
        for key, value in (("foundation_slabs", 0), ("foundation_columns", 3),
                           ("rebar_rods", 0), ("animated_objects", 2),
                           ("animated_objects", True)):
            m = sample_manifest(r)
            m["geometry"][key] = value
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                verify_shot_manifest(r, m)

    def test_manifest_rejects_missing_scene_or_objects(self):
        r = request()
        for change in (lambda m: m.pop("geometry"), lambda m: m.update(object_count=3),
                       lambda m: m["geometry"].update(fake_asset=True)):
            m = sample_manifest(r)
            change(m)
            with self.assertRaises(ValueError):
                verify_shot_manifest(r, m)


if __name__ == "__main__":
    unittest.main()
