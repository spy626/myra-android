"""Read actual saved Blender scenes and audit 38 visible boundary mesh pairs.

Camera-only cuts or metadata cannot impersonate matching foundation,
structure and facade geometry. Two adjacent physical scene seams checked.
"""
from __future__ import annotations
import argparse
import json
import os
import sys
from pathlib import Path

import bpy

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from handoff_geometry_contract import (
    FOUNDATION_TO_STRUCTURE, STRUCTURE_TO_FACADE, require_equal_geometry
)


def actual_blend_geometry(path: Path, frame: int) -> dict:
    if path.is_symlink() or not path.is_file() or path.stat().st_size < 2000:
        raise ValueError("Missing genuine Blender project")
    bpy.ops.wm.open_mainfile(filepath=str(path.resolve()))
    scene = bpy.context.scene
    if (scene.frame_start, scene.frame_end, scene.render.fps,
        scene.render.resolution_x, scene.render.resolution_y) != (1, 72, 12, 360, 640):
        raise ValueError("Wrong original storyboard scene settings")
    scene.frame_set(frame)
    measured = {}
    for ob in scene.objects:
        if ob.type != "MESH" or ob.hide_render:
            continue
        material = ob.active_material
        if material is None:
            raise ValueError("Visible 3D construction part without material")
        measured[ob.name] = {
            "location": tuple(float(i) for i in ob.location),
            "dimensions": tuple(float(i) for i in ob.dimensions),
            "material_rgba": tuple(float(i) for i in material.diffuse_color),
        }
    return measured


def audit(foundation: Path, structure: Path, facade: Path) -> dict:
    cut1_a = actual_blend_geometry(foundation, 72)
    cut1_b = actual_blend_geometry(structure, 1)
    cut2_a = actual_blend_geometry(structure, 72)
    cut2_b = actual_blend_geometry(facade, 1)
    counts = {
        "foundation_to_structure_matched_objects": require_equal_geometry(
            cut1_a, cut1_b, FOUNDATION_TO_STRUCTURE, "foundation_to_structure"
        ),
        "structure_to_facade_matched_objects": require_equal_geometry(
            cut2_a, cut2_b, STRUCTURE_TO_FACADE, "structure_to_facade"
        ),
    }
    return {
        "version": 1, "slice": 27, **counts,
        "total_visible_boundary_pairs": sum(counts.values()),
        "real_blender_geometry_verified": True,
        "visual_quality_manually_approved": False,
        "video_submitted_from_android": False,
    }


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--foundation", type=Path, required=True)
    p.add_argument("--structure", type=Path, required=True)
    p.add_argument("--facade", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    opts = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    a = p.parse_args(opts)
    if a.output.exists() or a.output.is_symlink():
        raise ValueError("Refusing to replace existing Blender geometry evidence")
    report = audit(a.foundation, a.structure, a.facade)
    a.output.parent.mkdir(parents=True, exist_ok=True)
    a.output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print("SLICE27_REAL_BLENDER_GEOMETRY_HANDOFF_PASS", report)


if __name__ == "__main__":
    main()
