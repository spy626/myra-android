"""Slice 23: no video can be called 12s merely because two manifests claim so."""
import copy
import unittest

from render_job import make_request
from foundation_shot_contract import approved_foundation_shot
from frame_shot_contract import approved_frame_shot
from construction_sequence_contract import (
    assembly_plan, validate_source_manifests, verified_output_manifest,
)


def req(target="30", floors="1", garden="no"):
    return make_request("house_slice23", floors, "blue", "gable", garden, target)


def foundation_manifest(r):
    return {
        **approved_foundation_shot(r), "actual_shot_rendered": True,
        "object_count": 17, "geometry": {
            "foundation_slabs": 1, "foundation_columns": 4,
            "rebar_rods": 4, "animated_objects": 9,
        }
    }


def frame_manifest(r):
    return {
        **approved_frame_shot(r), "actual_shot_rendered": True,
        "object_count": 26, "geometry": {
            "ground_floor_columns": 4, "top_beams": 4,
            "partial_walls": 3, "animated_objects": 11,
            "new_structure_not_finished_house": True,
        }
    }


class AssemblyContractTests(unittest.TestCase):
    def test_30_second_storyboard_has_two_adjacent_shots(self):
        p = assembly_plan(req())
        self.assertEqual([s["source_scene_id"] for s in p["shots"]], ["foundation", "structure"])
        self.assertEqual([s["output_first_frame"] for s in p["shots"]], [1, 73])
        self.assertEqual([s["output_last_frame"] for s in p["shots"]], [72, 144])
        self.assertEqual((p["storyboard_start_frame"], p["storyboard_end_frame"]), (73, 216))

    def test_60_second_storyboard_has_adjacent_foundation_columns(self):
        p = assembly_plan(req(target="60"))
        self.assertEqual([s["source_scene_id"] for s in p["shots"]], ["foundation", "columns"])
        self.assertEqual((p["storyboard_start_frame"], p["storyboard_end_frame"]), (145, 288))
        self.assertEqual([s["offset_seconds"] for s in p["shots"]], [0, 6])

    def test_assembly_is_not_a_full_30_or_60_second_render(self):
        for target in ("30", "60"):
            with self.subTest(target=target):
                plan = assembly_plan(req(target))
                self.assertEqual(plan["sequence_duration_seconds"], 12)
                self.assertEqual(plan["sequence_frames"], 144)
                self.assertEqual(plan["target_duration_seconds"], int(target))
                self.assertFalse(plan["is_full_target_duration"])

    def test_preserves_one_owner_and_single_house_spec(self):
        plan = assembly_plan(req(floors="2", garden="yes"))
        self.assertEqual(plan["spec"], {
            "floors": 2, "wall_color": "blue", "roof_style": "gable", "garden": True
        })
        self.assertEqual(plan["project_id"], "house_slice23")

    def test_same_inputs_same_plan_no_randomness(self):
        r = req(target="60")
        self.assertEqual(assembly_plan(r), assembly_plan(copy.deepcopy(r)))
        self.assertRegex(assembly_plan(r)["storyboard_sha256"], r"^[0-9a-f]{64}$")

    def test_bad_request_fields_are_not_promoted_to_executor(self):
        for field, value in (
            ("fps", 60), ("project_id", "../evil"),
            ("target_duration_seconds", 12), ("mode", "shell"),
            ("renderer", "http-api"),
        ):
            r = req()
            r[field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                assembly_plan(r)
        for extra in ("url", "script", "cmd", "token", "asset_path"):
            r = req()
            r[extra] = "attack"
            with self.subTest(extra=extra), self.assertRaises(ValueError):
                assembly_plan(r)

    def test_positive_manifests_bind_to_existing_storyboard(self):
        r = req()
        self.assertEqual(validate_source_manifests(
            r, foundation_manifest(r), frame_manifest(r)
        ), assembly_plan(r))

    def test_mixed_projects_and_requests_fail(self):
        r = req()
        for modified in (make_request("another_owner", "1", "blue", "gable", "no", "30"),
                         make_request("house_slice23", "2", "blue", "gable", "no", "30"),
                         req(target="60")):
            with self.assertRaises(ValueError):
                validate_source_manifests(r, foundation_manifest(r), frame_manifest(modified))

    def test_reject_reversed_and_faked_source(self):
        r = req()
        with self.assertRaises(ValueError):
            validate_source_manifests(r, frame_manifest(r), foundation_manifest(r))
        wrong = foundation_manifest(r)
        wrong["scene_id"] = "reveal"
        with self.assertRaises(ValueError):
            validate_source_manifests(r, wrong, frame_manifest(r))

    def test_auth_deploy_and_auto_dispatch_never_enabled(self):
        p = assembly_plan(req())
        self.assertFalse(p["automated_video_dispatch"])
        self.assertFalse(p["cloudflare_deployed"])
        self.assertFalse(p["paid_service_used"])

    def test_evidence_rejects_missing_short_or_reused_files(self):
        plan = assembly_plan(req())
        good1 = b"a" * 16000
        good2 = b"b" * 16000
        good3 = b"c" * 16000
        for files in (
            (good1, good1, good3),
            (good1, good2, good2),
            (b"tiny", good2, good3),
            (good1, None, good3),
            (good1, bytearray(good2), good3),
        ):
            with self.subTest(files=tuple(type(x).__name__ for x in files)):
                with self.assertRaises(ValueError):
                    verified_output_manifest(plan, *files)

    def test_evidence_contains_hashes_not_video_or_secrets(self):
        plan = assembly_plan(req())
        meta = verified_output_manifest(plan, b"a"*16000, b"b"*17000, b"c"*18000)
        self.assertTrue(meta["actual_render_verified"])
        self.assertFalse(meta["visual_quality_manually_approved"])
        self.assertEqual(meta["video_rendered_seconds"], 12)
        for key in ("foundation_mp4_sha256", "structure_mp4_sha256", "sequence_mp4_sha256"):
            self.assertRegex(meta[key], r"^[0-9a-f]{64}$")
        self.assertNotIn("token", str(meta))
        self.assertNotIn("remote", str(meta))

    def test_invalid_plan_does_not_claim_render(self):
        bad = assembly_plan(req())
        bad["sequence_frames"] = 72
        with self.assertRaises(ValueError):
            verified_output_manifest(bad, b"a"*16000, b"b"*16000, b"c"*16000)


if __name__ == "__main__":
    unittest.main()
