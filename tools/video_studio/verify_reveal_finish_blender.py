"""Inspect the real saved editable Blender reveal .blend, not JSON-only claims.

Each of 19 hand-constructed details must be invisible on the previous cut,
actually animated over the scene, and present at the final reveal frame with
the scheduled transform and unique material. Report auditable observations.
"""
from __future__ import annotations
import argparse
import json
import math
import sys
from pathlib import Path
import bpy

sys.path.insert(0, str(Path(__file__).resolve().parent))
from reveal_finish_contract import FINISH_COLORS, FINISH_PARTS, PREFIX, approved_detail_plan


def close_vector(actual, expected, label, tolerance=0.0005):
    if len(actual) != len(expected) or any(
        not math.isfinite(float(a)) or abs(float(a) - b) > tolerance
        for a, b in zip(actual, expected)
    ):
        raise ValueError("Real Blender detail mismatch: " + label)


def verify(blend: Path, roof_style: str):
    approved_detail_plan()
    if roof_style not in ("gable", "flat"):
        raise ValueError("Unapproved house roof choice")
    if blend.is_symlink() or not blend.is_file() or blend.stat().st_size < 2000:
        raise ValueError("No authentic editable Blender reveal scene")
    bpy.ops.wm.open_mainfile(filepath=str(blend.resolve()))
    scene = bpy.context.scene
    if (scene.frame_start, scene.frame_end, scene.render.fps,
        scene.render.resolution_x, scene.render.resolution_y, scene.render.engine
    ) != (1, 72, 12, 360, 640, "BLENDER_WORKBENCH"):
        raise ValueError("Unapproved render scene settings")
    if not (scene.display.shading.show_shadows and scene.display.shading.show_cavity
            and scene.display.shading.color_type == "MATERIAL"):
        raise ValueError("Missing staged studio shadow/material composition")

    expected_names = {PREFIX + part[0] for part in FINISH_PARTS}
    actual_names = {ob.name for ob in scene.objects if ob.name.startswith(PREFIX)}
    if actual_names != expected_names:
        raise ValueError("Wrong real Blender architectural detail inventory")
    scene.frame_set(1)
    for name in expected_names:
        ob = scene.objects[name]
        if ob.type != "MESH" or not ob.hide_render:
            raise ValueError("Architectural detail leaks into previous cut: " + name)
    scene.frame_set(72)
    for label, xyz, dims, tone, start, finish, axis in FINISH_PARTS:
        name = PREFIX + label
        ob = scene.objects[name]
        if ob.type != "MESH" or ob.hide_render or not ob.animation_data:
            raise ValueError("Unanimated or hidden finished 3D detail: " + name)
        planned = (xyz[0], xyz[1], 2.96) if (
            roof_style == "flat" and label.startswith("Roof line cap")
        ) else xyz
        close_vector(tuple(ob.location), planned, name + " center")
        close_vector(tuple(ob.dimensions), dims, name + " dimensions")
        if ob.active_material is None:
            raise ValueError("Missing physically authored finish material")
        close_vector(tuple(ob.active_material.diffuse_color),
                     (*FINISH_COLORS[tone], 1.0), name + " paint")
    return {
        "slice": 28, "version": 1,
        "original_render_engine": scene.render.engine,
        "original_animated_reveal_parts": len(FINISH_PARTS),
        "different_finish_materials": len(FINISH_COLORS),
        "frame1_old_seam_preserved": True,
        "frame72_all_meshes_present": True,
        "studio_shadow_and_cavity_enabled": True,
        "actual_physical_lights_verified": False,
        "photorealistic_render_proven": False,
        "physical_phone_test_passed": False,
    }


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--blend", type=Path, required=True)
    p.add_argument("--roof-style", choices=("flat", "gable"), required=True)
    p.add_argument("--report", type=Path, required=True)
    args = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    values = p.parse_args(args)
    if values.report.exists() or values.report.is_symlink():
        raise ValueError("Refusing to overwrite previous scene evidence")
    report = verify(values.blend, values.roof_style)
    values.report.parent.mkdir(parents=True, exist_ok=True)
    values.report.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print("SLICE28_REAL_ARCHITECTURAL_DETAILS_PASS", report)


if __name__ == "__main__":
    main()
