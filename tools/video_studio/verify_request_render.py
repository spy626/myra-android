"""Check a real Blender render was driven by the validated Android contract."""
import json
import sys
from pathlib import Path
from render_request import load_render_request

def main(request_path, render_dir):
    spec, metadata = load_render_request(request_path)
    out = Path(render_dir)
    scene = json.loads((out/"scene_manifest.json").read_text(encoding="utf-8"))
    if scene.get("spec") != spec.manifest():
        raise SystemExit("FAIL: renderer output does not reflect Android JSON spec")
    if scene.get("request_contract") != metadata:
        raise SystemExit("FAIL: render manifest lost or changed request identity/duration")
    if scene.get("expected_frames") != 72 or scene.get("fps") != 12:
        raise SystemExit("FAIL: renderer changed 6s preview format")
    print("PASS: validated Android request -> actual Blender 3D scene -> 6s video")

if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("Usage: verify_request_render.py request.json rendered-output-dir")
    main(sys.argv[1],sys.argv[2])
