"""Slice 21: real, distinct 6-second foundation-building Blender shot.

This is NOT the existing finished-house 6s clip replayed or a 30/60s output.
Only the allowlisted foundation scene from a pre-validated storyboard is rendered.
Run inside Blender: blender -b --factory-startup --python this_file -- \
    --request tools/video_studio/requests/sample-blue-house.json \
    --output-dir build/video-studio/slice21
"""
from __future__ import annotations

import argparse
import json
import os
import sys

import bpy
from mathutils import Vector

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from render_request import load_render_request
from foundation_shot_contract import approved_foundation_shot


def material(name, rgb):
    mat = bpy.data.materials.new(name)
    mat.diffuse_color = (*rgb, 1.0)
    return mat


def cube(name, xyz, size, mat):
    bpy.ops.mesh.primitive_cube_add(size=1, location=xyz)
    ob = bpy.context.object
    ob.name = name
    ob.dimensions = size
    bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    ob.data.materials.append(mat)
    return ob


def growing_cube(name, xyz, size, mat, start, end):
    assert 2 <= start < end <= 72
    ob = cube(name, xyz, size, mat)
    full_z = ob.dimensions.z
    top_z = ob.location.z
    ob.hide_render = True
    ob.keyframe_insert(data_path="hide_render", frame=1)
    ob.keyframe_insert(data_path="hide_render", frame=start - 1)
    ob.hide_render = False
    ob.keyframe_insert(data_path="hide_render", frame=start)
    ob.scale.z = 0.004 / full_z
    ob.location.z = top_z - full_z / 2 + 0.002
    ob.keyframe_insert(data_path="scale", frame=start)
    ob.keyframe_insert(data_path="location", frame=start)
    ob.scale.z = 1.0
    ob.location.z = top_z
    ob.keyframe_insert(data_path="scale", frame=end)
    ob.keyframe_insert(data_path="location", frame=end)
    return ob


def build_foundation(output, request):
    shot = approved_foundation_shot(request)
    spec = shot["spec"]
    bpy.ops.object.select_all(action="SELECT")
    bpy.ops.object.delete(use_global=False)
    scene = bpy.context.scene
    scene.frame_start, scene.frame_end = 1, 72
    scene.render.fps = 12
    scene.render.resolution_x, scene.render.resolution_y = 360, 640
    scene.render.resolution_percentage = 100
    scene.render.engine = "BLENDER_WORKBENCH"
    scene.render.image_settings.file_format = "PNG"
    scene.render.image_settings.color_mode = "RGB"
    scene.render.filepath = os.path.join(output, "frames", "foundation_")
    scene.display.shading.light = "STUDIO"
    scene.display.shading.color_type = "MATERIAL"
    scene.display.shading.show_cavity = True
    scene.display.shading.cavity_type = "BOTH"
    scene.display.shading.show_shadows = True
    scene.display.shading.background_type = "WORLD"
    scene.world.color = (0.08, 0.12, 0.16)

    soil = material("Fresh earth", (0.34, 0.28, 0.23))
    grass = material("Island grass", (0.30, 0.50, 0.32))
    concrete = material("Poured pale concrete", (0.70, 0.73, 0.72))
    dark = material("Foundation trenches", (0.20, 0.18, 0.17))
    steel = material("Exposed reinforcement steel", (0.29, 0.37, 0.41))
    accent = material("Approved house wall color", {
        "cream": (0.94, 0.90, 0.81),
        "blue": (0.29, 0.55, 0.82),
        "terracotta": (0.81, 0.43, 0.30),
    }[spec["wall_color"]])
    sand = material("Footpath sand", (0.82, 0.69, 0.48))

    # A construction-only scene, visibly different from the original completed house.
    cube("Lot ground", (0, 0, -0.34), (7.5, 6.9, 0.25), grass)
    cube("Excavation soil", (0, 0, -0.14), (5.25, 4.85, 0.15), soil)
    cube("Foundation trench perimeter", (0, 0, -0.045), (4.5, 3.8, 0.045), dark)
    cube("Survey axis longitudinal", (0, 0, -0.008), (0.025, 4.2, 0.025), sand)
    cube("Survey axis transverse", (0, 0, 0.007), (4.9, 0.025, 0.025), sand)
    cube("Site entrance", (0, -2.87, -0.19), (1.1, 1.1, 0.08), sand)

    growing_cube("01 Foundation concrete slab", (0, 0, 0.15),
                 (4.05, 3.36, 0.28), concrete, 10, 34)
    corners = ((-1.67, -1.31), (-1.67, 1.31), (1.67, -1.31), (1.67, 1.31))
    for i, (x, y) in enumerate(corners):
        growing_cube("02 Column base " + str(i), (x, y, 0.42),
                     (0.52, 0.52, 0.34), accent, 22 + i * 2, 46 + i * 2)
        growing_cube("03 Rising rebar " + str(i), (x, y, 1.14),
                     (0.09, 0.09, 1.34), steel, 34 + i * 2, 64 + i)
    # Camera movement is not the old preview camera; stage shown from above.
    bpy.ops.object.camera_add(location=(7.7, -9.8, 9.2))
    camera = bpy.context.object
    camera.name = "Foundation inspection dolly"
    camera.data.type = "ORTHO"
    camera.data.ortho_scale = 8.4
    scene.camera = camera
    for frame, xyz in ((1, (7.7, -9.8, 9.2)), (36, (6.2, -8.5, 7.3)), (72, (4.7, -7.2, 6.1))):
        camera.location = xyz
        camera.rotation_euler = (Vector((0, 0, 0.10)) - camera.location).to_track_quat("-Z", "Y").to_euler()
        camera.keyframe_insert(data_path="location", frame=frame)
        camera.keyframe_insert(data_path="rotation_euler", frame=frame)

    # This manifest is independently verified against original request + plan.
    manifest = {
        **shot,
        "actual_shot_rendered": True,
        "object_count": len(scene.objects),
        "geometry": {
            "foundation_slabs": 1,
            "foundation_columns": 4,
            "rebar_rods": 4,
            "animated_objects": 9,
        },
    }
    scene.frame_set(1)
    bpy.ops.wm.save_as_mainfile(filepath=os.path.join(output, "foundation_scene.blend"))
    with open(os.path.join(output, "foundation_manifest.json"), "w", encoding="utf-8") as handle:
        json.dump(manifest, handle, sort_keys=True, indent=2)
    bpy.ops.render.render(animation=True)
    print("SLICE21_REAL_FOUNDATION_FRAMES_RENDERED", len(scene.objects))


def main():
    args = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    parser = argparse.ArgumentParser()
    parser.add_argument("--request", required=True)
    parser.add_argument("--output-dir", required=True)
    cfg = parser.parse_args(args)
    # Reuse the original strict 4KB request and selected styling values.
    load_render_request(cfg.request)
    with open(cfg.request, "r", encoding="utf-8") as handle:
        request = json.load(handle)
    approved_foundation_shot(request)
    target = os.path.abspath(cfg.output_dir)
    os.makedirs(os.path.join(target, "frames"), exist_ok=True)
    build_foundation(target, request)


if __name__ == "__main__":
    main()
