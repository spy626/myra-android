"""Verify REAL Blender 32-sample denoised frames vs 8-sample originals.

Do not claim a full-length physically rendered video or independently proven
subjective image improvement: verifies actual changed settings, authentic
rendered RGB24 images, different content, and preserved physical scene proof.
"""
from __future__ import annotations
import argparse
import json
import subprocess
import sys
import tempfile
from pathlib import Path
import bpy

sys.path.insert(0, str(Path(__file__).resolve().parent))
from physical_look_contract import FRAMES
from refined_cycles_contract import (
    DENOISING_BACKEND, DENOISING_FILTER, REFINED_SAMPLES, require_refined_plan
)
from verify_cycles_lookdev_blender import check_scene, check_png


def verify(refined: Path, original: Path, base_frames: Path, refined_frames: Path):
    require_refined_plan()
    for path, expected in (
        (refined, "reveal_cycles_refined.blend"),
        (original, "reveal_cycles_lookdev.blend"),
    ):
        if path.is_symlink() or not path.is_file() or path.name != expected or path.stat().st_size < 2000:
            raise ValueError("Missing distinct saved Cycles Blender project")

    # Independent read of BOTH real editable projects, not just claim JSON.
    bpy.ops.wm.open_mainfile(filepath=str(original.resolve()))
    old_materials = check_scene(bpy.context.scene, samples=8, denoised=False)
    old_lights = tuple(sorted(o.name for o in bpy.context.scene.objects if o.type == "LIGHT"))
    old_meshes = tuple(sorted(o.name for o in bpy.context.scene.objects if o.type == "MESH"))

    bpy.ops.wm.open_mainfile(filepath=str(refined.resolve()))
    scene = bpy.context.scene
    new_materials = check_scene(scene, samples=REFINED_SAMPLES, denoised=False)
    new_lights = tuple(sorted(o.name for o in scene.objects if o.type == "LIGHT"))
    new_meshes = tuple(sorted(o.name for o in scene.objects if o.type == "MESH"))
    if old_materials != new_materials or old_lights != new_lights or old_meshes != new_meshes:
        raise ValueError("Physical house, light or material inventory changed during refinement")

    source_hashes, raw_hashes, refined_hashes = [], [], []
    source_luma, refined_luma = [], []
    for frame in FRAMES:
        baseline, mean_old = check_png(base_frames / ("cycles_frame_%04d.png" % frame))
        raw = refined_frames / ("refined_raw_%04d.png" % frame)
        improved_path = refined_frames / ("refined_frame_%04d.png" % frame)
        high_sample, _ = check_png(raw)
        improved, mean_new = check_png(improved_path)
        if len({baseline, high_sample, improved}) != 3:
            raise ValueError("Baseline, physical render and denoised output must differ")
        # Reproduce the exact fixed filter independently from the saved
        # real 32-sample PNG; reject arbitrary edited or forged refined frames.
        with tempfile.TemporaryDirectory(prefix="slice30_denoise_audit_") as tmp:
            reproduced = Path(tmp) / "nlmeans_replayed.png"
            subprocess.run([
                "ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin",
                "-y", "-i", str(raw), "-vf", DENOISING_FILTER,
                "-frames:v", "1", "-pix_fmt", "rgb24", str(reproduced)
            ], check=True, timeout=120)
            reproduced_hash, _ = check_png(reproduced)
            if reproduced_hash != improved:
                raise ValueError("Denoised evidence is not exactly reproducible")
        source_hashes.append(baseline)
        raw_hashes.append(high_sample)
        refined_hashes.append(improved)
        source_luma.append(mean_old)
        refined_luma.append(mean_new)
    if (len(set(source_hashes)) != len(FRAMES) or
        len(set(raw_hashes)) != len(FRAMES) or
        len(set(refined_hashes)) != len(FRAMES)):
        raise ValueError("Still frames not independent or original animation frozen")

    return {
        "slice": 30, "original_project_verified": True,
        "refined_project_verified": True,
        "render_engine": scene.render.engine,
        "device": scene.cycles.device,
        "original_samples": 8, "refined_samples": REFINED_SAMPLES,
        "denoising_enabled": True,
        "denoising_backend": DENOISING_BACKEND,
        "blender_native_denoising": False,
        "fixed_ffmpeg_nlmeans_reproduced": True,
        "area_lights_preserved": len(new_lights),
        "original_meshes_preserved": len(new_meshes),
        "original_pbr_materials_preserved": new_materials,
        "original_frame_numbers": list(FRAMES),
        "baseline_decoded_rgb_sha256": source_hashes,
        "raw_32_sample_decoded_rgb_sha256": raw_hashes,
        "refined_decoded_rgb_sha256": refined_hashes,
        "baseline_mean_red_channel": source_luma,
        "refined_mean_red_channel": refined_luma,
        "three_new_rendered_original_timeline_stills_verified": True,
        "grain_reduction_quantitatively_proven": False,
        "full_thirty_second_cycles_video_created": False,
        "physical_android_acceptance_proven": False,
    }


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--original", type=Path, required=True)
    p.add_argument("--refined", type=Path, required=True)
    p.add_argument("--original-frames", type=Path, required=True)
    p.add_argument("--refined-frames", type=Path, required=True)
    p.add_argument("--report", type=Path, required=True)
    flags = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    a = p.parse_args(flags)
    if a.report.exists() or a.report.is_symlink():
        raise ValueError("Do not overwrite evidence files")
    report = verify(a.refined, a.original, a.original_frames, a.refined_frames)
    a.report.parent.mkdir(parents=True, exist_ok=True)
    a.report.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print("SLICE30_REAL_CYCLES_32_SAMPLE_PLUS_FFMPEG_DENOISE_PASS", report)


if __name__ == "__main__":
    main()
