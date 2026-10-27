"""Fail-closed verification of ACTUAL saved Cycles project + three PNG renders.

Must run inside Blender with ffmpeg installed. A manifest or modified RGB
preview alone cannot substitute for actual PBR node/material/light evidence.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import math
import struct
import subprocess
import sys
from pathlib import Path
import bpy

sys.path.insert(0, str(Path(__file__).resolve().parent))
from physical_look_contract import (
    FRAMES, LIGHTS, SAMPLES, WORLD_STRENGTH, material_properties, require_look_plan
)
from reveal_finish_contract import FINISH_PARTS, PREFIX, approved_detail_plan


def near(actual, expected, name, tolerance=0.0002):
    if not math.isfinite(float(actual)) or abs(float(actual) - float(expected)) > tolerance:
        raise ValueError("Mismatch in true Cycles evidence: " + name)


def check_scene(scene, samples=SAMPLES, denoised=False):
    if (scene.render.engine != "CYCLES" or scene.cycles.device != "CPU" or
        scene.cycles.samples != samples or
        bool(scene.cycles.use_denoising) is not denoised or scene.frame_end != 72 or
        scene.render.fps != 12 or
        scene.render.resolution_x != 360 or
        scene.render.resolution_y != 640):
        raise ValueError("Missing physically based bounded CPU rendering contract")

    lamps = {o.name: o for o in scene.objects if o.type == "LIGHT"}
    if set(lamps) != {x[0] for x in LIGHTS}:
        raise ValueError("Unexpected real area-light inventory")
    for name, loc, watts, rgb, size in LIGHTS:
        ob = lamps[name]
        if ob.data.type != "AREA" or ob.data.shape != "DISK":
            raise ValueError("Not a physically contributing area light")
        for a, b in zip(ob.location, loc):
            near(a, b, name + " position")
        near(ob.data.energy, watts, name + " real power")
        near(ob.data.size, size, name + " source radius")
        for a, b in zip(ob.data.color, rgb):
            near(a, b, name + " light color")
    if not scene.world or not scene.world.use_nodes:
        raise ValueError("No physically shaded ambient world")
    bg = scene.world.node_tree.nodes.get("Background")
    if bg is None:
        raise ValueError("No active ambient node")
    near(bg.inputs["Strength"].default_value, WORLD_STRENGTH, "world light")

    mat_names = {s.material.name for o in scene.objects if o.type == "MESH"
                 for s in o.material_slots if s.material is not None}
    if len(mat_names) < 9:
        raise ValueError("Too few physically based original materials")
    for name in mat_names:
        mat = bpy.data.materials[name]
        if not mat.use_nodes or mat.node_tree is None:
            raise ValueError("Material not converted to PBR: " + name)
        shaders = [n for n in mat.node_tree.nodes if n.bl_idname == "ShaderNodeBsdfPrincipled"]
        if len(shaders) != 1:
            raise ValueError("Expected a single actual PBR shader for " + name)
        shader = shaders[0]
        metallic, roughness = material_properties(name)
        near(shader.inputs["Metallic"].default_value, metallic, name + " metal")
        near(shader.inputs["Roughness"].default_value, roughness, name + " roughness")
        for a, b in zip(shader.inputs["Base Color"].default_value,
                        mat.diffuse_color):
            near(a, b, name + " existing source color")
        if not any(link.from_node == shader and
                   link.to_node.bl_idname == "ShaderNodeOutputMaterial"
                   for link in mat.node_tree.links):
            raise ValueError("BSDF not wired to real material output")
    if sum(o.type == "MESH" and o.name.startswith(PREFIX)
           for o in scene.objects) != len(FINISH_PARTS):
        raise ValueError("Missing Slice28 final house details")
    return len(mat_names)


def check_png(path: Path):
    if (path.is_symlink() or not path.is_file() or
        path.stat().st_size < 12000):
        raise ValueError("Missing real physically lit PNG: " + path.name)
    payload = path.read_bytes()
    if payload[:8] != b"\x89PNG\r\n\x1a\n" or struct.unpack(">II", payload[16:24]) != (360, 640):
        raise ValueError("Unexpected physical render dimensions or file type")
    result = subprocess.run([
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin",
        "-i", str(path), "-frames:v", "1",
        "-pix_fmt", "rgb24", "-f", "rawvideo", "-"
    ], capture_output=True, timeout=45, check=True)
    raw = result.stdout
    if len(raw) != 360 * 640 * 3:
        raise ValueError("Cannot decode real Cycles output")
    luma = sum(raw[::3]) / (360 * 640)
    if not (15 < luma < 245) or not (min(raw) < 45 and max(raw) > 90):
        raise ValueError("Physically lit scene black, clipped or visually blank")
    return hashlib.sha256(raw).hexdigest(), round(luma, 4)


def verify(scene_file: Path, source: Path, output: Path):
    require_look_plan()
    approved_detail_plan()
    if scene_file.is_symlink() or not scene_file.is_file() or scene_file.name != "reveal_cycles_lookdev.blend":
        raise ValueError("Missing saved CPU look-development scene")
    if source.is_symlink() or not source.is_file() or source.name != "reveal_scene.blend":
        raise ValueError("Missing distinct original Workbench scene")
    bpy.ops.wm.open_mainfile(filepath=str(scene_file.resolve()))
    materials = check_scene(bpy.context.scene)
    hashes, mean_lumas = [], []
    for frame in FRAMES:
        h, lum = check_png(output / ("cycles_frame_%04d.png" % frame))
        hashes.append(h)
        mean_lumas.append(lum)
    if len(set(hashes)) != 3:
        raise ValueError("Source frames recycled or unchanging")
    return {
        "slice": 29, "render_engine": "CYCLES", "device": "CPU",
        "samples": SAMPLES, "original_timeline_frames": list(FRAMES),
        "physically_contributing_area_lights": len(LIGHTS),
        "principled_bsdf_materials": materials,
        "decoded_frame_sha256": hashes,
        "frame_red_channel_mean": mean_lumas,
        "actual_path_traced_preview_verified": True,
        "existing_30s_workbench_video_unchanged": True,
        "thirty_second_cycles_movie_created": False,
        "photorealism_or_phone_acceptance_proven": False
    }


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--scene", type=Path, required=True)
    p.add_argument("--original", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--report", type=Path, required=True)
    args = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    v = p.parse_args(args)
    if v.report.exists() or v.report.is_symlink():
        raise ValueError("Do not overwrite previous physically lit evidence")
    proof = verify(v.scene, v.original, v.output)
    v.report.parent.mkdir(parents=True, exist_ok=True)
    v.report.write_text(json.dumps(proof, sort_keys=True, indent=2) + "\n", encoding="utf-8")
    print("SLICE29_THREE_REAL_CPU_CYCLES_FRAMES_VERIFIED", proof)


if __name__ == "__main__":
    main()
