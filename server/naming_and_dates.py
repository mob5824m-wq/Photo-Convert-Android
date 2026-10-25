"""Token-based batch renaming and filename-to-EXIF capture date reconstruction (#8)."""

from __future__ import annotations

import re
from datetime import datetime, timezone
from pathlib import Path

# Matches common mobile & camera naming conventions:
#   IMG_20261018_184210.jpg, PXL_20261018_184210123.jpg,
#   Screenshot_2026-10-18-18-42-10.png, IMG-20261018-WA0012.jpg, 2026-10-18 18.42.10.jpg
_DATE_TIME_RE = re.compile(
    r"(?<!\d)(20\d{2}|19\d{2})[-_.]?(0[1-9]|1[0-2])[-_.]?(0[1-9]|[12]\d|3[01])"
    r"(?:[-_ T.]?([01]\d|2[0-3])[-_.]?([0-5]\d)[-_.]?([0-5]\d))?"
)


def infer_exif_datetime_from_filename(filename: str) -> str | None:
    """Return EXIF-formatted 'YYYY:MM:DD HH:MM:SS' parsed from filename if valid."""
    stem = Path(filename).stem
    match = _DATE_TIME_RE.search(stem)
    if not match:
        return None
    year, month, day, hh, mm, ss = match.groups()
    hour = int(hh) if hh is not None else 12
    minute = int(mm) if mm is not None else 0
    second = int(ss) if ss is not None else 0
    try:
        dt = datetime(int(year), int(month), int(day), hour, minute, second)
        return dt.strftime("%Y:%m:%d %H:%M:%S")
    except ValueError:
        return None


def compact_date_from_exif_or_now(exif_dt: str | None) -> str:
    """Convert 'YYYY:MM:DD HH:MM:SS' -> 'YYYYMMDD' for rename tokens."""
    if exif_dt and len(exif_dt) >= 10:
        digits = exif_dt[:10].replace(":", "").replace("-", "")
        if len(digits) == 8 and digits.isdigit():
            return digits
    return datetime.now(timezone.utc).strftime("%Y%m%d")


def render_output_filename(
    pattern: str,
    original_filename: str,
    ext: str,
    width: int,
    height: int,
    seq: int = 1,
    preset_slug: str = "custom",
    exif_dt: str | None = None,
) -> str:
    """Expand rename tokens safely into a clean filename."""
    raw_stem = Path(original_filename).stem or "photo"
    clean_orig = re.sub(r"[^A-Za-z0-9_-]+", "_", raw_stem).strip("_") or "photo"
    clean_fmt = ext.lstrip(".").lower()
    clean_preset = re.sub(r"[^A-Za-z0-9_-]+", "_", preset_slug).strip("_") or "custom"
    date_token = compact_date_from_exif_or_now(exif_dt or infer_exif_datetime_from_filename(original_filename))

    template = (pattern or "{orig}").strip()
    expanded = (
        template.replace("{orig}", clean_orig)
        .replace("{date}", date_token)
        .replace("{preset}", clean_preset)
        .replace("{format}", clean_fmt)
        .replace("{width}", str(width))
        .replace("{height}", str(height))
        .replace("{seq}", f"{max(1, seq):03d}")
    )
    sanitized = re.sub(r"[^A-Za-z0-9._-]+", "_", expanded).strip("._") or clean_orig
    if not ext.startswith("."):
        ext = f".{ext}"
    if sanitized.lower().endswith(ext.lower()):
        sanitized = sanitized[: -len(ext)]
    return f"{sanitized}{ext}"
