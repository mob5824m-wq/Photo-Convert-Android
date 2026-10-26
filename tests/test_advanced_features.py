from __future__ import annotations

import http.server
import io
import json
import threading
import time
import zipfile
from pathlib import Path

import piexif
from fastapi.testclient import TestClient
from PIL import Image, ImageDraw

from server.converter import inspect_media_file
from server.main import create_app
from server.motion_photo import attach_motion_mp4_to_jpeg, extract_motion_photo_mp4
from server.watcher import is_within_quiet_hours


def _wait_until(predicate, timeout: float = 20.0, interval: float = 0.2) -> bool:
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        if predicate():
            return True
        time.sleep(interval)
    return predicate()


def test_quiet_hours_schedule_window():
    # Overnight window 23:00 -> 07:00
    assert is_within_quiet_hours(23, 23, 7) is True
    assert is_within_quiet_hours(2, 23, 7) is True
    assert is_within_quiet_hours(6, 23, 7) is True
    assert is_within_quiet_hours(7, 23, 7) is False
    assert is_within_quiet_hours(14, 23, 7) is False
    # Daytime window 09:00 -> 17:00
    assert is_within_quiet_hours(12, 9, 17) is True
    assert is_within_quiet_hours(20, 9, 17) is False


def test_takeout_zip_auto_extract_motion_photo_and_publicshare_privacy(tmp_path: Path):
    app = create_app(data_dir=tmp_path / "nas_adv")
    with TestClient(app) as client:
        client.post(
            "/api/settings",
            json={"ftp_enabled": False, "smb_enabled": False},
        )
        st = client.get("/api/status").json()
        assert st["network"]["pairing_uri"].startswith("pixelnas://pair?")
        inc_dir = Path(st["folders"]["incoming"]["path"])
        out_dir = Path(st["folders"]["output"]["path"])

        # 1. Pixel Motion Photo (20MP JPEG + embedded MP4 tail) -> resized <=16MP WITH MP4 preserved!
        mp_src = tmp_path / "MVIMG_20250910_142000.jpg"
        im = Image.new("RGB", (5000, 4000), (35, 110, 195))
        draw = ImageDraw.Draw(im)
        draw.rectangle([400, 400, 4200, 3200], outline=(255, 220, 90), width=40)
        im.save(mp_src, format="JPEG", quality=92)
        fake_mp4 = (
            b"\x00\x00\x00\x18ftypmp42\x00\x00\x00\x00mp42isom"
            + b"\x00\x00\x02\x00mdat"
            + (b"\x42" * 1024)
        )
        attach_motion_mp4_to_jpeg(mp_src, fake_mp4)
        assert extract_motion_photo_mp4(mp_src) == fake_mp4

        (inc_dir / "StorageSaver" / "Mom").mkdir(parents=True, exist_ok=True)
        (inc_dir / "StorageSaver" / "Mom" / mp_src.name).write_bytes(mp_src.read_bytes())

        assert _wait_until(
            lambda: (out_dir / f"Mom_{mp_src.name}").is_file(),
            timeout=15.0,
        )
        converted_mp = out_dir / f"Mom_{mp_src.name}"
        meta_mp = inspect_media_file(converted_mp, root_dir=out_dir)
        assert meta_mp["width"] * meta_mp["height"] <= 16_000_000
        assert meta_mp["has_motion_video"] is True
        assert extract_motion_photo_mp4(converted_mp) == fake_mp4

        # 2. PublicShare Privacy Scrubber strips GPS & serials while keeping DateTimeOriginal!
        exif_bytes = piexif.dump(
            {
                "0th": {
                    piexif.ImageIFD.Make: b"Google",
                    piexif.ImageIFD.Model: b"Pixel 8 Pro",
                    piexif.ImageIFD.Artist: b"Private Owner",
                },
                "Exif": {
                    piexif.ExifIFD.DateTimeOriginal: b"2026:07:04 19:15:00",
                    piexif.ExifIFD.BodySerialNumber: b"SN-SECRET-999",
                },
                "GPS": {
                    piexif.GPSIFD.GPSLatitudeRef: b"N",
                    piexif.GPSIFD.GPSLatitude: ((43, 1), (27, 1), (0, 1)),
                    piexif.GPSIFD.GPSLongitudeRef: b"W",
                    piexif.GPSIFD.GPSLongitude: ((80, 1), (29, 1), (0, 1)),
                },
                "1st": {},
                "thumbnail": None,
            }
        )
        pub_buf = io.BytesIO()
        Image.new("RGB", (4800, 3600), (190, 80, 70)).save(
            pub_buf, format="JPEG", quality=92, exif=exif_bytes
        )
        (inc_dir / "PublicShare").mkdir(parents=True, exist_ok=True)
        (inc_dir / "PublicShare" / "park_picnic.jpg").write_bytes(pub_buf.getvalue())

        assert _wait_until(
            lambda: (out_dir / "PublicShare" / "park_picnic.jpg").is_file(),
            timeout=15.0,
        )
        pub_meta = inspect_media_file(
            out_dir / "PublicShare" / "park_picnic.jpg", root_dir=out_dir
        )
        assert pub_meta["gps_coords"] == ""
        assert pub_meta["date_taken"] == "2026:07:04 19:15:00"

        # 3. Direct Google Takeout .zip drop -> auto-extracts photo + JSON sidecar & deletes .zip!
        zip_buf = io.BytesIO()
        takeout_img = io.BytesIO()
        Image.new("RGB", (5000, 3800), (40, 170, 120)).save(
            takeout_img, format="JPEG", quality=90
        )
        sidecar_json = json.dumps(
            {
                "photoTakenTime": {"timestamp": "1704110400"},
                "geoData": {"latitude": 35.6762, "longitude": 139.6503},
            }
        ).encode("utf-8")
        with zipfile.ZipFile(zip_buf, "w", compression=zipfile.ZIP_DEFLATED) as zf:
            zf.writestr(
                "Takeout/Google Photos/Photos from 2024/TokyoTower.jpg",
                takeout_img.getvalue(),
            )
            zf.writestr(
                "Takeout/Google Photos/Photos from 2024/TokyoTower.jpg.json",
                sidecar_json,
            )

        up_zip = client.post(
            "/api/upload?profile=StorageSaver",
            files=[("files", ("takeout-20240101.zip", zip_buf.getvalue(), "application/zip"))],
        )
        assert up_zip.status_code == 200
        assert _wait_until(
            lambda: any("TokyoTower.jpg" in p.name for p in out_dir.rglob("*.jpg")),
            timeout=20.0,
        )
        tokyo_out = next(p for p in out_dir.rglob("*TokyoTower.jpg"))
        tokyo_meta = inspect_media_file(tokyo_out, root_dir=out_dir)
        assert "35.6762" in tokyo_meta["gps_coords"]
        assert not (inc_dir / "StorageSaver" / "takeout-20240101.zip").exists()

        # 4. Per-Member Storage Analytics + Streaming .zip Export + Backup/Restore
        analytics = client.get("/api/analytics").json()
        member_names = {m["member"] for m in analytics["members"]}
        assert "StorageSaver/Mom" in member_names
        assert "PublicShare" in member_names

        zip_resp = client.get("/api/export-zip?folder=output&subpath=PublicShare")
        assert zip_resp.status_code == 200
        with zipfile.ZipFile(io.BytesIO(zip_resp.content), "r") as zf:
            assert "park_picnic.jpg" in zf.namelist()

        backup = client.get("/api/backup/export").json()
        assert len(backup["ledger"]["seen_hashes"]) >= 3
        restore = client.post("/api/backup/import", json=backup).json()
        assert restore["restored_settings"] is True


