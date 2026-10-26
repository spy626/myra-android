"""Create a *separate* honest Cycles CPU look-development preview from Slice28.

Do not replace any of the five storyboard scenes, source MP4s, manifests, or
production renderer. Three genuine ray-traced frames are written with real
Principled BSDF material nodes and physically contributing area lights.
"""
from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

import bpy
from mathutils import Vector

sys.path.insert(0, str(Path(__file__).resolve().parent))
from physical_look_contract import (
    FRAMES, LIGHTS, SAMPLES, WORLD_STRENGTH, material_properties,
    require_look_plan
)
from reveal_finish_contract import FINISH_PARTS, PREFIX, approved_detail_plan


def build(source: Path, output: Path) -> None:
    require_look_plan()
    approved_detail_plan()
    if (source.is_symlink() or not source.is_file() or
        source.name != "reveal_scene.blend" or source.stat().st_size < 2000):
        raise ValueError("Expected immutable saved Slice28 reveal_scene.blend only")
    if output.exists() or output.is_symlink():
        raise ValueError("Never overwrite existing look-development evidence")
    output.mkdir(parents=True)
    bpy.ops.wm.open_mainfile(filepath=str(source.resolve()))
    scene = bpy.context.scene
    if (scene.render.engine != "BLENDER_WORKBENCH" or
        scene.render.resolution_x != 360 or
        scene.render.resolution_y != 640 or
        scene.frame_end != 72 or scene.render.fps != 12):
        raise ValueError("Unexpected source Blender storyboard")
    if sum(ob.type == "MESH" and ob.name.startswith(PREFIX) for ob in scene.objects) != len(FINISH_PARTS):
        raise ValueError("Missing original nineteen architectural finish meshes")

    scene.render.engine = "CYCLES"
    scene.cycles.device = "CPU"
    scene.cycles.samples = SAMPLES
    scene.cycles.use_denoising = False
    scene.render.film_transparent = False
    scene.render.image_settings.file_format = "PNG"
    scene.render.image_settings.color_mode = "RGB"
    scene.render.resolution_percentage = 100
    scene.render.threads_mode = "FIXED"
    scene.render.threads = 2

    # The original workbench materials only have diffuse display colors.
    # Convert their existing Blender-native RGB values into actual PBR shaders.
    used = set()
    for ob in scene.objects:
        if ob.type == "MESH":
            for slot in ob.material_slots:
                if slot.material is not None:
                    used.add(slot.material.name)
    if len(used) < 9:
        raise ValueError("Expected the original assorted facade finishes")
    for name in sorted(used):
        mat = bpy.data.materials[name]
        rgba = tuple(float(x) for x in mat.diffuse_color)
        metallic, roughness = material_properties(name)
        mat.use_nodes = True
        nodes = mat.node_tree.nodes
        nodes.clear()
        output_node = nodes.new("ShaderNodeOutputMaterial")
        output_node.location = (220, 0)
        bsdf = nodes.new("ShaderNodeBsdfPrincipled")
        bsdf.location = (-80, 0)
        bsdf.inputs["Base Color"].default_value = rgba
        bsdf.inputs["Metallic"].default_value = metallic
        bsdf.inputs["Roughness"].default_value = roughness
        mat.node_tree.links.new(bsdf.outputs["BSDF"], output_node.inputs["Surface"])

    world = scene.world or bpy.data.worlds.new("LYRA Soft Ambient Sky")
    scene.world = world
    world.use_nodes = True
    background = world.node_tree.nodes.get("Background")
    if background is None:
        raise ValueError("No real Blender World Background shader")
    background.inputs["Color"].default_value = (0.49, 0.61, 0.74, 1.0)
    background.inputs["Strength"].default_value = WORLD_STRENGTH

    for label, xyz, watts, rgb, size in LIGHTS:
        bpy.ops.object.light_add(type="AREA", location=xyz)
        lamp = bpy.context.object
        lamp.name = label
        lamp.rotation_euler = (Vector((0.0, 0.0, 1.0)) - lamp.location).to_track_quat("-Z", "Y").to_euler()
        lamp.data.energy = watts
        lamp.data.color = rgb
        lamp.data.shape = "DISK"
        lamp.data.size = size

    scene.view_settings.view_transform = "Standard"
    scene.view_settings.look = "Medium High Contrast"
    scene.view_settings.exposure = 0
    scene.view_settings.gamma = 1
    scene.frame_set(FRAMES[0])
    bpy.ops.wm.save_as_mainfile(filepath=str((output / "reveal_cycles_lookdev.blend").resolve()))

    for frame in FRAMES:
        scene.frame_set(frame)
        scene.render.filepath = str((output / ("cycles_frame_%04d.png" % frame)).resolve())
        bpy.ops.render.render(write_still=True)
        print("LYRA_REAL_CYCLES_FRAME_COMPLETE", frame, flush=True)

    print("LYRA_SLICE29_PHYSICAL_CPU_LOOKDEV_3_FRAMES_COMPLETE", flush=True)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--source", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    args = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    values = p.parse_args(args)
    build(values.source, values.output)


if __name__ == "__main__":
    main()
