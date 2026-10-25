"""Slice 20: deterministic offline storyboard, zero cloud/dispatch contracts."""
import copy
import json
import unittest

from storyboard_plan import make_storyboard
from render_job import make_request


class StoryboardPlanTest(unittest.TestCase):
    def request(self, floors="2", garden="yes", target="30"):
        return make_request("demo_20", floors, "cream", "gable", garden, target)

    def test_thirty_seconds_are_five_contiguous_scenes(self):
        plan = make_storyboard(self.request())
        self.assertEqual(len(plan["scenes"]), 5)
        self.assertEqual(plan["planned_frames"], 360)
        self.assertEqual((plan["scenes"][0]["start_frame"], plan["scenes"][-1]["end_frame"]), (1, 360))

    def test_sixty_seconds_are_ten_contiguous_scenes(self):
        plan = make_storyboard(self.request(target="60"))
        self.assertEqual(len(plan["scenes"]), 10)
        self.assertEqual(plan["planned_frames"], 720)
        for i, scene in enumerate(plan["scenes"]):
            self.assertEqual(scene["start_seconds"], i * 6)
            self.assertEqual(scene["start_frame"], i * 72 + 1)
            self.assertEqual(scene["end_frame"], (i + 1) * 72)
            self.assertEqual(scene["duration_seconds"], 6)

    def test_one_floor_does_not_claim_upper_floor(self):
        plan = make_storyboard(self.request(floors="1", target="60"))
        stages = [s["stage"] for s in plan["scenes"]]
        self.assertNotIn("upper_structure", stages)
        self.assertIn("entry_details", stages)

    def test_garden_false_has_pathway_not_garden(self):
        plan = make_storyboard(self.request(garden="no", target="60"))
        stages = [s["stage"] for s in plan["scenes"]]
        self.assertIn("pathway", stages)
        self.assertNotIn("garden", stages)

    def test_garden_true_has_garden(self):
        plan = make_storyboard(self.request(target="60"))
        self.assertIn("garden", [s["stage"] for s in plan["scenes"]])

    def test_style_in_captions(self):
        plan = make_storyboard(self.request(target="60"))
        self.assertTrue(any("gable" in s["caption"] for s in plan["scenes"]))
        self.assertTrue(any("cream" in s["caption"] for s in plan["scenes"]))

    def test_repeat_is_deterministic(self):
        request = self.request(target="60")
        self.assertEqual(make_storyboard(request), make_storyboard(copy.deepcopy(request)))

    def test_existing_preview_untouched(self):
        plan = make_storyboard(self.request(target="60"))
        self.assertEqual(plan["existing_preview_duration_seconds"], 6)
        self.assertEqual(plan["renderer"], "blender-mini-house-v1")

    def test_reject_untrusted_fields(self):
        for key in ("prompt", "token", "script", "url", "dispatch"):
            request = self.request()
            request[key] = "attack"
            with self.subTest(key=key), self.assertRaises(ValueError):
                make_storyboard(request)

    def test_reject_wrong_duration_or_setting(self):
        for key, value in (("target_duration_seconds", 120), ("fps", 24),
                           ("project_id", "../x"), ("renderer", "custom")):
            request = self.request()
            request[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                make_storyboard(request)

    def test_reject_bool_as_floors(self):
        request = self.request()
        request["spec"]["floors"] = True
        with self.assertRaises(ValueError):
            make_storyboard(request)

    def test_plan_not_dispatch_or_render_receipt(self):
        plan = make_storyboard(self.request())
        self.assertFalse(plan["video_rendered"])
        self.assertFalse(plan["staging_deployed"])
        self.assertFalse(plan["workflow_dispatched"])
        self.assertTrue(plan["owner_approval_required_before_dispatch"])
        self.assertNotIn("mp4_sha256", json.dumps(plan))

    def test_no_unbounded_text_or_paths(self):
        plan = make_storyboard(self.request())
        for scene in plan["scenes"]:
            self.assertLessEqual(len(scene["caption"]), 80)
            self.assertRegex(scene["scene_id"], r"^[a-z_]+$")
            self.assertNotIn("/", scene["stage"])


if __name__ == "__main__":
    unittest.main()
