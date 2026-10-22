"""LYRA slice 3: fixed original 6-second Blender mini-house 3D prototype.

Run: blender -b --factory-startup --python tools/video_studio/mini_house_blender.py -- --output-dir build/video-studio
No AI video provider, external model, paid asset, or Android render is used.
"""
import argparse
import json
import os
import sys
import bpy
from mathutils import Vector

FPS, SECONDS, WIDTH, HEIGHT = 12, 6, 360, 640
FRAMES = FPS * SECONDS


def color(name, rgb):
    material = bpy.data.materials.new(name)
    material.diffuse_color = (*rgb, 1.0)
    return material


def box(name, center, dimensions, material, reveal=None):
    bpy.ops.mesh.primitive_cube_add(size=1, location=center)
    obj = bpy.context.object
    obj.name = name
    obj.scale = dimensions
    obj.data.materials.append(material)
    if reveal:
        start, finish = reveal
        assert 1 <= start < finish <= FRAMES
        # Keep unrevealed 3D objects genuinely invisible. Flattening their Z
        # scale alone left roofs/slabs floating as visible plates in early frames.
        obj.hide_render = True
        obj.keyframe_insert(data_path="hide_render", frame=1)
        obj.keyframe_insert(data_path="hide_render", frame=start - 1)
        obj.hide_render = False
        obj.keyframe_insert(data_path="hide_render", frame=start)
        z = obj.location.z
        depth = obj.scale.z
        obj.location.z = z - depth / 2 + 0.0005
        obj.scale.z = 0.001
        obj.keyframe_insert(data_path="scale", frame=start)
        obj.keyframe_insert(data_path="location", frame=start)
        obj.location.z = z
        obj.scale.z = depth
        obj.keyframe_insert(data_path="scale", frame=finish)
        obj.keyframe_insert(data_path="location", frame=finish)
    return obj


def shrub(name, center, material):
    bpy.ops.mesh.primitive_uv_sphere_add(segments=12, ring_count=8, radius=0.32, location=center)
    obj = bpy.context.object
    obj.name = name
    obj.data.materials.append(material)


