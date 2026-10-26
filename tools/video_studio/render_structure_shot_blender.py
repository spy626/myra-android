"""Slice 22: a NEW real 6s structural-frame Blender scene, not the old 6s movie.

Accepts only existing strictly validated 30/60-second mini-house storyboards.
No arbitrary Python, path, mesh asset, cloud provider, or video dispatch in payload.
"""
from __future__ import annotations

import argparse
import json
import os
import sys

import bpy
from mathutils import Vector

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from frame_shot_contract import approved_frame_shot
from render_request import load_render_request


def mat(name, rgb):
    value = bpy.data.materials.new(name)
    value.diffuse_color = (*rgb, 1.0)
    return value


def block(name, center, dimensions, material):
    bpy.ops.mesh.primitive_cube_add(size=1, location=center)
    ob = bpy.context.object
    ob.name = name
    ob.dimensions = dimensions
    bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    ob.data.materials.append(material)
    return ob


def grow_block(name, center, dimensions, material, begin, finish, axis=2):
    """Unhide then animate the actual geometry from a defined construction edge."""
    assert axis in (0, 1, 2) and 2 <= begin < finish <= 72
    ob = block(name, center, dimensions, material)
    extent = ob.dimensions[axis]
    final_position = ob.location[axis]
    ob.hide_render = True
    ob.keyframe_insert(data_path="hide_render", frame=1)
    ob.keyframe_insert(data_path="hide_render", frame=begin - 1)
    ob.hide_render = False
    ob.keyframe_insert(data_path="hide_render", frame=begin)
    ob.scale[axis] = 0.004 / extent
    ob.location[axis] = final_position - extent / 2 + 0.002
    ob.keyframe_insert(data_path="scale", frame=begin)
    ob.keyframe_insert(data_path="location", frame=begin)
    ob.scale[axis] = 1
    ob.location[axis] = final_position
    ob.keyframe_insert(data_path="scale", frame=finish)
    ob.keyframe_insert(data_path="location", frame=finish)
    return ob


def build_frame(directory, request):
    selected = approved_frame_shot(request)
    spec = selected["spec"]

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
    scene.render.filepath = os.path.join(directory, "frames", "structure_")
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
    masonry = mat("Selected partial walls", {
        "cream": (0.94, 0.90, 0.81),
        "blue": (0.29, 0.55, 0.82),
        "terracotta": (0.81, 0.43, 0.30),
    }[spec["wall_color"]])
    paving = mat("Site path", (0.80, 0.70, 0.52))

    block("Ground grass", (0, 0, -0.36), (7.5, 6.9, 0.25), lawn)
    block("Construction clearing", (0, 0, -0.18), (5.25, 4.85, 0.10), soil)
    block("Paved path to structure", (0, -2.82, -0.15), (1.1, 1.1, 0.08), paving)
    block("Finished concrete foundation only", (0, 0, 0.12), (4.05, 3.36, 0.26), concrete)

    corners = ((-1.70, -1.32), (-1.70, 1.32), (1.70, -1.32), (1.70, 1.32))
    for i, (x, y) in enumerate(corners):
        block("Concrete column footing %d" % i, (x, y, 0.35),
              (0.55, 0.55, 0.21), concrete)
        block("Existing rebar %d" % i, (x, y, 0.62),
              (0.085, 0.085, 0.44), steel)

    for i, (x, y) in enumerate(corners):
        grow_block("01 Rising ground-floor column %d" % i,
                   (x, y, 1.37), (0.24, 0.24, 2.25),
                   concrete, 5 + i * 3, 33 + i * 3)

    for i, (y, x) in enumerate(((-1.32, 0), (1.32, 0))):
        grow_block("02 Long top beam %d" % i,
                   (x, y, 2.55), (3.62, 0.27, 0.23),
                   concrete, 31 + i * 3, 52 + i * 3, axis=0)
    for i, x in enumerate((-1.70, 1.70)):
        grow_block("02 Side top beam %d" % i,
                   (x, 0, 2.55), (0.27, 2.88, 0.23),
                   concrete, 36 + i * 3, 58 + i * 3, axis=1)

    # Intentionally unfinished partial walls: no roof, doors or finished facade.
    grow_block("03 Back masonry starter wall", (0, 1.33, 0.81),
               (3.19, 0.15, 1.15), masonry, 51, 69)
    grow_block("03 Left masonry starter wall", (-1.70, 0, 0.75),
               (0.15, 2.48, 1.03), masonry, 55, 70)
    grow_block("03 Right masonry starter wall", (1.70, 0, 0.75),
               (0.15, 2.48, 1.03), masonry, 57, 72)

    # Slice26: exact camera handoff from the previous foundation's frame 72.
    # End pose remains identical to the facade shot's frame 1. Camera zoom,
    # location and look-at animate; no abrupt lens/orientation reset at cuts.
    bpy.ops.object.camera_add(location=(4.7, -7.2, 6.1))
    camera = bpy.context.object
    camera.name = "Ground floor orbit camera"
    camera.data.type = "ORTHO"
    scene.camera = camera
    for frame, xyz, aim_z, zoom in (
        (1, (4.7, -7.2, 6.1), 0.10, 8.4),
        (36, (4.9, -8.0, 5.95), 0.66, 8.3),
        (72, (5.1, -8.8, 5.8), 1.18, 8.2),
    ):
        camera.location = xyz
        camera.rotation_euler = (
            Vector((0, 0, aim_z)) - camera.location
        ).to_track_quat("-Z", "Y").to_euler()
        camera.data.ortho_scale = zoom
        camera.keyframe_insert(data_path="location", frame=frame)
        camera.keyframe_insert(data_path="rotation_euler", frame=frame)
        camera.data.keyframe_insert(data_path="ortho_scale", frame=frame)

    animated = [
        ob for ob in scene.objects
        if ob.animation_data and ob.name.startswith(("01 ", "02 ", "03 "))
    ]
    manifest = {
        **selected,
        "actual_shot_rendered": True,
        "object_count": len(scene.objects),
        "geometry": {
            "ground_floor_columns": sum(
                o.name.startswith("01 Rising ground-floor column") for o in scene.objects
            ),
            "top_beams": sum(o.name.startswith("02 ") for o in scene.objects),
            "partial_walls": sum(o.name.startswith("03 ") for o in scene.objects),
            "animated_objects": len(animated),
            "new_structure_not_finished_house": all(
                not o.name.startswith(("06 Flat roof", "07 Door", "08 Upper window"))
                for o in scene.objects
            ),
        },
    }
    scene.frame_set(1)
    bpy.ops.wm.save_as_mainfile(filepath=os.path.join(directory, "structure_scene.blend"))
    bpy.ops.render.render(animation=True)
    with open(os.path.join(directory, "structure_manifest.json"), "w", encoding="utf-8") as out:
        json.dump(manifest, out, indent=2, sort_keys=True)
    print("SLICE22_REAL_STRUCTURE_FRAMES_RENDERED", len(scene.objects))


def main():
    cli = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    parser = argparse.ArgumentParser(description="Single real storyboard structure shot, offline")
    parser.add_argument("--request", required=True)
    parser.add_argument("--output-dir", required=True)
    args = parser.parse_args(cli)
    load_render_request(args.request)
    with open(args.request, "r", encoding="utf-8") as stream:
        request = json.load(stream)
    approved_frame_shot(request)
    directory = os.path.abspath(args.output_dir)
    os.makedirs(os.path.join(directory, "frames"), exist_ok=True)
    build_frame(directory, request)


if __name__ == "__main__":
    main()
