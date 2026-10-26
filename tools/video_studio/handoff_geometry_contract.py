"""Slice 27: independent exact visible geometry correspondence between two scenes.

This is intentionally not a geometry generator. Two original Blender .blend
files must independently provide the measured object transforms and materials.
A fake storyboard manifest or camera-only match cannot satisfy this gate.
"""
from __future__ import annotations
import math

# Semantic object pairs: exact physical surfaces visible across each cut.
FOUNDATION_TO_STRUCTURE = [
    ("Lot ground", "Ground grass"),
    ("Excavation soil", "Construction clearing"),
    ("Site entrance", "Paved path to structure"),
    ("Foundation trench perimeter", "Foundation trench perimeter"),
    ("Survey axis longitudinal", "Survey axis longitudinal"),
    ("Survey axis transverse", "Survey axis transverse"),
    ("01 Foundation concrete slab", "Finished concrete foundation only"),
] + [
    ("02 Column base %d" % i, "Concrete column footing %d" % i)
    for i in range(4)
] + [
    ("03 Rising rebar %d" % i, "Existing rebar %d" % i)
    for i in range(4)
]

STRUCTURE_TO_FACADE = [
    ("Ground grass", "Ground grass"),
    ("Construction clearing", "Construction clearing"),
    ("Paved path to structure", "Paved path to structure"),
    ("Finished concrete foundation only", "Finished concrete foundation only"),
] + [
    ("Concrete column footing %d" % i, "Concrete column footing %d" % i)
    for i in range(4)
] + [
    ("Existing rebar %d" % i, "Existing rebar %d" % i)
    for i in range(4)
] + [
    ("01 Rising ground-floor column %d" % i, "01 Complete column %d" % i)
    for i in range(4)
] + [
    ("02 Long top beam %d" % i, "02 Complete long beam %d" % i)
    for i in range(2)
] + [
    ("02 Side top beam %d" % i, "02 Complete side beam %d" % i)
    for i in range(2)
] + [
    ("03 %s masonry starter wall" % name, "03 Existing partial wall %s" % name)
    for name in ("Back", "Left", "Right")
]

METRIC_FIELDS = frozenset(("location", "dimensions", "material_rgba"))
TOLERANCE = 0.0002


def require_equal_geometry(first: dict, second: dict, pairs: list, label: str) -> int:
    """Reject missing, extra, hidden, fake or physically discontinuous surfaces."""
    if type(first) is not dict or type(second) is not dict or not pairs:
        raise ValueError("Missing authentic visible scene geometry")
    names_a = [a for a, _ in pairs]
    names_b = [b for _, b in pairs]
    if (len(set(names_a)) != len(pairs) or len(set(names_b)) != len(pairs) or
        set(first) != set(names_a) or set(second) != set(names_b)):
        raise ValueError("Wrong visible mesh inventory at " + label)
    for left, right in pairs:
        a, b = first[left], second[right]
        if (type(a) is not dict or type(b) is not dict or
            set(a) != METRIC_FIELDS or set(b) != METRIC_FIELDS):
            raise ValueError("Incomplete Blender geometry metric at " + label)
        for field in METRIC_FIELDS:
            aa, bb = a[field], b[field]
            size = 4 if field == "material_rgba" else 3
            if (type(aa) is not tuple or type(bb) is not tuple or
                len(aa) != size or len(bb) != size):
                raise ValueError("Wrong geometry component count")
            for x, y in zip(aa, bb):
                if (type(x) not in (int, float) or type(y) not in (int, float)
                    or not math.isfinite(x) or not math.isfinite(y)
                    or abs(x - y) > TOLERANCE):
                    raise ValueError("Geometry cut jump at %s / %s / %s" %
                                     (label, left, field))
            if field == "dimensions" and any(x <= 0 for x in aa + bb):
                raise ValueError("Nonpositive real mesh dimension")
    return len(pairs)
