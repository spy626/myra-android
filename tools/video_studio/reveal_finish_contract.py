"""Slice28: strictly offline architectural finish plan; no AI-generated assets.

All Blender meshes are deterministic, generated locally, and introduced only
after frame one to preserve the verified previous facade -> reveal cut.
No garden, extra floors, arbitrary user-provided paths or downloaded models.
"""
from __future__ import annotations
import math

FINISH_COLORS = {
    "ivory": (0.92, 0.88, 0.76),
    "bronze": (0.56, 0.39, 0.19),
    "charcoal": (0.20, 0.27, 0.31),
    "slate": (0.33, 0.42, 0.46),
    "glow": (0.99, 0.73, 0.39),
    "paver": (0.85, 0.78, 0.64),
}
# id, center, dimensions, material label, frame_start, frame_done, grow_axis
FINISH_PARTS = (
    ("West roof fascia", (-1.15, -1.73, 2.93), (2.02, 0.10, 0.08), "slate", 8, 27, 0),
    ("East roof fascia", (1.15, -1.73, 2.93), (2.02, 0.10, 0.08), "slate", 11, 30, 0),
    ("Entry porch canopy", (0.0, -1.83, 2.40), (1.58, 0.71, 0.13), "charcoal", 16, 36, 1),
    ("Canopy front lip", (0.0, -2.21, 2.35), (1.70, 0.10, 0.11), "ivory", 24, 42, 0),
    ("Entry frame left", (-0.50, -1.59, 1.14), (0.09, 0.11, 1.80), "ivory", 18, 42, 2),
    ("Entry frame right", (0.50, -1.59, 1.14), (0.09, 0.11, 1.80), "ivory", 21, 44, 2),
    ("Brass door handle", (0.30, -1.58, 1.10), (0.10, 0.075, 0.21), "bronze", 32, 48, 2),
    ("Window vertical mullion", (1.22, -1.56, 1.48), (0.055, 0.08, 0.71), "ivory", 27, 51, 2),
    ("Window horizontal mullion", (1.22, -1.57, 1.48), (0.59, 0.085, 0.055), "ivory", 31, 52, 0),
    ("Porch lantern left", (-0.73, -1.61, 1.87), (0.17, 0.12, 0.23), "glow", 38, 57, 2),
    ("Porch lantern right", (0.73, -1.61, 1.87), (0.17, 0.12, 0.23), "glow", 40, 59, 2),
    ("Front corner trim west", (-1.64, -1.56, 1.39), (0.10, 0.10, 1.88), "ivory", 42, 62, 2),
    ("Front corner trim east", (1.64, -1.56, 1.39), (0.10, 0.10, 1.88), "ivory", 44, 64, 2),
    ("Front stepping stone one", (0.0, -2.18, -0.015), (0.88, 0.29, 0.08), "paver", 46, 62, 0),
    ("Front stepping stone two", (0.0, -2.67, -0.015), (0.88, 0.29, 0.08), "paver", 48, 65, 0),
    ("Front stepping stone three", (0.0, -3.13, -0.015), (0.88, 0.29, 0.08), "paver", 50, 67, 0),
    ("Address name plaque", (-0.73, -1.58, 1.77), (0.35, 0.09, 0.17), "bronze", 52, 68, 0),
    ("Roof line cap west", (-0.64, 0.0, 3.27), (0.06, 3.44, 0.06), "slate", 54, 70, 1),
    ("Roof line cap east", (0.64, 0.0, 3.27), (0.06, 3.44, 0.06), "slate", 56, 72, 1),
)
PREFIX = "07 Architectural "


def approved_detail_plan(parts=FINISH_PARTS):
    """Fail closed on any extra, swapped, missing or malformed design part."""
    if type(parts) is not tuple or len(parts) != 19:
        raise ValueError("Only 19 original finish meshes are approved")
    if parts != FINISH_PARTS:
        raise ValueError("Unapproved offline Blender finish plan")
    labels = set()
    for item in parts:
        if type(item) is not tuple or len(item) != 7:
            raise ValueError("Invalid mesh tuple")
        label, xyz, dimensions, material, begin, finish, axis = item
        if (type(label) is not str or not label or label in labels or
            material not in FINISH_COLORS or type(begin) is not int or
            type(finish) is not int or type(axis) is not int or
            not (2 <= begin < finish <= 72) or axis not in (0, 1, 2)):
            raise ValueError("Invalid architectural schedule")
        labels.add(label)
        for vec in (xyz, dimensions):
            if (type(vec) is not tuple or len(vec) != 3 or
                any(type(n) not in (float, int) or not math.isfinite(n) for n in vec)):
                raise ValueError("Nonfinite fixed mesh dimensions")
        if any(x <= 0 for x in dimensions):
            raise ValueError("Invalid mesh dimensions")
    return len(labels)
