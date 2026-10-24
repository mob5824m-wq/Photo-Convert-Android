"""Unit tests for image conversion, all 10 studio enhancements, EXIF privacy, and rollover versioning."""

from __future__ import annotations

import io
from pathlib import Path

import piexif
from PIL import Image, ImageCms, ImageDraw

from server.converter import (
    calculate_target_dimensions,
    convert_image_bytes,
    generate_loupe_preview,
)
from server.models import ConvertOptions
from server.naming_and_dates import infer_exif_datetime_from_filename, render_output_filename
from server.pdf_exporter import build_pdf_from_images
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


def _make_noisy_photo_jpeg(width: int = 2000, height: int = 1500) -> bytes:
    img = Image.effect_noise((width, height), 75.0).convert("RGB")
    buf = io.BytesIO()
    img.save(buf, format="JPEG", quality=96)
    return buf.getvalue()


def test_dimension_calculator_modes() -> None:
    assert calculate_target_dimensions(4000, 3000, "original") == (4000, 3000)

    w, h = calculate_target_dimensions(6000, 4000, "megapixels", max_megapixels=16.0)
    assert w * h <= 16_000_000
    assert abs((w / h) - 1.5) < 0.01

    assert calculate_target_dimensions(1600, 1200, "megapixels", max_megapixels=16.0) == (1600, 1200)
    assert calculate_target_dimensions(1600, 1200, "max_edge", max_edge_px=3840) == (1600, 1200)
    assert calculate_target_dimensions(3000, 4000, "max_edge", max_edge_px=2000) == (1500, 2000)


def test_exif_stripping_and_preservation(tmp_path: Path) -> None:
    src = _make_exif_jpeg(1200, 900, orientation=6)

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


def test_target_file_size_binary_search_ceiling(tmp_path: Path) -> None:
    """Feature #2: Exact Target File-Size Mode ('Fit Under X KB')."""
    heavy = _make_noisy_photo_jpeg(1800, 1350)
    assert len(heavy) > 250 * 1024

    capped = convert_image_bytes(
        heavy,
        "heavy_portal.jpg",
        ConvertOptions(output_format="jpeg", quality=92, target_size_kb=120),
        tmp_path,
    )
    assert capped.out_bytes <= 120 * 1024


def test_avif_and_heic_roundtrip(tmp_path: Path) -> None:
    """Feature #5: AVIF and HEIC decoding & encoding pipeline."""
    src = _make_exif_jpeg(800, 600)

    avif_item = convert_image_bytes(
        src,
        "shot.jpg",
        ConvertOptions(output_format="avif", quality=78),
        tmp_path,
    )
    assert avif_item.output_format == "AVIF"
    assert avif_item.out_bytes > 0

    # Re-decode AVIF into HEIC and back into WebP
    avif_bytes = Path(avif_item.output_path).read_bytes()
    heic_item = convert_image_bytes(
        avif_bytes,
        "shot.avif",
        ConvertOptions(output_format="heic", quality=80),
        tmp_path,
    )
    assert heic_item.output_format == "HEIC"
    assert heic_item.out_bytes > 0


def test_aspect_framing_and_watermark(tmp_path: Path) -> None:
    """Features #6 & #7: Smart Aspect-Ratio Framing + Batch Watermark Overlay."""
    img = Image.new("RGB", (1200, 800), (20, 40, 80))
    buf = io.BytesIO()
    img.save(buf, format="JPEG", quality=90)

    framed = convert_image_bytes(
        buf.getvalue(),
        "landscape.jpg",
        ConvertOptions(
            output_format="jpeg",
            quality=88,
            aspect_ratio="4:5",
            frame_mode="blur",
            watermark_text="© Photo Convert 2026",
            watermark_position="bottom-right",
            watermark_opacity=75,
        ),
        tmp_path,
    )
    assert abs((framed.out_width / framed.out_height) - 0.8) < 0.02


def test_token_batch_renaming_and_filename_date_repair(tmp_path: Path) -> None:
    """Feature #8: Token-based batch renaming & filename date reconstruction."""
    assert infer_exif_datetime_from_filename("IMG_20261018_184210_Trip.jpg") == "2026:10:18 18:42:10"
    assert infer_exif_datetime_from_filename("IMG-20261020-WA0012.jpg") == "2026:10:20 12:00:00"

    name = render_output_filename(
        "{date}_{orig}_{width}x{height}_{seq}",
        original_filename="IMG_20261018_184210.jpg",
        ext="webp",
        width=1600,
        height=1200,
        seq=4,
    )
    assert name == "20261018_IMG_20261018_184210_1600x1200_004.webp"

    # Verify stripped WhatsApp-style image gets repaired DateTimeOriginal when strip_exif=False
    bare = Image.new("RGB", (640, 480), (80, 120, 200))
    buf = io.BytesIO()
    bare.save(buf, format="JPEG")
    repaired = convert_image_bytes(
        buf.getvalue(),
        "IMG-20261020-WA0099.jpg",
        ConvertOptions(
            output_format="jpeg",
            strip_exif=False,
            repair_filename_dates=True,
            rename_pattern="{date}_{orig}_{seq}",
        ),
        tmp_path,
        seq=2,
    )
    assert repaired.repaired_date is True
    assert repaired.output_filename == "20261020_IMG-20261020-WA0099_002.jpg"
    with Image.open(repaired.output_path) as r_img:
        exif_loaded = piexif.load(r_img.info["exif"])
        assert exif_loaded["Exif"][piexif.ExifIFD.DateTimeOriginal] == b"2026:10:20 12:00:00"


