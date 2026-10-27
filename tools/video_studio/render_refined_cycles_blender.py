"""Slice30: render a separate refined Cycles scene from authentic Slice29 proof.

Only 3 original reveal frames. Keep prior Cycles scene and 30s Workbench
film untouched. No arbitrary input code, downloaded assets, or paid models.
"""
from __future__ import annotations
import argparse
import subprocess
import sys
from pathlib import Path
import bpy

sys.path.insert(0, str(Path(__file__).resolve().parent))
from physical_look_contract import FRAMES
from refined_cycles_contract import (
    DENOISING_FILTER, REFINED_SAMPLES, require_refined_plan
)
from verify_cycles_lookdev_blender import check_scene


def build(source: Path, output: Path):
    require_refined_plan()
    if (source.is_symlink() or not source.is_file() or
        source.name != "reveal_cycles_lookdev.blend" or source.stat().st_size < 2000):
        raise ValueError("Must start from the actual saved Slice29 Cycles Blender project")
    if output.exists() or output.is_symlink():
        raise ValueError("Refusing to overwrite any previous render evidence")
    output.mkdir(parents=True)
    bpy.ops.wm.open_mainfile(filepath=str(source.resolve()))
    scene = bpy.context.scene
    check_scene(scene)
    if scene.render.image_settings.file_format != "PNG":
        raise ValueError("Only original offline PNG still rendering is allowed")
    scene.cycles.samples = REFINED_SAMPLES
    # Ubuntu's signed Blender build has no OpenImageDenoise support.
    # Keep true CPU path tracing and apply fixed FFmpeg NLMeans after render.
    scene.cycles.use_denoising = False
    scene.cycles.device = "CPU"
    scene.render.threads_mode = "FIXED"
    scene.render.threads = 2
    scene.render.resolution_percentage = 100
    scene.render.image_settings.file_format = "PNG"
    scene.render.image_settings.color_mode = "RGB"
    scene.frame_set(FRAMES[0])
    bpy.ops.wm.save_as_mainfile(
        filepath=str((output / "reveal_cycles_refined.blend").resolve())
    )
    for frame in FRAMES:
        scene.frame_set(frame)
        raw = output / ("refined_raw_%04d.png" % frame)
        denoised = output / ("refined_frame_%04d.png" % frame)
        scene.render.filepath = str(raw.resolve())
        bpy.ops.render.render(write_still=True)
        subprocess.run([
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin",
            "-y", "-i", str(raw.resolve()), "-vf", DENOISING_FILTER,
            "-frames:v", "1", "-pix_fmt", "rgb24",
            str(denoised.resolve())
        ], check=True, timeout=120)
        if not raw.is_file() or not denoised.is_file():
            raise RuntimeError("Missing CPU path trace or NLMeans denoised output")
        print("LYRA_REFINED_CPU_FRAME_AND_NLMEANS_RENDERED", frame, flush=True)
    print("SLICE30_REAL_32_SAMPLE_CYCLES_FFMPEG_NLMEANS_COMPLETE", flush=True)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--source", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    flags = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    a = p.parse_args(flags)
    build(a.source, a.output)


if __name__ == "__main__":
    main()
