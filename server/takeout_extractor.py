"""Safe streaming Google Takeout / photo archive (.zip) unpacker for Incoming folders."""

from __future__ import annotations

import shutil
import uuid
import zipfile
from pathlib import Path
from typing import List, Set

MAX_EXTRACTED_BYTES = 8 * 1024 * 1024 * 1024  # 8 GB safety ceiling per archive


def extract_incoming_zip(
    zip_path: Path,
    allowed_media_exts: Set[str],
    delete_archive_after: bool = True,
) -> List[Path]:
    """
    Stream-extracts supported photos, videos, and `.json` Takeout sidecars from `zip_path`
    into a sibling folder inside `Incoming` (or `Incoming/<Profile>`), preventing zip-slip
    path traversal and cleaning up the `.zip` archive once verified.
    """
    if not zip_path.is_file() or zip_path.suffix.lower() != ".zip":
        return []

    target_root = (zip_path.parent / f"{zip_path.stem}_unpacked").resolve()
    target_root.mkdir(parents=True, exist_ok=True)

    extracted_media: List[Path] = []
    total_written = 0

    with zipfile.ZipFile(zip_path, "r") as zf:
        for member in zf.infolist():
            if member.is_dir():
                continue
            raw_rel = member.filename.replace("\\", "/").lstrip("/")
            parts = [
                p
                for p in raw_rel.split("/")
                if p and p not in (".", "..") and not p.startswith("__MACOSX")
            ]
            if not parts or parts[-1].startswith("."):
                continue

            # Strip top-level "Takeout/Google Photos/" boilerplate prefix if present
            if len(parts) >= 3 and parts[0].lower() == "takeout" and "photos" in parts[1].lower():
                parts = parts[2:]
            elif len(parts) >= 2 and parts[0].lower() == "takeout":
                parts = parts[1:]

            leaf_lower = parts[-1].lower()
            ext = Path(leaf_lower).suffix
            is_json_sidecar = ext == ".json"
            is_media = ext in allowed_media_exts
            if not (is_media or is_json_sidecar):
                continue

            if total_written + member.file_size > MAX_EXTRACTED_BYTES:
                break

            dest = (target_root / Path(*parts)).resolve()
            if not str(dest).startswith(str(target_root)):
                continue
            dest.parent.mkdir(parents=True, exist_ok=True)
            if dest.exists() and is_media:
                dest = dest.parent / f"{dest.stem}_{uuid.uuid4().hex[:6]}{dest.suffix}"

            tmp_dest = dest.parent / f".unzip_{uuid.uuid4().hex}.part"
            try:
                with zf.open(member, "r") as src_f, tmp_dest.open("wb") as out_f:
                    shutil.copyfileobj(src_f, out_f, length=512 * 1024)
                tmp_dest.replace(dest)
                total_written += dest.stat().st_size
                if is_media:
                    extracted_media.append(dest)
            finally:
                if tmp_dest.exists():
                    tmp_dest.unlink(missing_ok=True)

    if delete_archive_after:
        zip_path.unlink(missing_ok=True)

    return extracted_media