def test_perceptual_dhash_burst_guard_date_sorting_and_webhook(tmp_path: Path):
    received_webhooks: list[bytes] = []

    class _WebhookHandler(http.server.BaseHTTPRequestHandler):
        def do_POST(self):
            length = int(self.headers.get("Content-Length", "0"))
            received_webhooks.append(self.rfile.read(length))
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b"ok")

        def log_message(self, *_args):
            pass

    srv = http.server.HTTPServer(("127.0.0.1", 0), _WebhookHandler)
    th = threading.Thread(target=srv.serve_forever, daemon=True)
    th.start()
    webhook_url = f"http://127.0.0.1:{srv.server_port}/ntfy/pixelnas"

    try:
        app = create_app(data_dir=tmp_path / "nas_dhash")
        with TestClient(app) as client:
            client.post(
                "/api/settings",
                json={
                    "ftp_enabled": False,
                    "smb_enabled": False,
                    "perceptual_dedup_enabled": True,
                    "perceptual_hamming_threshold": 6,
                    "organize_by_date": True,
                    "webhook_url": webhook_url,
                },
            )
            test_wh = client.post("/api/webhook/test").json()
            assert test_wh["ok"] is True
            assert len(received_webhooks) == 1

            st = client.get("/api/status").json()
            inc_dir = Path(st["folders"]["incoming"]["path"])
            out_dir = Path(st["folders"]["output"]["path"])

            # Create patterned image #1 with date 2025-11-08 -> sorted into Output/2025/2025-11/
            base_im = Image.new("RGB", (4600, 3400), (25, 45, 95))
            d = ImageDraw.Draw(base_im)
            d.ellipse([500, 400, 3500, 2900], fill=(230, 140, 50))
            d.rectangle([1200, 900, 2800, 2200], fill=(40, 190, 170))

            b1 = io.BytesIO()
            base_im.save(b1, format="JPEG", quality=94)
            (inc_dir / "PXL_20251108_101500.jpg").write_bytes(b1.getvalue())
            assert _wait_until(
                lambda: (out_dir / "2025" / "2025-11" / "PXL_20251108_101500.jpg").is_file(),
                timeout=15.0,
            )

            # Re-encoded JPEG of same scene (different SHA-256, identical perceptual dHash!) -> skipped!
            b2 = io.BytesIO()
            base_im.save(b2, format="JPEG", quality=78)
            assert b1.getvalue() != b2.getvalue()
            burst_dup = inc_dir / "PXL_20251108_101501_burst.jpg"
            burst_dup.write_bytes(b2.getvalue())

            assert _wait_until(lambda: not burst_dup.exists(), timeout=15.0)
            hist = client.get("/api/history").json()
            assert hist["summary"]["duplicates_skipped_count"] == 1
            assert any("dHash" in (it.get("metadata_note") or "") for it in hist["items"])
    finally:
        srv.shutdown()
