"""Integration tests for FastAPI endpoints, Loupe preview, PDF exporter, Watched Folder, and ZIP export."""

from __future__ import annotations

import io
import zipfile
from pathlib import Path

from fastapi.testclient import TestClient
from PIL import Image

from server.app import create_app


def _sample_png_bytes() -> bytes:
    img = Image.new("RGB", (1800, 1200), color=(16, 185, 129))
    buf = io.BytesIO()
    img.save(buf, format="PNG", compress_level=1)
    return buf.getvalue()


def test_full_api_workflow(tmp_path: Path) -> None:
    app = create_app(data_dir=tmp_path / "data")
    client = TestClient(app)

    status = client.get("/api/status")
    assert status.status_code == 200
    status_json = status.json()
    assert "webp" in status_json["supported_formats"]
    assert "avif" in status_json["supported_formats"]
    assert "heic" in status_json["supported_formats"]
    assert ".dng" in status_json["raw_extensions"]

    # Presets CRUD (7 built-in presets)
    presets = client.get("/api/presets").json()["presets"]
    assert len(presets) >= 7

    created = client.post(
        "/api/presets",
        json={
            "name": "Portal 400KB",
            "description": "Strict 400KB ceiling with watermark",
            "output_format": "jpeg",
            "quality": 85,
            "target_size_kb": 400,
            "resize_mode": "max_edge",
            "max_megapixels": 16.0,
            "max_edge_px": 1600,
            "strip_exif": True,
            "watermark_text": "© Studio",
            "rename_pattern": "{orig}_{width}x{height}",
        },
    )
    assert created.status_code == 200
    custom_id = created.json()["preset"]["id"]

    # 1. Interactive Split-Screen Loupe preview endpoint
    preview_res = client.post(
        "/api/preview",
        data={
            "sample_only": "true",
            "output_format": "webp",
            "quality": "80",
            "watermark_text": "Preview Test",
        },
    )
    assert preview_res.status_code == 200
    pbody = preview_res.json()
    assert pbody["before_data_url"].startswith("data:image/")
    assert pbody["after_data_url"].startswith("data:image/")

    # 2. Upload & convert with studio enhancements + rename tokens
    upload_res = client.post(
        "/api/convert",
        files=[("files", ("IMG_20261018_120000_banner.png", _sample_png_bytes(), "image/png"))],
        data={
            "output_format": "webp",
            "quality": "82",
            "resize_mode": "max_edge",
            "max_edge_px": "1200",
            "strip_exif": "false",
            "repair_filename_dates": "true",
            "aspect_ratio": "16:9",
            "frame_mode": "blur",
            "watermark_text": "© 2026",
            "rename_pattern": "{date}_{orig}_{seq}",
        },
    )
    assert upload_res.status_code == 200
    body = upload_res.json()
    assert len(body["items"]) == 1
    item = body["items"][0]
    assert item["output_format"] == "WEBP"
    assert item["output_filename"].startswith("20261018_")

    # Serve converted file
    file_res = client.get(item["preview_url"])
    assert file_res.status_code == 200
    assert file_res.headers["content-type"] == "image/webp"

    # 3. Sample batch
    sample_res = client.post("/api/convert/sample", json={"output_format": "webp", "quality": 84})
    assert sample_res.status_code == 200
    assert len(sample_res.json()["items"]) == 3

    # 4. Watched-Folder Auto-Converter endpoints
    cfg_res = client.post(
        "/api/watcher/config",
        json={"enabled": True, "output_format": "webp", "quality": 82},
    )
    assert cfg_res.status_code == 200
    assert cfg_res.json()["enabled"] is True

    drop_res = client.post("/api/watcher/drop-sample")
    assert drop_res.status_code == 200
    assert len(drop_res.json()["auto_converted"]) == 1

    # Turn off background thread cleanly
    client.post("/api/watcher/config", json={"enabled": False})

    # 5. Export history items to multi-page PDF & Scan Sheet
    all_ids = [i["id"] for i in client.get("/api/history").json()["items"]]
    assert len(all_ids) == 5

    pdf_hist_res = client.post(
        "/api/export-pdf/history",
        json={
            "ids": all_ids[:2],
            "page_size": "a4",
            "margin": "small",
            "document_scan_filter": True,
        },
    )
    assert pdf_hist_res.status_code == 200
    assert pdf_hist_res.headers["content-type"] == "application/pdf"
    assert pdf_hist_res.content.startswith(b"%PDF")

    # Direct upload PDF endpoint
    pdf_up_res = client.post(
        "/api/export-pdf",
        files=[("files", ("scan.png", _sample_png_bytes(), "image/png"))],
        data={"page_size": "letter", "margin": "none", "document_scan_filter": "true"},
    )
    assert pdf_up_res.status_code == 200
    assert pdf_up_res.content.startswith(b"%PDF")

    # 6. ZIP download
    zip_res = client.post("/api/download-zip", json={"ids": all_ids})
    assert zip_res.status_code == 200
    with zipfile.ZipFile(io.BytesIO(zip_res.content)) as zf:
        assert len(zf.namelist()) == 5

    # Delete custom preset & clear history
    assert client.delete(f"/api/presets/{custom_id}").status_code == 200
    cleared = client.delete("/api/history")
    assert cleared.status_code == 200
    assert cleared.json()["summary"]["total_converted"] == 0
