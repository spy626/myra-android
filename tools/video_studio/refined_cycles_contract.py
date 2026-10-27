"""Slice30: reusable, strict render-quality profile for original CPU Cycles scenes.

This profile is standalone and portable to other real storyboard Blender
scenes in later slices, but Slice30 renders only three existing reveal frames.
"""
from __future__ import annotations
from physical_look_contract import FRAMES, SAMPLES, require_look_plan

REFINED_SAMPLES = 32
REFINED_FRAMES = FRAMES
DENOISING_ENABLED = True
DENOISING_BACKEND = "FFMPEG_NLMEANS_CPU"
DENOISING_FILTER = "nlmeans=s=2.5:p=3:pc=3:r=5:rc=5"
TARGET_ENGINE = "CYCLES"
TARGET_DEVICE = "CPU"


def require_refined_plan(frames=REFINED_FRAMES, samples=REFINED_SAMPLES,
                         denoising=DENOISING_ENABLED, backend=DENOISING_BACKEND,
                         device=TARGET_DEVICE):
    require_look_plan()
    if type(frames) is not tuple or frames != REFINED_FRAMES:
        raise ValueError("Refined look requires the same three original frames")
    if type(samples) is not int or samples != REFINED_SAMPLES:
        raise ValueError("Refined samples must be the bounded 4x baseline")
    if type(denoising) is not bool or denoising is not True:
        raise ValueError("True Cycles denoising must be enabled")
    if type(backend) is not str or backend != DENOISING_BACKEND:
        raise ValueError("Only the fixed CPU NLMeans post-processing gate is approved")
    if type(device) is not str or device != TARGET_DEVICE:
        raise ValueError("Only offline local CPU rendering is allowed")
    if REFINED_SAMPLES <= SAMPLES:
        raise ValueError("Refined preview cannot use baseline samples")
    return {
        "render_engine": TARGET_ENGINE,
        "device": TARGET_DEVICE,
        "refined_samples": REFINED_SAMPLES,
        "baseline_samples": SAMPLES,
        "denoising_enabled": DENOISING_ENABLED,
        "denoising_backend": DENOISING_BACKEND,
        "denoising_filter": DENOISING_FILTER,
        "blender_native_denoising": False,
        "original_frames": list(REFINED_FRAMES),
        "full_video_rendered": False,
    }
