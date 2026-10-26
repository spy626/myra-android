"""Slice 28 pure fail-closed 3D presentation-plan regression tests."""
import math
import unittest
from reveal_finish_contract import (
    FINISH_COLORS, FINISH_PARTS, PREFIX, approved_detail_plan
)


class RevealDetailPlanTests(unittest.TestCase):
    def test_nineteen_unique_local_blender_components(self):
        self.assertEqual(approved_detail_plan(), 19)
        self.assertEqual(len({p[0] for p in FINISH_PARTS}), 19)
        self.assertEqual(len(FINISH_COLORS), 6)
        self.assertTrue(PREFIX.startswith("07 "))
        self.assertTrue(all("http" not in p[0].lower() for p in FINISH_PARTS))

    def test_every_new_object_appears_only_after_previous_cut(self):
        self.assertTrue(all(2 <= p[4] < p[5] <= 72 for p in FINISH_PARTS))
        self.assertTrue(all(p[4] >= 8 for p in FINISH_PARTS))
        self.assertTrue(all(p[6] in (0, 1, 2) for p in FINISH_PARTS))

    def test_approved_material_colors_are_bounded(self):
        for color in FINISH_COLORS.values():
            self.assertEqual(len(color), 3)
            self.assertTrue(all(type(channel) is float and math.isfinite(channel)
                                and 0 <= channel <= 1 for channel in color))

    def test_missing_reordered_replaced_and_injected_parts_rejected(self):
        for wrong in (
            FINISH_PARTS[:-1],
            tuple(reversed(FINISH_PARTS)),
            FINISH_PARTS + FINISH_PARTS[:1],
            tuple(list(FINISH_PARTS[:-1]) + [("malicious", (0, 0, 0), (1, 1, 1),
                                                "glow", 8, 72, 0)])
        ):
            with self.subTest(size=len(wrong)), self.assertRaises(ValueError):
                approved_detail_plan(wrong)

    def test_malformed_container_and_spoofed_parts_rejected(self):
        for wrong in (None, [], list(FINISH_PARTS), (), "19"):
            with self.subTest(value=type(wrong).__name__), self.assertRaises(ValueError):
                approved_detail_plan(wrong)

    def test_flat_and_gable_roof_choice_do_not_inject_user_meshes(self):
        self.assertEqual(
            [part[0] for part in FINISH_PARTS if part[0].startswith("Roof line cap")],
            ["Roof line cap west", "Roof line cap east"]
        )
        self.assertTrue(all(part[3] in FINISH_COLORS for part in FINISH_PARTS))


if __name__ == "__main__":
    unittest.main()
