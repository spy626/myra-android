"""Compare rendered scene metadata to requested controlled 3D house attributes."""
import argparse
import json
from pathlib import Path

def main():
    p = argparse.ArgumentParser()
    p.add_argument("directory")
    p.add_argument("--floors", type=int, choices=[1,2], required=True)
    p.add_argument("--color", choices=["cream","blue","terracotta"], required=True)
    p.add_argument("--roof", choices=["flat","gable"], required=True)
    p.add_argument("--garden", choices=["yes","no"], required=True)
    opts = p.parse_args()
    data = json.loads((Path(opts.directory) / "scene_manifest.json").read_text(encoding="utf-8"))
    expected = {"floors":opts.floors, "wall_color":opts.color,
                "roof_style":opts.roof, "garden":opts.garden=="yes"}
    if data.get("spec") != expected:
        raise SystemExit("FAIL: requested house parameters not reflected in scene")
    geometry = data.get("geometry", {})
    if geometry.get("second_storey") != (opts.floors==2):
        raise SystemExit("FAIL: second-floor geometry does not match")
    if geometry.get("gable_panels") != (2 if opts.roof=="gable" else 0):
        raise SystemExit("FAIL: roof shape geometry does not match")
    if geometry.get("garden_planters") != (4 if opts.garden=="yes" else 0):
        raise SystemExit("FAIL: garden 3D geometry does not match")
    if data.get("object_count", 0) < 20:
        raise SystemExit("FAIL: missing expected 3D geometry")
    print("PASS: actual Blender scene matches selected house brief", expected)

if __name__ == "__main__":
    main()
