"""Authorized GitHub Actions job input and verified receipt (no network or tokens)."""
import argparse
import hashlib
import json
import re
from pathlib import Path

from render_request import validate_request


def make_request(project_id, floors, color, roof, garden, target):
    if floors not in ("1", "2") or target not in ("30", "60") or garden not in ("yes", "no"):
        raise ValueError("Unsupported request choice")
    payload = {
        "request_version": 1, "renderer": "blender-mini-house-v1",
        "project_id": project_id, "mode": "construction",
        "target_duration_seconds": int(target),
        "preview_duration_seconds": 6, "width": 360, "height": 640,
        "fps": 12, "spec": {
            "floors": int(floors), "wall_color": color,
            "roof_style": roof, "garden": garden == "yes",
        },
    }
    validate_request(payload)
    return payload


def approved_input_fingerprint(request):
    """Deterministic cross-platform reference digest, NOT an auth signature.

    Android uses exactly these six GitHub workflow_dispatch fields, in this
    fixed order. Neither the original user prompt nor any credential appears.
    """
    spec, _ = validate_request(request)
    rows = [
        ("project_id", request["project_id"]),
        ("floors", str(spec.floors)),
        ("color", spec.wall_color),
        ("roof", spec.roof_style),
        ("garden", "yes" if spec.garden else "no"),
        ("target", str(request["target_duration_seconds"])),
    ]
    canonical = "lyra-video-render-manual-v1\n" + "".join(
        key + "=" + value + "\n" for key, value in rows
    )
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def run_url(run_id):
    if not isinstance(run_id, str) or not re.fullmatch(r"[1-9][0-9]{0,17}", run_id):
        raise ValueError("Invalid GitHub Actions run ID")
    return "https://github.com/spy626/myra-android/actions/runs/" + run_id


def create_success_receipt(request, manifest, video, run_id, sha):
    spec, _ = validate_request(request)
    if manifest.get("spec") != spec.manifest():
        raise ValueError("Rendered scene does not match approved house")
    if manifest.get("expected_frames") != 72 or manifest.get("fps") != 12:
        raise ValueError("Unexpected render frame count or rate")
    if not isinstance(video, bytes) or len(video) < 15000:
        raise ValueError("Missing or tiny rendered MP4")
    if not isinstance(sha, str) or not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise ValueError("Invalid checkout SHA")
    return {
        "receipt_version": 1, "state": "SUCCEEDED", "run_id": run_id,
        "run_url": run_url(run_id), "git_sha": sha,
        "project_id": request["project_id"],
        "target_duration_seconds": request["target_duration_seconds"],
        "approved_input_fingerprint": approved_input_fingerprint(request),
        "rendered_seconds": 6, "mp4_bytes": len(video),
        "mp4_sha256": hashlib.sha256(video).hexdigest(),
    }


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="action", required=True)
    a = sub.add_parser("prepare")
    for k in ("output_json", "project_id", "floors", "color", "roof", "garden", "target"):
        a.add_argument("--" + k.replace("_", "-"), required=True)
    b = sub.add_parser("complete")
    for k in ("request", "manifest", "video", "output_receipt", "run_id", "git_sha"):
        b.add_argument("--" + k.replace("_", "-"), required=True)
    x = parser.parse_args()
    if x.action == "prepare":
        request = make_request(x.project_id, x.floors, x.color, x.roof, x.garden, x.target)
        output = Path(x.output_json)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(request, indent=2) + "\n", encoding="utf-8")
        print("APPROVED_RENDER_INPUT_VALID", request["project_id"])
    else:
        req = json.loads(Path(x.request).read_text(encoding="utf-8"))
        manifest = json.loads(Path(x.manifest).read_text(encoding="utf-8"))
        receipt = create_success_receipt(req, manifest, Path(x.video).read_bytes(),
                                         x.run_id, x.git_sha)
        Path(x.output_receipt).write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
        print("VERIFIED_RENDER_RECEIPT", receipt["run_url"])


if __name__ == "__main__":
    main()
