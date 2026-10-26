"""Slice29: bounded, local CPU-only physically based look-development contract.

This is a separate three-frame proof, NOT a completed 30s Cycles video.
Neither the user request nor the Blender source may choose external shaders,
assets, code, arbitrary lights, GPU device, URLs, or render frame budgets.
"""
from __future__ import annotations

import math

FRAMES = (16, 48, 72)
SAMPLES = 8
EXPECTED_WIDTH = 360
EXPECTED_HEIGHT = 640
LIGHTS = (
    ("LYRA Key Area", (4.4, -5.5, 8.0), 480.0, (1.0, 0.89, 0.76), 5.0),
    ("LYRA Fill Area", (-4.1, -1.8, 5.6), 310.0, (0.70, 0.84, 1.0), 5.0),
    ("LYRA Rim Area", (1.0, 5.5, 7.4), 590.0, (1.0, 0.82, 0.62), 4.0),
)
WORLD_STRENGTH = 0.40
METAL_NAMES = ("steel", "bronze", "brass", "roof", "slate", "dark roof")
GLASS_NAMES = ("glass", "window")


def material_properties(name: str) -> tuple[float, float]:
    """metallic, roughness. Do not misrepresent the opaque blue windows as glass."""
    if type(name) is not str or not name or len(name) > 128:
        raise ValueError("Invalid bounded Blender material identity")
    n = name.lower()
    if any(key in n for key in METAL_NAMES):
        return (0.58, 0.34)
    if any(key in n for key in GLASS_NAMES):
        return (0.0, 0.18)
    return (0.0, 0.65)


def require_look_plan(frames=FRAMES, samples=SAMPLES, lights=LIGHTS):
    if type(frames) is not tuple or frames != FRAMES:
        raise ValueError("Unapproved source animation frame sample")
    if type(samples) is not int or samples != SAMPLES:
        raise ValueError("Render budget must remain bounded to 8 CPU samples")
    if type(lights) is not tuple or lights != LIGHTS:
        raise ValueError("Unapproved external lighting setup")
    for name, xyz, power, color, size in lights:
        if (type(name) is not str or len(xyz) != 3 or len(color) != 3 or
            not all(type(n) in (int, float) and math.isfinite(n) for n in (*xyz, power, *color, size))
            or power <= 0 or size <= 0 or any(c <= 0 or c > 1 for c in color)):
            raise ValueError("Invalid physical area light")
    return len(frames)