def test_srgb_normalization_and_offline_cutout(tmp_path: Path) -> None:
    """Features #9 & #10: ICC color normalization to sRGB + offline subject cutout."""
    studio = Image.new("RGB", (500, 500), (245, 247, 250))
    draw = ImageDraw.Draw(studio)
    draw.ellipse((130, 130, 370, 370), fill=(220, 38, 38))
    xyz_prof = ImageCms.ImageCmsProfile(ImageCms.createProfile("XYZ")).tobytes()
    buf = io.BytesIO()
    studio.save(buf, format="PNG", icc_profile=xyz_prof)

    cutout = convert_image_bytes(
        buf.getvalue(),
        "product.png",
        ConvertOptions(
            output_format="jpeg",  # Auto-promoted to alpha-capable WebP when cutout enabled
            remove_background=True,
            normalize_srgb=True,
        ),
        tmp_path,
    )
    assert cutout.output_format == "WEBP"
    assert cutout.cutout_applied is True
    assert cutout.srgb_normalized is True
    with Image.open(cutout.output_path) as out_img:
        assert out_img.mode == "RGBA"
        # Corners should now be transparent (alpha == 0) while center subject stays opaque
        assert out_img.getpixel((5, 5))[3] == 0
        assert out_img.getpixel((250, 250))[3] > 200


def test_loupe_preview_and_pdf_builder() -> None:
    """Features #1 & #3: Before/After Loupe preview + Multi-page PDF & Scan Sheet."""
    src = _make_exif_jpeg(1000, 750)
    preview = generate_loupe_preview(
        src,
        "preview.jpg",
        ConvertOptions(output_format="webp", quality=80),
    )
    assert preview["before_data_url"].startswith("data:image/")
    assert preview["after_data_url"].startswith("data:image/")
    assert preview["out_bytes"] > 0

    pdf_bytes = build_pdf_from_images(
        [("p1.jpg", src), ("p2.jpg", src)],
        page_size="a4",
        margin="small",
        document_scan_filter=True,
    )
    assert pdf_bytes.startswith(b"%PDF")
    assert len(pdf_bytes) > 1000


def test_rollover_versioning() -> None:
    assert bump_version(None) == (1, 0, 0)
    assert bump_version((1, 0, 0)) == (1, 0, 1)
    assert bump_version((1, 0, 9)) == (1, 1, 0)
    assert bump_version((1, 9, 9)) == (2, 0, 0)
    assert parse_tag("v1.2.9") == (1, 2, 9)
    assert parse_tag("invalid") is None


def test_selective_gps_scrubber_and_exif_copyright(tmp_path: Path) -> None:
    """Batch 2 #2: Scrub GPS coordinates while keeping camera/date + custom Artist/Copyright."""
    src = _make_exif_jpeg(1200, 900, orientation=1)
    scrubbed = convert_image_bytes(
        src,
        "gps_shot.jpg",
        ConvertOptions(
            output_format="jpeg",
            quality=86,
            exif_privacy_mode="gps_only",
            exif_artist="Studio North",
            exif_copyright="© 2026 Studio North",
        ),
        tmp_path,
    )
    assert scrubbed.exif_kept is True
    assert scrubbed.exif_privacy_mode == "gps_only"
    with Image.open(scrubbed.output_path) as out_img:
        loaded = piexif.load(out_img.info["exif"])
        assert loaded["GPS"] == {}
        assert loaded["0th"][piexif.ImageIFD.Model] == b"Pixel 9"
        assert loaded["0th"][piexif.ImageIFD.Artist] == b"Studio North"
        assert b"2026 Studio North" in loaded["0th"][piexif.ImageIFD.Copyright]


def test_privacy_redaction_deskew_tone_and_tiled_logo_watermark(tmp_path: Path) -> None:
    """Batch 2 #1, #4, #6, #7: Custom redaction boxes, auto-deskew, tone/sharpen & tiled logo."""
    doc = Image.new("RGB", (900, 700), (24, 28, 36))
    draw = ImageDraw.Draw(doc)
    draw.polygon([(140, 110), (770, 90), (790, 590), (120, 610)], fill=(246, 248, 252))
    draw.rectangle((260, 240, 520, 320), fill=(220, 38, 38))
    buf = io.BytesIO()
    doc.save(buf, format="JPEG", quality=92)

    logo = Image.new("RGBA", (120, 48), (0, 0, 0, 0))
    ImageDraw.Draw(logo).rectangle((8, 8, 112, 40), fill=(37, 99, 235, 210))
    logo_buf = io.BytesIO()
    logo.save(logo_buf, format="PNG")

    converted = convert_image_bytes(
        buf.getvalue(),
        "skewed_scan.jpg",
        ConvertOptions(
            output_format="webp",
            quality=85,
            auto_deskew=True,
            post_sharpen=True,
            auto_levels=True,
            grayscale=True,
            redact_boxes="0.20,0.20,0.35,0.25",
            redact_style="solid",
            watermark_text="CONFIDENTIAL",
            watermark_tiled=True,
            watermark_logo_bytes=logo_buf.getvalue(),
        ),
        tmp_path,
    )
    assert converted.redacted_regions >= 1
    assert converted.out_bytes > 0
    # Rectified document should be trimmed smaller than full 900x700 dark canvas
    assert converted.out_width < 880 or converted.out_height < 680