def build_scene(directory):
    bpy.ops.object.select_all(action="SELECT")
    bpy.ops.object.delete(use_global=False)
    scene = bpy.context.scene
    scene.frame_start, scene.frame_end = 1, FRAMES
    scene.render.fps = FPS
    scene.render.resolution_x, scene.render.resolution_y = WIDTH, HEIGHT
    scene.render.resolution_percentage = 100
    scene.render.engine = "BLENDER_WORKBENCH"
    scene.render.image_settings.file_format = "PNG"
    scene.render.image_settings.color_mode = "RGB"
    scene.render.filepath = os.path.join(directory, "frames", "frame_")
    scene.display.shading.light = "STUDIO"
    scene.display.shading.color_type = "MATERIAL"
    scene.display.shading.show_cavity = True
    scene.display.shading.cavity_type = "BOTH"
    scene.display.shading.show_shadows = True
    scene.display.shading.background_type = "WORLD"
    scene.world.color = (0.10, 0.15, 0.19)
    grass = color("Grass", (0.35, 0.56, 0.35))
    earth = color("Ground display stand", (0.30, 0.32, 0.32))
    stone = color("Concrete", (0.70, 0.70, 0.68))
    wall = color("Ivory facade", (0.94, 0.90, 0.81))
    white = color("White facade", (0.92, 0.95, 0.94))
    roof = color("Modern slate roof", (0.22, 0.33, 0.39))
    wood = color("Wood entry", (0.55, 0.32, 0.20))
    glass = color("Blue glass", (0.18, 0.57, 0.71))
    trim = color("Teal trim", (0.10, 0.32, 0.35))
    path = color("Sand paving", (0.76, 0.69, 0.58))
    flower = color("Coral pink flowers", (0.89, 0.46, 0.40))

    box("Display plinth", (0, 0, -0.29), (6.2, 5.8, 0.36), earth)
    box("Garden plot", (0, 0, -0.06), (6.0, 5.55, 0.10), grass)
    box("Garden entry path", (0, -2.2, 0.025), (1.02, 1.4, 0.08), path)
    for i, x in enumerate((-2.53, 2.53)):
        for j, y in enumerate((-2.0, 1.78)):
            box("Planter {} {}".format(i, j), (x, y, 0.10), (0.62, 0.66, 0.22), path)
            shrub("Garden foliage {} {}".format(i, j), (x, y, 0.39), grass)

    box("01 Foundation", (0, 0, 0.10), (4.0, 3.3, 0.24), stone, (3, 11))
    for x in (-1.76, 1.76):
        for y in (-1.39, 1.39):
            box("02 Column {} {}".format(x, y), (x, y, 1.25), (0.15, 0.15, 2.10), stone, (10, 23))
    box("03 Ground rear wall", (0, 1.39, 1.26), (3.47, 0.14, 2.07), wall, (14, 26))
    box("03 Ground left wall", (-1.77, 0, 1.26), (0.15, 2.74, 2.07), wall, (15, 27))
    box("03 Ground right wall", (1.77, 0, 1.26), (0.15, 2.74, 2.07), wall, (16, 28))
    box("03 Facade left", (-1.3, -1.40, 1.26), (0.93, 0.14, 2.07), white, (17, 29))
    box("03 Facade right", (1.3, -1.40, 1.26), (0.93, 0.14, 2.07), white, (17, 30))
    box("03 Entrance lintel", (0, -1.40, 2.06), (1.68, 0.13, 0.31), wall, (18, 30))
    box("04 First floor slab", (0, 0, 2.41), (4.12, 3.40, 0.20), stone, (27, 37))
    box("05 Upper rear wall", (-0.59, 1.47, 3.40), (2.34, 0.13, 1.81), white, (33, 46))
    box("05 Upper left wall", (-1.69, 0.23, 3.40), (0.14, 2.63, 1.81), white, (34, 47))
    box("05 Upper right wall", (0.54, 0.23, 3.40), (0.14, 2.63, 1.81), wall, (35, 48))
    box("05 Upper front wall", (-0.60, -1.08, 3.40), (2.32, 0.14, 1.81), wall, (35, 49))
    box("06 Flat roof", (-0.59, 0.23, 4.36), (2.55, 2.98, 0.24), roof, (42, 53))
    box("06 Terrace floor", (1.28, 0.05, 2.60), (1.38, 3.10, 0.08), path, (41, 52))
    for x in (0.69, 1.07, 1.45, 1.88):
        box("Terrace railing post {:.2f}".format(x), (x, -1.48, 3.02),
            (0.052, 0.05, 0.72), trim, (48, 59))
    box("Terrace handrail", (1.29, -1.48, 3.43), (1.52, 0.07, 0.11), trim, (51, 61))

    box("07 Door", (0, -1.53, 1.02), (0.85, 0.07, 1.76), wood, (47, 60))
    box("07 Door awning", (0, -1.65, 2.16), (1.25, 0.40, 0.10), roof, (48, 60))
    for x in (-1.26, 1.26):
        box("07 Ground window {}".format(x), (x, -1.52, 1.38),
            (0.64, 0.07, 0.75), glass, (49, 61))
        box("07 Window divider {}".format(x), (x, -1.57, 1.38),
            (0.05, 0.09, 0.79), trim, (50, 62))
    box("08 Upper window", (-0.60, -1.18, 3.5), (1.35, 0.07, 0.85), glass, (52, 65))
    box("08 Upper divider", (-0.60, -1.23, 3.5), (0.07, 0.09, 0.92), trim, (53, 66))
    box("08 Entrance step", (0, -1.8, 0.13), (1.36, 0.57, 0.18), path, (50, 65))
    for x in (-1.95, 1.95):
        shrub("Flower bed {}".format(x), (x, -2.08, 0.20), flower)

    bpy.ops.object.camera_add(location=(9.0, -11.8, 7.3))
    cam = bpy.context.object
    cam.name = "Moving studio camera"
    cam.data.type = "ORTHO"
    cam.data.ortho_scale = 8.8
    scene.camera = cam
    for f, location in ((1, (9, -11.8, 7.4)), (36, (8, -10.4, 7.0)), (72, (7.4, -9.6, 6.6))):
        cam.location = location
        cam.rotation_euler = (Vector((0.0, 0.0, 2.04)) - cam.location).to_track_quat("-Z", "Y").to_euler()
        cam.keyframe_insert(data_path="location", frame=f)
        cam.keyframe_insert(data_path="rotation_euler", frame=f)

    scene.frame_set(1)
    bpy.ops.wm.save_as_mainfile(filepath=os.path.join(directory, "mini_house_scene.blend"))
    with open(os.path.join(directory, "scene_manifest.json"), "w", encoding="utf-8") as out:
        json.dump({
            "engine": scene.render.engine,
            "width": WIDTH, "height": HEIGHT, "fps": FPS,
            "duration_seconds": SECONDS, "expected_frames": FRAMES,
            "object_count": len(scene.objects),
            "kind": "fixed procedural proof of concept, NOT prompt-to-video",
        }, out, indent=2)
    bpy.ops.render.render(animation=True)


def main():
    command = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", required=True)
    args = parser.parse_args(command)
    output = os.path.abspath(args.output_dir)
    os.makedirs(os.path.join(output, "frames"), exist_ok=True)
    build_scene(output)
    print("SIX_SECOND_BLENDER_RENDER_COMPLETE")


if __name__ == "__main__":
    main()
