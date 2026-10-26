"""Regression tests for CPU Cycles look-development constraints."""
import unittest
from physical_look_contract import (
    FRAMES, SAMPLES, LIGHTS, material_properties, require_look_plan
)


class PhysicalLookContractTest(unittest.TestCase):
    def test_small_original_three_frame_budget(self):
        self.assertEqual(require_look_plan(), 3)
        self.assertEqual(FRAMES, (16, 48, 72))
        self.assertEqual(SAMPLES, 8)
        self.assertEqual(len(LIGHTS), 3)

    def test_noncanonical_frames_and_big_cpu_budgets_rejected(self):
        for f in ((1, 48, 72), tuple(range(1, 73)), (16, 72), [16, 48, 72], (72, 48, 16)):
            with self.subTest(frame=f), self.assertRaises(ValueError):
                require_look_plan(frames=f)
        for samples in (1, 12, 72, 2048, 8.0, True, "8"):
            with self.subTest(samples=samples), self.assertRaises(ValueError):
                require_look_plan(samples=samples)

    def test_external_or_modified_lights_rejected(self):
        with self.assertRaises(ValueError):
            require_look_plan(lights=LIGHTS[:-1])
        bad = list(LIGHTS)
        bad[0] = (bad[0][0], bad[0][1], 999999, bad[0][3], bad[0][4])
        with self.assertRaises(ValueError):
            require_look_plan(lights=tuple(bad))

    def test_pbr_material_classification_deterministic(self):
        self.assertEqual(material_properties("Architectural bronze"), (0.58, 0.34))
        self.assertEqual(material_properties("Roof slate"), (0.58, 0.34))
        self.assertEqual(material_properties("Windows"), (0.0, 0.18))
        self.assertEqual(material_properties("Requested house paint"), (0.0, 0.65))
        self.assertEqual(material_properties("Wood Entry"), (0.0, 0.65))

    def test_malformed_material_identity_rejected(self):
        for name in ("", None, 1, "A"*129):
            with self.subTest(name=name), self.assertRaises(ValueError):
                material_properties(name)


if __name__ == "__main__":
    unittest.main()
