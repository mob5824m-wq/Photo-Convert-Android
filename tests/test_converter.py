"""Unit tests for image conversion, dimension math, EXIF privacy, and rollover versioning."""

from __future__ import annotations

import io
from pathlib import Path

import piexif
from PIL import Image

from server.converter import calculate_target_dimensions, convert_image_bytes
from server.models import ConvertOptions
from server.versioning import bump_version, parse_tag


def _make_exif_jpeg(width: int = 2400, height: int = 1800, orientation: int = 1) -> bytes:
    img = Image.new("RGB", (width, height), color=(36, 99, 235))
    exif_bytes = piexif.dump(
        {
            "0th": {
                piexif.ImageIFD.Make: b"Google",
                piexif.ImageIFD.Model: b"Pixel 9",
                piexif.ImageIFD.Orientation: orientation,
            },
            "Exif": {
                piexif.ExifIFD.DateTimeOriginal: b"2026:10:24 12:30:00",
            },
            "GPS": {
                piexif.GPSIFD.GPSLatitudeRef: b"N",
                piexif.GPSIFD.GPSLatitude: ((43, 1), (27, 1), (0, 1)),
            },
        }
    )
    buf = io.BytesIO()
    img.save(buf, format="JPEG", quality=92, exif=exif_bytes)
    return buf.getvalue()


def test_dimension_calculator_modes() -> None:
    assert calculate_target_dimensions(4000, 3000, "original") == (4000, 3000)

    # 24 MP -> <= 16 MP preserving 3:2 ratio
    w, h = calculate_target_dimensions(6000, 4000, "megapixels", max_megapixels=16.0)
    assert w * h <= 16_000_000
    assert abs((w / h) - 1.5) < 0.01

    # Never upscale smaller images
    assert calculate_target_dimensions(1600, 1200, "megapixels", max_megapixels=16.0) == (1600, 1200)
    assert calculate_target_dimensions(1600, 1200, "max_edge", max_edge_px=3840) == (1600, 1200)

    # Portrait & landscape max_edge clamp
    assert calculate_target_dimensions(3000, 4000, "max_edge", max_edge_px=2000) == (1500, 2000)


def test_exif_stripping_and_preservation(tmp_path: Path) -> None:
    src = _make_exif_jpeg(1200, 900, orientation=6)  # 90 deg CW rotated metadata

    # 1. Strip EXIF ON -> no GPS or camera tags, pixels transposed upright (900 x 1200)
    stripped = convert_image_bytes(
        src,
        "camera.jpg",
        ConvertOptions(output_format="jpeg", quality=82, strip_exif=True),
        tmp_path,
    )
    assert (stripped.out_width, stripped.out_height) == (900, 1200)
    assert stripped.had_exif is True
    assert stripped.exif_kept is False
    with Image.open(stripped.output_path) as out_img:
        assert not out_img.info.get("exif")

    # 2. Strip EXIF OFF -> keeps capture date & GPS, resets Orientation=1
    kept = convert_image_bytes(
        src,
        "camera.jpg",
        ConvertOptions(output_format="jpeg", quality=85, strip_exif=False),
        tmp_path,
    )
    assert kept.exif_kept is True
    with Image.open(kept.output_path) as out_img:
        loaded = piexif.load(out_img.info["exif"])
        assert loaded["0th"][piexif.ImageIFD.Orientation] == 1
        assert loaded["Exif"][piexif.ExifIFD.DateTimeOriginal] == b"2026:10:24 12:30:00"
        assert piexif.GPSIFD.GPSLatitude in loaded["GPS"]


def test_transparent_png_to_webp_and_jpeg(tmp_path: Path) -> None:
    rgba = Image.new("RGBA", (600, 400), (37, 99, 235, 128))
    buf = io.BytesIO()
    rgba.save(buf, format="PNG")
    raw_png = buf.getvalue()

    webp_res = convert_image_bytes(
        raw_png,
        "badge.png",
        ConvertOptions(output_format="webp", quality=85),
        tmp_path,
    )
    with Image.open(webp_res.output_path) as w_img:
        assert w_img.mode == "RGBA"

    jpg_res = convert_image_bytes(
        raw_png,
        "badge.png",
        ConvertOptions(output_format="jpeg", quality=85),
        tmp_path,
    )
    with Image.open(jpg_res.output_path) as j_img:
        assert j_img.mode == "RGB"


def test_rollover_versioning() -> None:
    assert bump_version(None) == (1, 0, 0)
    assert bump_version((1, 0, 0)) == (1, 0, 1)
    assert bump_version((1, 0, 9)) == (1, 1, 0)
    assert bump_version((1, 9, 9)) == (2, 0, 0)
    assert parse_tag("v1.2.9") == (1, 2, 9)
    assert parse_tag("invalid") is None
