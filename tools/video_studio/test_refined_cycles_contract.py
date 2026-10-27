"""Negative tests for bounded path-tracing/denoiser settings."""
import unittest
from refined_cycles_contract import (
    DENOISING_ENABLED, REFINED_FRAMES, REFINED_SAMPLES, require_refined_plan
)


class RefinedCyclesContractTests(unittest.TestCase):
    def test_reuse_original_timeline_only(self):
        result = require_refined_plan()
        self.assertEqual(result["original_frames"], [16, 48, 72])
        self.assertEqual(result["baseline_samples"], 8)
        self.assertEqual(result["refined_samples"], 32)
        self.assertTrue(result["denoising_enabled"])
        self.assertFalse(result["full_video_rendered"])

    def test_reject_scaled_up_frame_count_and_reordering(self):
        for frames in (tuple(range(1, 73)), (16, 72), (72, 48, 16), [16, 48, 72], (), None):
            with self.subTest(frames=frames), self.assertRaises(ValueError):
                require_refined_plan(frames=frames)

    def test_reject_gpu_renderers_and_extra_samples(self):
        for device in ("GPU", "OPTIX", "", None, 1):
            with self.subTest(device=device), self.assertRaises(ValueError):
                require_refined_plan(device=device)
        for samples in (8, 16, 24, 64, 4096, 32.0, True, "32"):
            with self.subTest(samples=samples), self.assertRaises(ValueError):
                require_refined_plan(samples=samples)

    def test_reject_missing_or_forged_denoising(self):
        for flag in (False, 0, 1, None, "true"):
            with self.subTest(denoising=flag), self.assertRaises(ValueError):
                require_refined_plan(denoising=flag)


if __name__ == "__main__":
    unittest.main()
