"""Background Watched-Folder Auto-Converter daemon (#4)."""

from __future__ import annotations

import threading
import time
from datetime import datetime, timezone
from pathlib import Path

from server.converter import build_sample_photos, convert_image_bytes
from server.models import RAW_EXTENSIONS, ConvertOptions
from server.store import ConvertStore

WATCHABLE_EXTS = {
    ".jpg",
    ".jpeg",
    ".png",
    ".webp",
    ".avif",
    ".heic",
    ".heif",
    ".bmp",
    ".tif",
    ".tiff",
    *RAW_EXTENSIONS,
}


class WatchedFolderManager:
    def __init__(self, inbox_dir: Path, outputs_dir: Path, store: ConvertStore) -> None:
        self.inbox_dir = inbox_dir
        self.outputs_dir = outputs_dir
        self.store = store
        self.inbox_dir.mkdir(parents=True, exist_ok=True)
        self.outputs_dir.mkdir(parents=True, exist_ok=True)

        self.enabled: bool = False
        self.options: ConvertOptions = ConvertOptions(
            output_format="webp",
            quality=82,
            resize_mode="max_edge",
            max_edge_px=2048,
            strip_exif=True,
            preset_slug="watched",
        )
        self.processed_count: int = 0
        self.last_scan_at: str | None = None
        self._stop_event = threading.Event()
        self._lock = threading.Lock()
        self._thread: threading.Thread | None = None

    def configure(
        self,
        enabled: bool,
        options: ConvertOptions | None = None,
    ) -> dict:
        if options is not None:
            self.options = options.normalized()
        self.enabled = bool(enabled)
        if self.enabled:
            self._ensure_thread_running()
            self.scan_once()
        return self.status()

    def _ensure_thread_running(self) -> None:
        if self._thread is not None and self._thread.is_alive():
            return
        self._stop_event.clear()
        self._thread = threading.Thread(
            target=self._loop,
            name="watched-folder-worker",
            daemon=True,
        )
        self._thread.start()

    def _loop(self) -> None:
        while not self._stop_event.wait(2.0):
            if self.enabled:
                self.scan_once()

    def stop(self) -> None:
        self._stop_event.set()
        if self._thread and self._thread.is_alive():
            self._thread.join(timeout=2.0)

    def scan_once(self) -> list[dict]:
        converted_records: list[dict] = []
        with self._lock:
            self.inbox_dir.mkdir(parents=True, exist_ok=True)
            candidates = sorted(
                p
                for p in self.inbox_dir.iterdir()
                if p.is_file() and not p.name.startswith(".") and p.suffix.lower() in WATCHABLE_EXTS
            )
            for idx, file_path in enumerate(candidates, start=1):
                try:
                    raw = file_path.read_bytes()
                    if not raw:
                        continue
                    artifact = convert_image_bytes(
                        raw,
                        file_path.name,
                        self.options,
                        self.outputs_dir,
                        seq=idx,
                    )
                    rec = self.store.record_conversion(artifact)
                    converted_records.append(rec)
                    file_path.unlink(missing_ok=True)
                    self.processed_count += 1
                except Exception:
                    continue
            self.last_scan_at = datetime.now(timezone.utc).isoformat()
        return converted_records

    def drop_sample_to_inbox(self) -> str:
        name, raw = build_sample_photos()[0]
        target = self.inbox_dir / f"watched_{int(time.time() * 1000)}_{name}"
        target.write_bytes(raw)
        return target.name

    def pending_files(self) -> list[str]:
        if not self.inbox_dir.is_dir():
            return []
        return sorted(
            p.name
            for p in self.inbox_dir.iterdir()
            if p.is_file() and not p.name.startswith(".") and p.suffix.lower() in WATCHABLE_EXTS
        )

    def status(self) -> dict:
        pending = self.pending_files()
        return {
            "enabled": self.enabled,
            "inbox_path": str(self.inbox_dir),
            "pending_count": len(pending),
            "pending_files": pending[:10],
            "processed_count": self.processed_count,
            "last_scan_at": self.last_scan_at,
            "output_format": self.options.output_format,
            "quality": self.options.quality,
        }
