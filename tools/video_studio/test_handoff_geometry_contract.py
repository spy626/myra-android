"""Slice 27: negative and positive tests for actual scene-geometry comparator."""
import copy
import math
import unittest
from handoff_geometry_contract import (
    FOUNDATION_TO_STRUCTURE, STRUCTURE_TO_FACADE, require_equal_geometry
)


def geometry(pairs):
    first = {}
    second = {}
    for a, b in pairs:
        shape = {
            "location": (0.0, -1.25, 0.45),
            "dimensions": (1.4, 0.22, 0.9),
            "material_rgba": (0.29, 0.55, 0.82, 1.0),
        }
        first[a] = copy.deepcopy(shape)
        second[b] = copy.deepcopy(shape)
    return first, second


class TestSlice27Geometry(unittest.TestCase):
    def test_endpoints_have_fifteen_and_twenty_three_meshes(self):
        self.assertEqual(len(FOUNDATION_TO_STRUCTURE), 15)
        self.assertEqual(len(STRUCTURE_TO_FACADE), 23)

    def test_exact_original_meshes_accepted(self):
        for pairs in (FOUNDATION_TO_STRUCTURE, STRUCTURE_TO_FACADE):
            a, b = geometry(pairs)
            self.assertEqual(require_equal_geometry(a, b, pairs, "test"), len(pairs))

    def test_reject_extra_or_missing_mesh(self):
        pairs = FOUNDATION_TO_STRUCTURE
        a, b = geometry(pairs)
        del b[pairs[0][1]]
        with self.assertRaises(ValueError):
            require_equal_geometry(a, b, pairs, "missing")
        a, b = geometry(pairs)
        b["fake decor"] = b[pairs[0][1]]
        with self.assertRaises(ValueError):
            require_equal_geometry(a, b, pairs, "injected")

    def test_reject_footing_movement_size_and_wrong_paint(self):
        pairs = FOUNDATION_TO_STRUCTURE
        for key, replacement in (
            ("location", (-1.70, -1.32, 0.35)),
            ("dimensions", (0.55, 0.55, 0.21)),
            ("material_rgba", (0.70, 0.73, 0.72, 1.0)),
        ):
            a, b = geometry(pairs)
            b["Concrete column footing 0"][key] = replacement
            with self.subTest(property=key), self.assertRaises(ValueError):
                require_equal_geometry(a, b, pairs, "foundation_to_structure")

    def test_reject_tampered_structural_end_or_false_scene(self):
        pairs = STRUCTURE_TO_FACADE
        a, b = geometry(pairs)
        a["01 Rising ground-floor column 1"]["location"] = (8.2, 1.3, 0.5)
        with self.assertRaises(ValueError):
            require_equal_geometry(a, b, pairs, "structure_to_facade")

    def test_reject_nan_inf_bool_text_negative_dimensions(self):
        pairs = FOUNDATION_TO_STRUCTURE
        for value in (math.nan, math.inf, "1", True, 0.1):
            a, b = geometry(pairs)
            b[pairs[0][1]]["location"] = (value, -1.25, 0.45)
            with self.subTest(value=value), self.assertRaises(ValueError):
                require_equal_geometry(a, b, pairs, "invalid")
        a, b = geometry(pairs)
        b[pairs[0][1]]["dimensions"] = (1.4, -0.22, 0.9)
        with self.assertRaises(ValueError):
            require_equal_geometry(a, b, pairs, "invalid")

    def test_reject_missing_material_or_malformed_metrics(self):
        pairs = FOUNDATION_TO_STRUCTURE
        a, b = geometry(pairs)
        del b[pairs[2][1]]["material_rgba"]
        with self.assertRaises(ValueError):
            require_equal_geometry(a, b, pairs, "invalid")
        a, b = geometry(pairs)
        b[pairs[2][1]]["material_rgba"] = [0.29, 0.55, 0.82, 1.0]
        with self.assertRaises(ValueError):
            require_equal_geometry(a, b, pairs, "invalid")

    def test_reject_forged_duplicate_mapping(self):
        pairs = FOUNDATION_TO_STRUCTURE + FOUNDATION_TO_STRUCTURE[:1]
        a, b = geometry(pairs)
        with self.assertRaises(ValueError):
            require_equal_geometry(a, b, pairs, "forged")


if __name__ == "__main__":
    unittest.main()
