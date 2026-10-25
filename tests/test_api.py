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


def test_batch2_studio_endpoints(tmp_path: Path) -> None:
    """Tests EXIF inspector, Burst De-duplicator, Collage/2-Up ID, Animated WebP/GIF & Web Pack."""
    app = create_app(data_dir=tmp_path / "data2")
    client = TestClient(app)

    sharp_img = Image.new("RGB", (800, 600), (240, 245, 250))
    from PIL import ImageDraw, ImageFilter

    d = ImageDraw.Draw(sharp_img)
    for x in range(40, 760, 40):
        d.line((x, 40, x, 560), fill=(15, 23, 42), width=4)
    sharp_buf = io.BytesIO()
    sharp_img.save(sharp_buf, format="JPEG", quality=92)
    sharp_bytes = sharp_buf.getvalue()

    blurry_buf = io.BytesIO()
    sharp_img.filter(ImageFilter.GaussianBlur(radius=6)).save(blurry_buf, format="JPEG", quality=85)
    blurry_bytes = blurry_buf.getvalue()

    # 1. EXIF & Palette Inspector
    insp = client.post(
        "/api/inspect-exif",
        files={"file": ("hero.jpg", sharp_bytes, "image/jpeg")},
    )
    assert insp.status_code == 200
    ibody = insp.json()
    assert ibody["width"] == 800
    assert len(ibody["palette"]) >= 1
    assert len(ibody["dhash"]) == 16

    # 2. Perceptual Burst De-duplicator keeps sharpest frame
    dedupe = client.post(
        "/api/dedupe",
        files=[
            ("files", ("burst_blur.jpg", blurry_bytes, "image/jpeg")),
            ("files", ("burst_sharp.jpg", sharp_bytes, "image/jpeg")),
        ],
    )
    assert dedupe.status_code == 200
    dbody = dedupe.json()
    assert dbody["keep_indices"] == [1]
    assert dbody["drop_indices"] == [0]

    # 3. 2-Up ID Card PDF & 2x2 Collage JPEG
    id_res = client.post(
        "/api/export-collage",
        files=[
            ("files", ("front.jpg", sharp_bytes, "image/jpeg")),
            ("files", ("back.jpg", blurry_bytes, "image/jpeg")),
        ],
        data={"layout": "id_card_2up", "output_format": "pdf", "show_captions": "true"},
    )
    assert id_res.status_code == 200
    assert id_res.content.startswith(b"%PDF")

    grid_res = client.post(
        "/api/export-collage",
        files=[
            ("files", ("a.jpg", sharp_bytes, "image/jpeg")),
            ("files", ("b.jpg", blurry_bytes, "image/jpeg")),
        ],
        data={"layout": "2x1", "output_format": "webp", "show_captions": "true"},
    )
    assert grid_res.status_code == 200
    assert grid_res.headers["content-type"] == "image/webp"

    # 4. Burst-to-Animated WebP & GIF
    anim_webp = client.post(
        "/api/export-animation",
        files=[
            ("files", ("f1.jpg", sharp_bytes, "image/jpeg")),
            ("files", ("f2.jpg", blurry_bytes, "image/jpeg")),
        ],
        data={"output_format": "webp", "frame_duration_ms": "200", "boomerang": "true"},
    )
    assert anim_webp.status_code == 200
    assert anim_webp.headers["content-type"] == "image/webp"

    anim_gif = client.post(
        "/api/export-animation",
        files=[
            ("files", ("f1.jpg", sharp_bytes, "image/jpeg")),
            ("files", ("f2.jpg", blurry_bytes, "image/jpeg")),
        ],
        data={"output_format": "gif", "frame_duration_ms": "200"},
    )
    assert anim_gif.status_code == 200
    assert anim_gif.content.startswith(b"GIF8")

    # 5. Responsive <picture> Web Pack preview + ZIP download
    wp_prev = client.post(
        "/api/export-webpack/preview",
        files={"file": ("hero_banner.jpg", sharp_bytes, "image/jpeg")},
        data={"quality": "82"},
    )
    assert wp_prev.status_code == 200
    assert "<picture>" in wp_prev.json()["html_snippet"]

    wp_zip = client.post(
        "/api/export-webpack",
        files={"file": ("hero_banner.jpg", sharp_bytes, "image/jpeg")},
        data={"quality": "82"},
    )
    assert wp_zip.status_code == 200
    with zipfile.ZipFile(io.BytesIO(wp_zip.content)) as zf:
        names = set(zf.namelist())
        assert "snippet.html" in names
        assert "palette.json" in names

