"""Strict shared Android -> offline Blender 6s request boundary.

This reads a *previously reviewed and locally shared* JSON request.
No network requests, CI dispatch, remote authentication, arbitrary file
paths in payload, commands, text-to-3D models, or paid providers.
"""
import json
import re
from pathlib import Path
from house_brief import HouseBrief

RENDER_KEYS = frozenset({
    "request_version", "renderer", "project_id", "mode",
    "target_duration_seconds", "preview_duration_seconds",
    "width", "height", "fps", "spec",
})
SPEC_KEYS = frozenset({"floors", "wall_color", "roof_style", "garden"})
MAX_FILE_BYTES = 4096


def validate_request(data):
    if type(data) is not dict or set(data) != RENDER_KEYS:
        raise ValueError("Unexpected or missing render request fields")
    expected = {"request_version": 1, "renderer": "blender-mini-house-v1",
                "mode": "construction", "preview_duration_seconds": 6,
                "width": 360, "height": 640, "fps": 12}
    for key, value in expected.items():
        if type(data[key]) is not type(value) or data[key] != value:
            raise ValueError("Unsupported renderer setting: " + key)
    project_id = data["project_id"]
    if type(project_id) is not str or not re.fullmatch(r"[A-Za-z0-9_-]{1,80}", project_id):
        raise ValueError("Invalid project identity")
    duration = data["target_duration_seconds"]
    if type(duration) is not int or duration not in (30, 60):
        raise ValueError("Only a 30s or 60s target with a 6s test render is supported")

    s = data["spec"]
    if type(s) is not dict or set(s) != SPEC_KEYS:
        raise ValueError("Unsupported house spec fields")
    if type(s["floors"]) is not int or s["floors"] not in (1, 2):
        raise ValueError("Unsupported floors")
    if type(s["wall_color"]) is not str or s["wall_color"] not in (
        "cream", "blue", "terracotta",
    ):
        raise ValueError("Unsupported wall color")
    if type(s["roof_style"]) is not str or s["roof_style"] not in ("flat", "gable"):
        raise ValueError("Unsupported roof style")
    if type(s["garden"]) is not bool:
        raise ValueError("Garden must be true or false")
    return HouseBrief(
        s["floors"], s["wall_color"], s["roof_style"], s["garden"],
    ), {
        "request_version": 1,
        "renderer": "blender-mini-house-v1",
        "project_id": project_id,
        "target_duration_seconds": duration,
        "preview_duration_seconds": 6,
    }


def load_render_request(path):
    file = Path(path)
    if not file.is_file() or file.stat().st_size > MAX_FILE_BYTES:
        raise ValueError("Missing or oversized render request")
    raw = file.read_bytes()
    try:
        data = json.loads(raw.decode("utf-8"))
    except (ValueError, UnicodeDecodeError) as error:
        raise ValueError("Invalid UTF-8 JSON request") from error
    return validate_request(data)
