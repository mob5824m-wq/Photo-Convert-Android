"""Integration tests for FastAPI endpoints, presets, sample batch, and ZIP export."""

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
    assert "webp" in status.json()["supported_formats"]

    # Presets CRUD
    presets = client.get("/api/presets").json()["presets"]
    assert len(presets) >= 5

    created = client.post(
        "/api/presets",
        json={
            "name": "Blog 1200px",
            "description": "Clean blog width",
            "output_format": "webp",
            "quality": 80,
            "resize_mode": "max_edge",
            "max_megapixels": 16.0,
            "max_edge_px": 1200,
            "strip_exif": True,
        },
    )
    assert created.status_code == 200
    custom_id = created.json()["preset"]["id"]

    # Upload & convert
    upload_res = client.post(
        "/api/convert",
        files=[("files", ("hero_banner.png", _sample_png_bytes(), "image/png"))],
        data={
            "output_format": "webp",
            "quality": "82",
            "resize_mode": "max_edge",
            "max_edge_px": "1200",
            "strip_exif": "true",
        },
    )
    assert upload_res.status_code == 200
    body = upload_res.json()
    assert len(body["items"]) == 1
    item = body["items"][0]
    assert item["output_format"] == "WEBP"
    assert item["out_width"] == 1200
    assert item["out_height"] == 800

    # Serve converted file
    file_res = client.get(item["preview_url"])
    assert file_res.status_code == 200
    assert file_res.headers["content-type"] == "image/webp"

    # Sample batch
    sample_res = client.post("/api/convert/sample", json={"output_format": "webp", "quality": 84})
    assert sample_res.status_code == 200
    assert len(sample_res.json()["items"]) == 3

    # ZIP download
    all_ids = [i["id"] for i in client.get("/api/history").json()["items"]]
    assert len(all_ids) == 4
    zip_res = client.post("/api/download-zip", json={"ids": all_ids})
    assert zip_res.status_code == 200
    with zipfile.ZipFile(io.BytesIO(zip_res.content)) as zf:
        assert len(zf.namelist()) == 4

    # Delete custom preset & clear history
    assert client.delete(f"/api/presets/{custom_id}").status_code == 200
    cleared = client.delete("/api/history")
    assert cleared.status_code == 200
    assert cleared.json()["summary"]["total_converted"] == 0
