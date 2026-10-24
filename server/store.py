"""Lightweight SQLite store for conversion history, metrics, and custom presets."""

from __future__ import annotations

import sqlite3
import threading
import uuid
from pathlib import Path

from server.converter import ConversionArtifact
from server.models import BUILTIN_PRESETS, PresetProfile


class ConvertStore:
    def __init__(self, db_path: Path) -> None:
        self.db_path = db_path
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.Lock()
        self._init_schema()

    def _connect(self) -> sqlite3.Connection:
        conn = sqlite3.connect(str(self.db_path))
        conn.row_factory = sqlite3.Row
        return conn

    def _init_schema(self) -> None:
        with self._lock, self._connect() as conn:
            conn.executescript(
                """
                CREATE TABLE IF NOT EXISTS conversions (
                    id TEXT PRIMARY KEY,
                    original_filename TEXT NOT NULL,
                    output_filename TEXT NOT NULL,
                    original_format TEXT NOT NULL,
                    output_format TEXT NOT NULL,
                    mime_type TEXT NOT NULL,
                    orig_width INTEGER NOT NULL,
                    orig_height INTEGER NOT NULL,
                    out_width INTEGER NOT NULL,
                    out_height INTEGER NOT NULL,
                    orig_bytes INTEGER NOT NULL,
                    out_bytes INTEGER NOT NULL,
                    saved_bytes INTEGER NOT NULL,
                    saved_pct REAL NOT NULL,
                    quality INTEGER NOT NULL,
                    strip_exif INTEGER NOT NULL,
                    exif_privacy_mode TEXT NOT NULL DEFAULT 'strip_all',
                    had_exif INTEGER NOT NULL,
                    exif_kept INTEGER NOT NULL,
                    repaired_date INTEGER NOT NULL DEFAULT 0,
                    srgb_normalized INTEGER NOT NULL DEFAULT 1,
                    cutout_applied INTEGER NOT NULL DEFAULT 0,
                    redacted_regions INTEGER NOT NULL DEFAULT 0,
                    sha256 TEXT NOT NULL,
                    output_path TEXT NOT NULL,
                    created_at TEXT NOT NULL
                );

                CREATE TABLE IF NOT EXISTS custom_presets (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    description TEXT NOT NULL,
                    badge TEXT NOT NULL,
                    output_format TEXT NOT NULL,
                    quality INTEGER NOT NULL,
                    resize_mode TEXT NOT NULL,
                    max_megapixels REAL NOT NULL,
                    max_edge_px INTEGER NOT NULL,
                    strip_exif INTEGER NOT NULL,
                    exif_privacy_mode TEXT NOT NULL DEFAULT 'strip_all',
                    target_size_kb INTEGER NOT NULL DEFAULT 0,
                    normalize_srgb INTEGER NOT NULL DEFAULT 1,
                    remove_background INTEGER NOT NULL DEFAULT 0,
                    auto_deskew INTEGER NOT NULL DEFAULT 0,
                    post_sharpen INTEGER NOT NULL DEFAULT 0,
                    auto_levels INTEGER NOT NULL DEFAULT 0,
                    grayscale INTEGER NOT NULL DEFAULT 0,
                    aspect_ratio TEXT NOT NULL DEFAULT 'original',
                    frame_mode TEXT NOT NULL DEFAULT 'blur',
                    watermark_text TEXT NOT NULL DEFAULT '',
                    watermark_tiled INTEGER NOT NULL DEFAULT 0,
                    rename_pattern TEXT NOT NULL DEFAULT '{orig}'
                );
                """
            )
            conv_cols = {r["name"] for r in conn.execute("PRAGMA table_info(conversions)").fetchall()}
            for col, spec in (
                ("exif_privacy_mode", "TEXT NOT NULL DEFAULT 'strip_all'"),
                ("repaired_date", "INTEGER NOT NULL DEFAULT 0"),
                ("srgb_normalized", "INTEGER NOT NULL DEFAULT 1"),
                ("cutout_applied", "INTEGER NOT NULL DEFAULT 0"),
                ("redacted_regions", "INTEGER NOT NULL DEFAULT 0"),
            ):
                if col not in conv_cols:
                    conn.execute(f"ALTER TABLE conversions ADD COLUMN {col} {spec}")

            preset_cols = {r["name"] for r in conn.execute("PRAGMA table_info(custom_presets)").fetchall()}
            for col, spec in (
                ("exif_privacy_mode", "TEXT NOT NULL DEFAULT 'strip_all'"),
                ("target_size_kb", "INTEGER NOT NULL DEFAULT 0"),
                ("normalize_srgb", "INTEGER NOT NULL DEFAULT 1"),
                ("remove_background", "INTEGER NOT NULL DEFAULT 0"),
                ("auto_deskew", "INTEGER NOT NULL DEFAULT 0"),
                ("post_sharpen", "INTEGER NOT NULL DEFAULT 0"),
                ("auto_levels", "INTEGER NOT NULL DEFAULT 0"),
                ("grayscale", "INTEGER NOT NULL DEFAULT 0"),
                ("aspect_ratio", "TEXT NOT NULL DEFAULT 'original'"),
                ("frame_mode", "TEXT NOT NULL DEFAULT 'blur'"),
                ("watermark_text", "TEXT NOT NULL DEFAULT ''"),
                ("watermark_tiled", "INTEGER NOT NULL DEFAULT 0"),
                ("rename_pattern", "TEXT NOT NULL DEFAULT '{orig}'"),
            ):
                if col not in preset_cols:
                    conn.execute(f"ALTER TABLE custom_presets ADD COLUMN {col} {spec}")

    def record_conversion(self, item: ConversionArtifact) -> dict:
        with self._lock, self._connect() as conn:
            conn.execute(
                """
                INSERT INTO conversions (
                    id, original_filename, output_filename, original_format, output_format,
                    mime_type, orig_width, orig_height, out_width, out_height,
                    orig_bytes, out_bytes, saved_bytes, saved_pct, quality,
                    strip_exif, exif_privacy_mode, had_exif, exif_kept, repaired_date,
                    srgb_normalized, cutout_applied, redacted_regions, sha256, output_path, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    item.id,
                    item.original_filename,
                    item.output_filename,
                    item.original_format,
                    item.output_format,
                    item.mime_type,
                    item.orig_width,
                    item.orig_height,
                    item.out_width,
                    item.out_height,
                    item.orig_bytes,
                    item.out_bytes,
                    item.saved_bytes,
                    item.saved_pct,
                    item.quality,
                    int(item.strip_exif),
                    item.exif_privacy_mode,
                    int(item.had_exif),
                    int(item.exif_kept),
                    int(item.repaired_date),
                    int(item.srgb_normalized),
                    int(item.cutout_applied),
                    item.redacted_regions,
                    item.sha256,
                    item.output_path,
                    item.created_at,
                ),
            )
        return self._artifact_to_public(item)

    @staticmethod
    def _row_to_public(row: sqlite3.Row) -> dict:
        item_id = row["id"]
        return {
            "id": item_id,
            "original_filename": row["original_filename"],
            "output_filename": row["output_filename"],
            "original_format": row["original_format"],
            "output_format": row["output_format"],
            "mime_type": row["mime_type"],
            "orig_width": row["orig_width"],
            "orig_height": row["orig_height"],
            "out_width": row["out_width"],
            "out_height": row["out_height"],
            "orig_bytes": row["orig_bytes"],
            "out_bytes": row["out_bytes"],
            "saved_bytes": row["saved_bytes"],
            "saved_pct": row["saved_pct"],
            "quality": row["quality"],
            "strip_exif": bool(row["strip_exif"]),
            "exif_privacy_mode": row["exif_privacy_mode"],
            "had_exif": bool(row["had_exif"]),
            "exif_kept": bool(row["exif_kept"]),
            "repaired_date": bool(row["repaired_date"]),
            "srgb_normalized": bool(row["srgb_normalized"]),
            "cutout_applied": bool(row["cutout_applied"]),
            "redacted_regions": int(row["redacted_regions"]),
            "sha256": row["sha256"],
            "created_at": row["created_at"],
            "preview_url": f"/api/files/{item_id}",
            "download_url": f"/api/files/{item_id}?download=1",
        }

    @staticmethod
    def _artifact_to_public(item: ConversionArtifact) -> dict:
        return {
            "id": item.id,
            "original_filename": item.original_filename,
            "output_filename": item.output_filename,
            "original_format": item.original_format,
            "output_format": item.output_format,
            "mime_type": item.mime_type,
            "orig_width": item.orig_width,
            "orig_height": item.orig_height,
            "out_width": item.out_width,
            "out_height": item.out_height,
            "orig_bytes": item.orig_bytes,
            "out_bytes": item.out_bytes,
            "saved_bytes": item.saved_bytes,
            "saved_pct": item.saved_pct,
            "quality": item.quality,
            "strip_exif": item.strip_exif,
            "exif_privacy_mode": item.exif_privacy_mode,
            "had_exif": item.had_exif,
            "exif_kept": item.exif_kept,
            "repaired_date": item.repaired_date,
            "srgb_normalized": item.srgb_normalized,
            "cutout_applied": item.cutout_applied,
            "redacted_regions": item.redacted_regions,
            "sha256": item.sha256,
            "created_at": item.created_at,
            "preview_url": f"/api/files/{item.id}",
            "download_url": f"/api/files/{item.id}?download=1",
        }

    def list_history(self, limit: int = 100) -> list[dict]:
        with self._lock, self._connect() as conn:
            rows = conn.execute(
                "SELECT * FROM conversions ORDER BY created_at DESC LIMIT ?",
                (limit,),
            ).fetchall()
        return [self._row_to_public(r) for r in rows]

    def get_file_record(self, item_id: str) -> dict | None:
        with self._lock, self._connect() as conn:
            row = conn.execute(
                "SELECT * FROM conversions WHERE id = ?",
                (item_id,),
            ).fetchone()
        if row is None:
            return None
        data = self._row_to_public(row)
        data["output_path"] = row["output_path"]
        return data

    def delete_item(self, item_id: str) -> bool:
        with self._lock, self._connect() as conn:
            row = conn.execute(
                "SELECT output_path FROM conversions WHERE id = ?",
                (item_id,),
            ).fetchone()
            if row is None:
                return False
            conn.execute("DELETE FROM conversions WHERE id = ?", (item_id,))
        Path(row["output_path"]).unlink(missing_ok=True)
        return True

    def clear_history(self) -> int:
        with self._lock, self._connect() as conn:
            rows = conn.execute("SELECT output_path FROM conversions").fetchall()
            count = len(rows)
            conn.execute("DELETE FROM conversions")
        for row in rows:
            Path(row["output_path"]).unlink(missing_ok=True)
        return count

    def summary(self) -> dict:
        with self._lock, self._connect() as conn:
            row = conn.execute(
                """
                SELECT
                    COUNT(*) AS total_count,
                    COALESCE(SUM(orig_bytes), 0) AS total_orig_bytes,
                    COALESCE(SUM(out_bytes), 0) AS total_out_bytes,
                    COALESCE(SUM(MAX(saved_bytes, 0)), 0) AS total_saved_bytes
                FROM conversions
                """
            ).fetchone()
        total_orig = int(row["total_orig_bytes"])
        total_saved = int(row["total_saved_bytes"])
        avg_pct = round((total_saved / total_orig) * 100.0, 1) if total_orig > 0 else 0.0
        return {
            "total_converted": int(row["total_count"]),
            "total_orig_bytes": total_orig,
            "total_out_bytes": int(row["total_out_bytes"]),
            "total_saved_bytes": total_saved,
            "avg_saved_pct": avg_pct,
        }

    def list_presets(self) -> list[dict]:
        presets = [p.to_dict() for p in BUILTIN_PRESETS]
        with self._lock, self._connect() as conn:
            rows = conn.execute("SELECT * FROM custom_presets ORDER BY rowid ASC").fetchall()
        for row in rows:
            presets.append(
                PresetProfile(
                    id=row["id"],
                    name=row["name"],
                    description=row["description"],
                    badge=row["badge"],
                    output_format=row["output_format"],
                    quality=row["quality"],
                    resize_mode=row["resize_mode"],
                    max_megapixels=row["max_megapixels"],
                    max_edge_px=row["max_edge_px"],
                    strip_exif=bool(row["strip_exif"]),
                    exif_privacy_mode=row["exif_privacy_mode"],
                    target_size_kb=int(row["target_size_kb"]),
                    normalize_srgb=bool(row["normalize_srgb"]),
                    remove_background=bool(row["remove_background"]),
                    auto_deskew=bool(row["auto_deskew"]),
                    post_sharpen=bool(row["post_sharpen"]),
                    auto_levels=bool(row["auto_levels"]),
                    grayscale=bool(row["grayscale"]),
                    aspect_ratio=row["aspect_ratio"],
                    frame_mode=row["frame_mode"],
                    watermark_text=row["watermark_text"],
                    watermark_tiled=bool(row["watermark_tiled"]),
                    rename_pattern=row["rename_pattern"],
                    built_in=False,
                ).to_dict()
            )
        return presets

    def create_custom_preset(
        self,
        name: str,
        description: str,
        output_format: str,
        quality: int,
        resize_mode: str,
        max_megapixels: float,
        max_edge_px: int,
        strip_exif: bool,
        exif_privacy_mode: str = "strip_all",
        target_size_kb: int = 0,
        normalize_srgb: bool = True,
        remove_background: bool = False,
        auto_deskew: bool = False,
        post_sharpen: bool = False,
        auto_levels: bool = False,
        grayscale: bool = False,
        aspect_ratio: str = "original",
        frame_mode: str = "blur",
        watermark_text: str = "",
        watermark_tiled: bool = False,
        rename_pattern: str = "{orig}",
    ) -> dict:
        preset_id = f"custom-{uuid.uuid4().hex[:8]}"
        profile = PresetProfile(
            id=preset_id,
            name=name.strip() or "Custom Preset",
            description=description.strip() or f"{output_format.upper()} · {quality}% quality",
            badge="Custom",
            output_format=output_format,  # type: ignore[arg-type]
            quality=quality,
            resize_mode=resize_mode,  # type: ignore[arg-type]
            max_megapixels=max_megapixels,
            max_edge_px=max_edge_px,
            strip_exif=strip_exif,
            exif_privacy_mode=exif_privacy_mode,
            target_size_kb=target_size_kb,
            normalize_srgb=normalize_srgb,
            remove_background=remove_background,
            auto_deskew=auto_deskew,
            post_sharpen=post_sharpen,
            auto_levels=auto_levels,
            grayscale=grayscale,
            aspect_ratio=aspect_ratio,  # type: ignore[arg-type]
            frame_mode=frame_mode,  # type: ignore[arg-type]
            watermark_text=watermark_text,
            watermark_tiled=watermark_tiled,
            rename_pattern=rename_pattern,
            built_in=False,
        )
        with self._lock, self._connect() as conn:
            conn.execute(
                """
                INSERT INTO custom_presets (
                    id, name, description, badge, output_format, quality,
                    resize_mode, max_megapixels, max_edge_px, strip_exif, exif_privacy_mode,
                    target_size_kb, normalize_srgb, remove_background, auto_deskew,
                    post_sharpen, auto_levels, grayscale,
                    aspect_ratio, frame_mode, watermark_text, watermark_tiled, rename_pattern
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    profile.id,
                    profile.name,
                    profile.description,
                    profile.badge,
                    profile.output_format,
                    profile.quality,
                    profile.resize_mode,
                    profile.max_megapixels,
                    profile.max_edge_px,
                    int(profile.strip_exif),
                    profile.exif_privacy_mode,
                    profile.target_size_kb,
                    int(profile.normalize_srgb),
                    int(profile.remove_background),
                    int(profile.auto_deskew),
                    int(profile.post_sharpen),
                    int(profile.auto_levels),
                    int(profile.grayscale),
                    profile.aspect_ratio,
                    profile.frame_mode,
                    profile.watermark_text,
                    int(profile.watermark_tiled),
                    profile.rename_pattern,
                ),
            )
        return profile.to_dict()

    def delete_custom_preset(self, preset_id: str) -> bool:
        with self._lock, self._connect() as conn:
            cur = conn.execute("DELETE FROM custom_presets WHERE id = ?", (preset_id,))
            return cur.rowcount > 0
