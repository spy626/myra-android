"""Slice 24: one genuine third-stage Blender shot: walls -> roof -> facade.

New geometry picks up the existing Slice22 structural frame end-state;
no reuse of finished-house 6-second output and no browser/cloud/AI render.
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
from facade_shot_contract import approved_facade_shot
from render_structure_shot_blender import mat, block, grow_block


def build(output: str, request: dict) -> None:
    claim = approved_facade_shot(request)
    spec = claim["spec"]
    bpy.ops.object.select_all(action="SELECT")
    bpy.ops.object.delete(use_global=False)
    scene = bpy.context.scene
    scene.frame_start = 1
    scene.frame_end = 72
    scene.render.fps = 12
    scene.render.resolution_x, scene.render.resolution_y = 360, 640
    scene.render.resolution_percentage = 100
    scene.render.engine = "BLENDER_WORKBENCH"
    scene.render.image_settings.file_format = "PNG"
    scene.render.image_settings.color_mode = "RGB"
    scene.render.filepath = os.path.join(output, "frames", "facade_")
    scene.display.shading.light = "STUDIO"
    scene.display.shading.color_type = "MATERIAL"
    scene.display.shading.show_cavity = True
    scene.display.shading.cavity_type = "BOTH"
    scene.display.shading.show_shadows = True
    scene.display.shading.background_type = "WORLD"
    scene.world.color = (0.08, 0.12, 0.16)

    lawn = mat("Island grass", (0.30, 0.50, 0.32))
    soil = mat("Earth", (0.34, 0.28, 0.23))
    concrete = mat("Pale concrete", (0.70, 0.73, 0.72))
    steel = mat("Dark reinforcing steel", (0.29, 0.37, 0.41))
    wall = mat("Requested wall finish", {
        "cream": (0.94, 0.90, 0.81),
        "blue": (0.29, 0.55, 0.82),
        "terracotta": (0.81, 0.43, 0.30),
    }[spec["wall_color"]])
    roof = mat("Dark roof", (0.22, 0.33, 0.39))
    glass = mat("Blue windows", (0.18, 0.57, 0.71))
    wood = mat("Entry door", (0.55, 0.32, 0.20))
    paving = mat("Site path", (0.80, 0.70, 0.52))

    # Static structure copies the final geometry/layout of Slice22's one-floor demo.
    block("Ground grass", (0, 0, -0.36), (7.5, 6.9, 0.25), lawn)
    block("Construction clearing", (0, 0, -0.18), (5.25, 4.85, 0.10), soil)
    block("Paved path to structure", (0, -2.82, -0.15), (1.1, 1.1, 0.08), paving)
    block("Finished concrete foundation only", (0, 0, 0.12), (4.05, 3.36, 0.26), concrete)

    corners = ((-1.70, -1.32), (-1.70, 1.32), (1.70, -1.32), (1.70, 1.32))
    for i, (x, y) in enumerate(corners):
        block("Concrete column footing %d" % i, (x, y, 0.35), (0.55, 0.55, 0.21), concrete)
        block("Existing rebar %d" % i, (x, y, 0.62), (0.085, 0.085, 0.44), steel)
        block("01 Complete column %d" % i, (x, y, 1.37), (0.24, 0.24, 2.25), concrete)

    for i, y in enumerate((-1.32, 1.32)):
        block("02 Complete long beam %d" % i, (0, y, 2.55), (3.62, 0.27, 0.23), concrete)
    for i, x in enumerate((-1.70, 1.70)):
        block("02 Complete side beam %d" % i, (x, 0, 2.55), (0.27, 2.88, 0.23), concrete)

    partials = (
        ("Back", (0, 1.33, 0.81), (3.19, 0.15, 1.15)),
        ("Left", (-1.70, 0, 0.75), (0.15, 2.48, 1.03)),
        ("Right", (1.70, 0, 0.75), (0.15, 2.48, 1.03)),
    )
    for name, xyz, dim in partials:
        block("03 Existing partial wall " + name, xyz, dim, wall)
    upper = (
        ("Back", (0, 1.33, 1.84), (3.19, 0.15, 0.86)),
        ("Left", (-1.70, 0, 1.74), (0.15, 2.48, 1.02)),
        ("Right", (1.70, 0, 1.74), (0.15, 2.48, 1.02)),
    )
    for i, (name, xyz, dim) in enumerate(upper):
        grow_block("04 Growing upper wall " + name, xyz, dim, wall,
                   6 + i * 3, 35 + i * 4)

    if spec["roof_style"] == "gable":
        for i, (name, sign) in enumerate((("Left", -1), ("Right", 1))):
            panel = grow_block("05 Gable roof " + name,
                               (sign * 1.03, 0, 3.02),
                               (2.26, 3.52, 0.17), roof,
                               35 + i * 5, 61 + i * 5, axis=1)
            panel.rotation_euler.y = sign * 0.33
    else:
        grow_block("05 Flat roof slab", (0, 0, 2.79),
                   (4.42, 3.65, 0.19), roof, 35, 62, axis=1)

    # Four new facade details, not yet a landscaping/full reveal.
    grow_block("06 Facade left panel", (-1.22, -1.34, 1.29),
               (0.77, 0.17, 1.86), wall, 44, 63)
    grow_block("06 Facade right panel", (1.22, -1.34, 1.29),
               (0.77, 0.17, 1.86), wall, 47, 65)
    grow_block("06 Entry door", (0, -1.48, 1.09),
               (0.90, 0.08, 1.84), wood, 49, 69)
    grow_block("06 Front window", (1.22, -1.48, 1.48),
               (0.58, 0.09, 0.70), glass, 54, 72)

    bpy.ops.object.camera_add(location=(5.1, -8.8, 5.8))
    camera = bpy.context.object
    camera.name = "Facade finishing track camera"
    camera.data.type = "ORTHO"
    camera.data.ortho_scale = 8.2
    scene.camera = camera
    for frame, xyz in ((1, (5.1, -8.8, 5.8)), (36, (4.0, -8.1, 5.4)), (72, (3.0, -7.8, 5.1))):
        camera.location = xyz
        camera.rotation_euler = (
            Vector((0, 0, 1.18)) - camera.location
        ).to_track_quat("-Z", "Y").to_euler()
        camera.keyframe_insert(data_path="location", frame=frame)
        camera.keyframe_insert(data_path="rotation_euler", frame=frame)

    def count(prefix):
        return sum(o.name.startswith(prefix) for o in scene.objects)

    geo = {
        "existing_columns": count("01 Complete column"),
        "existing_beams": count("02 Complete"),
        "existing_partial_walls": count("03 Existing partial wall"),
        "animated_upper_walls": count("04 Growing upper wall"),
        "animated_roof_panels": count("05 "),
        "animated_front_details": count("06 "),
        "unfinished_house_not_full_reveal": (
            not any("garden finish" in o.name.lower() or "final reveal" in o.name.lower()
                    for o in scene.objects)
        ),
    }
    if not all(o.animation_data for o in scene.objects
               if o.name.startswith(("04 ", "05 ", "06 "))):
        raise RuntimeError("Facade geometry is not actually animated")
    data = {
        **claim,
        "actual_shot_rendered": True,
        "object_count": len(scene.objects),
        "geometry": geo,
    }
    scene.frame_set(1)
    bpy.ops.wm.save_as_mainfile(filepath=os.path.join(output, "facade_scene.blend"))
    bpy.ops.render.render(animation=True)
    with open(os.path.join(output, "facade_manifest.json"), "w", encoding="utf-8") as file:
        json.dump(data, file, indent=2, sort_keys=True)
    print("SLICE24_REAL_FACADE_RENDER_COMPLETE", geo)


def main():
    opts = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    p = argparse.ArgumentParser()
    p.add_argument("--request", required=True)
    p.add_argument("--output-dir", required=True)
    args = p.parse_args(opts)
    load_render_request(args.request)
    with open(args.request, "r", encoding="utf-8") as file:
        request = json.load(file)
    approved_facade_shot(request)
    directory = os.path.abspath(args.output_dir)
    os.makedirs(os.path.join(directory, "frames"), exist_ok=True)
    build(directory, request)


if __name__ == "__main__":
    main()
