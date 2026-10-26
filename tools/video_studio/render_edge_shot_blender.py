"""Slice 25: two NEW offline Blender scenes: surveyed empty lot and finished-house reveal.

These are original six-second 3D animations. No AI provider, external assets,
Cloudflare, workflow dispatch or modification of the established renderers.
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
from edge_shot_contract import approved_edge_shot
from render_structure_shot_blender import mat, block, grow_block


def setup(directory, name):
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
    scene.render.filepath = os.path.join(directory, "frames", name + "_")
    scene.display.shading.light = "STUDIO"
    scene.display.shading.color_type = "MATERIAL"
    scene.display.shading.show_cavity = True
    scene.display.shading.cavity_type = "BOTH"
    scene.display.shading.show_shadows = True
    scene.display.shading.background_type = "WORLD"
    scene.world.color = (0.08, 0.12, 0.16)
    return scene


def camera_move(scene, name, keyframes, target, scale):
    bpy.ops.object.camera_add(location=keyframes[0][1])
    camera = bpy.context.object
    camera.name = name
    camera.data.type = "ORTHO"
    camera.data.ortho_scale = scale
    scene.camera = camera
    for frame, location in keyframes:
        camera.location = location
        camera.rotation_euler = (Vector(target) - camera.location).to_track_quat("-Z", "Y").to_euler()
        camera.keyframe_insert(data_path="location", frame=frame)
        camera.keyframe_insert(data_path="rotation_euler", frame=frame)


def site_preparation(scene, spec):
    grass = mat("Island grass", (0.30, 0.50, 0.32))
    earth = mat("Fresh excavated soil", (0.34, 0.28, 0.23))
    dark = mat("Foundation alignment trenches", (0.20, 0.18, 0.17))
    sand = mat("Survey string and site path", (0.82, 0.69, 0.48))
    timber = mat("Survey stakes", (0.56, 0.37, 0.23))

    block("Lot continuous grassy terrain", (0, 0, -0.34), (7.5, 6.9, 0.25), grass)
    grow_block("01 Clearing original lot", (0, 0, -0.14),
               (5.25, 4.85, 0.15), earth, 5, 27, axis=0)
    block("Site entrance sand", (0, -2.87, -0.19), (1.1, 1.1, 0.08), sand)

    # Excavation layout, four distinct new animated edges, no poured concrete.
    edges = (
        ("West", (-2.24, 0, -0.04), (0.07, 3.8, 0.05)),
        ("East", (2.24, 0, -0.04), (0.07, 3.8, 0.05)),
        ("North", (0, 1.88, -0.04), (4.5, 0.07, 0.05)),
        ("South", (0, -1.88, -0.04), (4.5, 0.07, 0.05)),
    )
    for i, (label, xyz, size) in enumerate(edges):
        grow_block("02 Excavation edge " + label, xyz, size,
                   dark, 19 + i * 3, 45 + i * 3,
                   axis=1 if i < 2 else 0)

    # Final surveyed site intentionally resembles foundation shot's first frame.
    grow_block("03 Survey longitudinal guide", (0, 0, 0.004),
               (0.025, 4.2, 0.025), sand, 39, 58, axis=1)
    grow_block("03 Survey transverse guide", (0, 0, 0.012),
               (4.9, 0.025, 0.025), sand, 43, 62, axis=0)
    for i, (x, y) in enumerate((
        (-1.67, -1.31), (-1.67, 1.31), (1.67, -1.31), (1.67, 1.31)
    )):
        grow_block("04 Survey stake " + str(i), (x, y, 0.18),
                   (0.075, 0.075, 0.35), timber, 48 + i * 2, 65 + i)

    camera_move(scene, "Site overhead inspection", (
        (1, (9.2, -10.6, 11.0)), (36, (8.4, -10.1, 10.1)),
        (72, (7.7, -9.8, 9.2))
    ), (0, 0, 0.10), 8.4)
    return {
        "site_clearing": 1, "survey_guides": 2,
        "excavation_edges": 4, "survey_stakes": 4,
        "foundation_not_poured": not any("concrete" in ob.name.lower()
                                          for ob in scene.objects)
    }


def house_reveal(scene, spec):
    grass = mat("Island grass", (0.30, 0.50, 0.32))
    earth = mat("Earth", (0.34, 0.28, 0.23))
    concrete = mat("Concrete", (0.70, 0.73, 0.72))
    steel = mat("Steel", (0.29, 0.37, 0.41))
    wall = mat("Requested house paint", {
        "cream": (0.94, 0.90, 0.81),
        "blue": (0.29, 0.55, 0.82),
        "terracotta": (0.81, 0.43, 0.30),
    }[spec["wall_color"]])
    roof = mat("Roof slate", (0.22, 0.33, 0.39))
    glass = mat("Windows", (0.18, 0.57, 0.71))
    wood = mat("Entry", (0.55, 0.32, 0.20))
    paving = mat("Entrance paving", (0.80, 0.70, 0.52))
    trim = mat("Light decorative trim", (0.91, 0.90, 0.82))

    # Same endpoints as stage 4. Final state is a completed house, not stage 4 replay.
    block("Ground grass", (0, 0, -0.36), (7.5, 6.9, 0.25), grass)
    block("Construction clearing", (0, 0, -0.18), (5.25, 4.85, 0.10), earth)
    block("Completed concrete foundation", (0, 0, 0.12), (4.05, 3.36, 0.26), concrete)

    corners = ((-1.70, -1.32), (-1.70, 1.32), (1.70, -1.32), (1.70, 1.32))
    for i, (x, y) in enumerate(corners):
        block("Column footing " + str(i), (x, y, 0.35),
              (0.55, 0.55, 0.21), concrete)
        block("Existing rebar " + str(i), (x, y, 0.62),
              (0.085, 0.085, 0.44), steel)
        block("01 Complete column " + str(i), (x, y, 1.37),
              (0.24, 0.24, 2.25), concrete)
    for i, y in enumerate((-1.32, 1.32)):
        block("02 Long beam " + str(i), (0, y, 2.55),
              (3.62, 0.27, 0.23), concrete)
    for i, x in enumerate((-1.70, 1.70)):
        block("02 Side beam " + str(i), (x, 0, 2.55),
              (0.27, 2.88, 0.23), concrete)

    walls = (
        ((0, 1.33, 0.81), (3.19, 0.15, 1.15)),
        ((-1.70, 0, 0.75), (0.15, 2.48, 1.03)),
        ((1.70, 0, 0.75), (0.15, 2.48, 1.03)),
        ((0, 1.33, 1.84), (3.19, 0.15, 0.86)),
        ((-1.70, 0, 1.74), (0.15, 2.48, 1.02)),
        ((1.70, 0, 1.74), (0.15, 2.48, 1.02)),
    )
    for i, (xyz, dims) in enumerate(walls):
        block("03 Complete wall " + str(i), xyz, dims, wall)

    if spec["roof_style"] == "gable":
        for name, sign in (("Left", -1), ("Right", 1)):
            panel = block("04 Gable roof " + name,
                          (sign * 1.03, 0, 3.02), (2.26, 3.52, 0.17), roof)
            panel.rotation_euler.y = sign * 0.33
        roof_count = 2
    else:
        block("04 Flat roof slab", (0, 0, 2.79),
              (4.42, 3.65, 0.19), roof)
        roof_count = 1

    for label, xyz, dims, material in (
        ("Left facade", (-1.22, -1.34, 1.29), (0.77, 0.17, 1.86), wall),
        ("Right facade", (1.22, -1.34, 1.29), (0.77, 0.17, 1.86), wall),
        ("Door", (0, -1.48, 1.09), (0.90, 0.08, 1.84), wood),
        ("Front window", (1.22, -1.48, 1.48), (0.58, 0.09, 0.70), glass),
    ):
        block("05 Existing " + label, xyz, dims, material)

    finishing = (
        ("Entry threshold", (0, -1.82, 0.16), (1.24, 0.56, 0.18), paving, 4, 22, 1),
        ("Walkway", (0, -2.68, -0.11), (1.04, 1.34, 0.09), paving, 12, 36, 1),
        ("Door lintel", (0, -1.55, 2.08), (1.13, 0.13, 0.12), trim, 20, 42, 0),
        ("Window sill", (1.22, -1.55, 1.09), (0.77, 0.15, 0.10), trim, 29, 50, 0),
        ("Left front trim", (-1.64, -1.51, 1.38), (0.10, 0.12, 1.78), trim, 40, 61, 2),
        ("Roof ridge finish", (0, 0, 3.50 if spec["roof_style"] == "gable" else 2.94),
         (0.16, 3.48, 0.12), trim, 49, 72, 1),
    )
    for label, xyz, size, material, start, finish, axis in finishing:
        grow_block("06 Reveal finishing " + label, xyz, size, material,
                   start, finish, axis=axis)

    camera_move(scene, "Finished home orbit right", (
        (1, (3.0, -7.8, 5.1)), (36, (0.5, -8.8, 5.3)),
        (72, (-3.7, -8.0, 5.5))
    ), (0, 0, 1.18), 8.2)
    return {
        "existing_columns": 4, "existing_beams": 4,
        "completed_walls": 6, "roof_panels": roof_count,
        "existing_front_details": 4,
        "animated_finishing_details": sum(
            ob.name.startswith("06 Reveal finishing") and ob.animation_data is not None
            for ob in scene.objects
        ),
    }


def build(directory, request, name):
    claim = approved_edge_shot(request, name)
    scene = setup(directory, name)
    geo = site_preparation(scene, claim["spec"]) if name == "lot" else house_reveal(scene, claim["spec"])
    manifest = {
        **claim, "actual_shot_rendered": True,
        "object_count": len(scene.objects), "geometry": geo
    }
    # Check all metadata and counts BEFORE costly rendering.
    from edge_shot_contract import verify_edge_manifest
    verify_edge_manifest(request, name, manifest)
    scene.frame_set(1)
    bpy.ops.wm.save_as_mainfile(filepath=os.path.join(directory, name + "_scene.blend"))
    bpy.ops.render.render(animation=True)
    with open(os.path.join(directory, name + "_manifest.json"), "w", encoding="utf-8") as file:
        json.dump(manifest, file, indent=2, sort_keys=True)
    print("SLICE25_ORIGINAL_BLENDER_SHOT_RENDERED", name, len(scene.objects))


def main():
    args = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    parser = argparse.ArgumentParser()
    parser.add_argument("--scene", choices=("lot", "reveal"), required=True)
    parser.add_argument("--request", required=True)
    parser.add_argument("--output-dir", required=True)
    cfg = parser.parse_args(args)
    load_render_request(cfg.request)
    with open(cfg.request, encoding="utf-8") as handle:
        request = json.load(handle)
    approved_edge_shot(request, cfg.scene)
    target = os.path.abspath(cfg.output_dir)
    os.makedirs(os.path.join(target, "frames"), exist_ok=True)
    build(target, request, cfg.scene)


if __name__ == "__main__":
    main()
