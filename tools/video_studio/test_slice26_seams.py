"""Slice26 camera-cut quality gate adversarial tests, no Blender needed."""
import math
import unittest

from verify_slice26_seams import (
    BOUNDARIES, MAX_RGB_MAE, pixel_mae, require_seam_quality
)


def good():
    return {name: 4.0 for name, _, _ in BOUNDARIES}


class TestSlice26Seams(unittest.TestCase):
    def test_exact_four_timeline_cuts(self):
        self.assertEqual(tuple((a, b) for _, a, b in BOUNDARIES),
                         ((71, 72), (143, 144), (215, 216), (287, 288)))
        self.assertEqual(MAX_RGB_MAE, 12.0)

    def test_raw_pixel_average_is_not_file_byte_hash(self):
        self.assertEqual(pixel_mae(bytes((0, 30, 60)), bytes((30, 10, 60))),
                         (30 + 20 + 0) / 3)

    def test_reject_missing_wrong_size_or_empty_buffers(self):
        for a, b in ((b"", b""), (b"\x01", b""), (b"\x01", b"\x01\x02")):
            with self.assertRaises(ValueError):
                pixel_mae(a, b)

    def test_accept_bounded_seam_scores(self):
        require_seam_quality(good())
        x = good()
        x["foundation_to_structure"] = MAX_RGB_MAE
        require_seam_quality(x)

    def test_reject_missing_extra_or_forged_stage(self):
        x = good()
        del x["structure_to_facade"]
        with self.assertRaises(ValueError):
            require_seam_quality(x)
        x = good()
        x["injected_cut"] = 0.0
        with self.assertRaises(ValueError):
            require_seam_quality(x)

    def test_reject_unbounded_or_nonfinite_seam(self):
        for value in (12.001, -0.1, math.inf, -math.inf, math.nan, "0", None, True):
            x = good()
            x["foundation_to_structure"] = value
            with self.subTest(value=value), self.assertRaises(ValueError):
                require_seam_quality(x)


if __name__ == "__main__":
    unittest.main()
