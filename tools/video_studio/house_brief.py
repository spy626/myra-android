"""Bounded, offline house brief parser. Not general AI prompt understanding."""
import re
from dataclasses import dataclass, asdict

DEFAULT_PROMPT = "Create a two floor cream mini house with a flat roof and garden"

@dataclass(frozen=True)
class HouseBrief:
    floors: int
    wall_color: str
    roof_style: str
    garden: bool

    def manifest(self):
        return asdict(self)


def parse_brief(raw):
    if not isinstance(raw, str) or not raw.strip() or len(raw) > 500:
        raise ValueError("A mini-house description of 1-500 characters is required")
    if any(ord(c) < 32 or ord(c) == 127 for c in raw):
        raise ValueError("Control characters are not allowed")
    words = re.sub(r"[\s_-]+", " ", raw.lower().strip())
    if not re.search(r"\b(?:house|home|ghar)\b", words):
        raise ValueError("Only house/home/ghar descriptions are supported")
    if re.search(r"\b(?:three|four|five|six|seven|eight|nine|ten|[3-9]|10)\s*(?:floor|storey|story|level)s?\b", words):
        raise ValueError("Only 1- or 2-floor houses are supported")
    levels = set()
    if re.search(r"\b(?:one|single|1|ek)\s*(?:floor|storey|story|level)s?\b", words):
        levels.add(1)
    if re.search(r"\b(?:two|double|2|do)\s*(?:floor|storey|story|level)s?\b", words):
        levels.add(2)
    if len(levels) > 1:
        raise ValueError("Conflicting floor counts")
    colors = [c for c in ("cream", "blue", "terracotta") if re.search(r"\b"+c+r"\b", words)]
    if len(colors) > 1:
        raise ValueError("Choose only one supported wall color")
    roofs = set()
    if re.search(r"\bflat\s*roof\b", words):
        roofs.add("flat")
    if re.search(r"\b(?:gable|pitched|sloped)\s*roof\b", words):
        roofs.add("gable")
    if len(roofs) > 1:
        raise ValueError("Conflicting roof styles")
    garden = not bool(re.search(r"\b(?:without|no)\s+(?:a\s+)?garden\b", words))
    return HouseBrief(next(iter(levels)) if levels else 2,
                      colors[0] if colors else "cream",
                      next(iter(roofs)) if roofs else "flat", garden)
