"""Core image conversion pipeline integrating RAW/AVIF/HEIC, target-KB, sRGB, cutout, deskew, redaction, tone, framing, watermark, and selective EXIF."""

from __future__ import annotations

import base64
import hashlib
import io
import math
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

import piexif
import pillow_heif
from PIL import Image, ImageDraw, ImageOps

from server.color_profile import normalize_to_srgb
from server.cutout import remove_background_offline
from server.deskew import auto_deskew_document
from server.enhancements import (
    apply_aspect_frame,
    apply_logo_watermark,
    apply_text_watermark,
    apply_tone_and_sharpen,
)
from server.exif_inspector import build_selective_exif
from server.models import LOSSLESS_FORMATS, RAW_EXTENSIONS, ConvertOptions
from server.naming_and_dates import infer_exif_datetime_from_filename, render_output_filename
from server.redaction import apply_privacy_redaction

pillow_heif.register_heif_opener()

try:
    import rawpy  # type: ignore[import-not-found]
except ImportError:
    rawpy = None

FORMAT_TO_PILLOW: dict[str, str] = {
    "webp": "WEBP",
    "jpeg": "JPEG",
    "png": "PNG",
    "avif": "AVIF",
    "heic": "HEIF",
    "tiff": "TIFF",
    "bmp": "BMP",
}

FORMAT_TO_EXT: dict[str, str] = {
    "webp": ".webp",
    "jpeg": ".jpg",
    "png": ".png",
    "avif": ".avif",
    "heic": ".heic",
    "tiff": ".tiff",
    "bmp": ".bmp",
}

FORMAT_TO_MIME: dict[str, str] = {
    "webp": "image/webp",
    "jpeg": "image/jpeg",
    "png": "image/png",
    "avif": "image/avif",
    "heic": "image/heic",
    "tiff": "image/tiff",
    "bmp": "image/bmp",
}


@dataclass(frozen=True)
class ConversionArtifact:
    id: str
    original_filename: str
    output_filename: str
    original_format: str
    output_format: str
    mime_type: str
    orig_width: int
    orig_height: int
    out_width: int
    out_height: int
    orig_bytes: int
    out_bytes: int
    saved_bytes: int
    saved_pct: float
    quality: int
    strip_exif: bool
    exif_privacy_mode: str
    had_exif: bool
    exif_kept: bool
    repaired_date: bool
    srgb_normalized: bool
    cutout_applied: bool
    redacted_regions: int
    sha256: str
    output_path: str
    created_at: str


def calculate_target_dimensions(
    width: int,
    height: int,
    resize_mode: str = "original",
    max_megapixels: float = 16.0,
    max_edge_px: int = 2048,
) -> tuple[int, int]:
    """Calculate aspect-preserving dimensions without upscaling."""
    if width <= 0 or height <= 0:
        raise ValueError("Image dimensions must be positive.")

    if resize_mode == "megapixels":
        limit_px = max_megapixels * 1_000_000.0
        current_px = float(width * height)
        if current_px <= limit_px:
            return width, height
        scale = math.sqrt(limit_px / current_px)
        new_w = max(1, int(math.floor(width * scale)))
        new_h = max(1, int(math.floor(height * scale)))
        return new_w, new_h

    if resize_mode == "max_edge":
        longest = max(width, height)
        if longest <= max_edge_px:
            return width, height
        scale = float(max_edge_px) / float(longest)
        new_w = max(1, int(round(width * scale)))
        new_h = max(1, int(round(height * scale)))
        return new_w, new_h

    return width, height


def decode_input_image(raw_bytes: bytes, filename: str) -> tuple[Image.Image, str, bytes | None]:
    """Decode standard, HEIC/AVIF, or RAW camera files (.dng, .cr2, .nef, .arw)."""
    suffix = Path(filename).suffix.lower()
    if suffix in RAW_EXTENSIONS and rawpy is not None:
        try:
            with rawpy.imread(io.BytesIO(raw_bytes)) as raw_file:
                rgb = raw_file.postprocess(use_camera_wb=True, output_bps=8)
            return Image.fromarray(rgb, mode="RGB"), suffix.lstrip(".").upper(), None
        except Exception:
            pass

    with Image.open(io.BytesIO(raw_bytes)) as src_raw:
        orig_format = (src_raw.format or suffix.lstrip(".") or "IMG").upper()
        raw_exif = src_raw.info.get("exif")
        icc = src_raw.info.get("icc_profile")
        transposed = ImageOps.exif_transpose(src_raw).copy()
        if icc:
            transposed.info["icc_profile"] = icc
        return transposed, orig_format, raw_exif


def _prepare_mode_for_format(img: Image.Image, target_format: str) -> Image.Image:
    has_alpha = img.mode in ("RGBA", "LA") or (img.mode == "P" and "transparency" in img.info)
    if target_format in ("jpeg", "bmp"):
        if has_alpha:
            rgba = img.convert("RGBA")
            bg = Image.new("RGB", rgba.size, (255, 255, 255))
            bg.paste(rgba, mask=rgba.split()[3])
            return bg
        return img.convert("RGB")

    if img.mode not in ("RGB", "RGBA", "L"):
        return img.convert("RGBA" if has_alpha else "RGB")
    return img


def _encode_once(
    img: Image.Image,
    target_format: str,
    quality: int,
    exif_bytes: bytes | None,
    icc_bytes: bytes | None,
) -> bytes:
    prepared = _prepare_mode_for_format(img, target_format)
    pillow_fmt = FORMAT_TO_PILLOW[target_format]
    save_kwargs: dict = {}

    if target_format == "jpeg":
        save_kwargs["quality"] = quality
        save_kwargs["optimize"] = True
        save_kwargs["progressive"] = True
    elif target_format == "webp":
        save_kwargs["quality"] = quality
        save_kwargs["method"] = 4
    elif target_format in ("avif", "heic"):
        save_kwargs["quality"] = quality
    elif target_format == "png":
        save_kwargs["optimize"] = True
        save_kwargs["compress_level"] = 6
    elif target_format == "tiff":
        save_kwargs["compression"] = "tiff_adobe_deflate"

    if exif_bytes and target_format in ("jpeg", "webp", "avif", "heic", "tiff"):
        save_kwargs["exif"] = exif_bytes
    if icc_bytes and target_format in ("jpeg", "webp", "png", "avif", "heic", "tiff"):
        save_kwargs["icc_profile"] = icc_bytes

    buf = io.BytesIO()
    prepared.save(buf, format=pillow_fmt, **save_kwargs)
    return buf.getvalue()


def _encode_with_target_budget(
    img: Image.Image,
    opts: ConvertOptions,
    exif_bytes: bytes | None,
    icc_bytes: bytes | None,
) -> tuple[bytes, int, int, int]:
    """Encode normally or binary-search quality + scale to fit under target_size_kb."""
    if opts.target_size_kb <= 0:
        data = _encode_once(img, opts.output_format, opts.quality, exif_bytes, icc_bytes)
        return data, opts.quality, img.width, img.height

    limit_bytes = opts.target_size_kb * 1024
    working = img
    best_bytes = b""
    best_q = opts.quality

    for scale_step in range(8):
        if scale_step > 0:
            factor = 0.84 ** scale_step
            nw = max(64, int(round(img.width * factor)))
            nh = max(64, int(round(img.height * factor)))
            working = img.resize((nw, nh), Image.Resampling.LANCZOS)

        if opts.output_format in LOSSLESS_FORMATS:
            candidate = _encode_once(working, opts.output_format, 100, exif_bytes, icc_bytes)
            best_bytes, best_q = candidate, 100
            if len(candidate) <= limit_bytes:
                return candidate, 100, working.width, working.height
            continue

        low, high = 12, min(95, max(opts.quality, 85))
        step_match: bytes | None = None
        step_q = low

        min_try = _encode_once(working, opts.output_format, low, exif_bytes, icc_bytes)
        best_bytes, best_q = min_try, low
        if len(min_try) > limit_bytes:
            continue

        while low <= high:
            mid = (low + high) // 2
            encoded = _encode_once(working, opts.output_format, mid, exif_bytes, icc_bytes)
            if len(encoded) <= limit_bytes:
                step_match = encoded
                step_q = mid
                low = mid + 1
            else:
                high = mid - 1

        if step_match is not None:
            return step_match, step_q, working.width, working.height

    return best_bytes, best_q, working.width, working.height


def _transform_pipeline(
    raw_bytes: bytes,
    original_filename: str,
    opts: ConvertOptions,
) -> tuple[Image.Image, str, int, int, bytes | None, bytes | None, bool, bool, bool, int, str | None]:
    if not raw_bytes:
        raise ValueError("Uploaded image file is empty.")

    decoded, orig_format, raw_exif = decode_input_image(raw_bytes, original_filename)
    orig_w, orig_h = decoded.size

    srgb_icc: bytes | None = None
    srgb_converted = False
    working = decoded
    if opts.normalize_srgb:
        working, srgb_icc, srgb_converted = normalize_to_srgb(working)

    if opts.auto_deskew:
        working = auto_deskew_document(working)

    redacted_count = 0
    if opts.redact_faces or opts.redact_text or opts.redact_boxes:
        working, redacted_count = apply_privacy_redaction(
            working,
            redact_faces=opts.redact_faces,
            redact_text=opts.redact_text,
            redact_boxes_spec=opts.redact_boxes,
            style=opts.redact_style,
        )

    cutout_done = False
    if opts.remove_background:
        working = remove_background_offline(working)
        cutout_done = True

    target_w, target_h = calculate_target_dimensions(
        working.width,
        working.height,
        resize_mode=opts.resize_mode,
        max_megapixels=opts.max_megapixels,
        max_edge_px=opts.max_edge_px,
    )
    was_downscaled = (target_w, target_h) != working.size
    if was_downscaled:
        working = working.resize((target_w, target_h), Image.Resampling.LANCZOS)

    if opts.auto_levels or opts.grayscale or opts.post_sharpen:
        working = apply_tone_and_sharpen(
            working,
            auto_levels=opts.auto_levels,
            grayscale=opts.grayscale,
            post_sharpen=opts.post_sharpen,
        )

    if opts.aspect_ratio != "original":
        working = apply_aspect_frame(working, opts.aspect_ratio, opts.frame_mode)

    if opts.watermark_logo_bytes:
        working = apply_logo_watermark(
            working,
            opts.watermark_logo_bytes,
            position=opts.watermark_position,
            opacity_pct=opts.watermark_opacity,
            tiled=opts.watermark_tiled,
        )

    if opts.watermark_text:
        working = apply_text_watermark(
            working,
            opts.watermark_text,
            position=opts.watermark_position,
            opacity_pct=opts.watermark_opacity,
            tiled=opts.watermark_tiled,
        )

    exif_bytes, dt_val, repaired_date = build_selective_exif(
        raw_exif=raw_exif,
        filename=original_filename,
        privacy_mode=opts.exif_privacy_mode,
        repair_filename_dates=opts.repair_filename_dates,
        exif_artist=opts.exif_artist,
        exif_copyright=opts.exif_copyright,
    )
    inferred_or_exif_dt: str | None = dt_val or infer_exif_datetime_from_filename(original_filename)

    return (
        working,
        orig_format,
        orig_w,
        orig_h,
        raw_exif,
        exif_bytes,
        repaired_date,
        srgb_converted,
        cutout_done,
        redacted_count,
        inferred_or_exif_dt,
    )


def convert_image_bytes(
    raw_bytes: bytes,
    original_filename: str,
    options: ConvertOptions,
    output_dir: Path,
    seq: int = 1,
) -> ConversionArtifact:
    """Run full conversion & enhancement pipeline and write result to output_dir."""
    opts = options.normalized()
    output_dir.mkdir(parents=True, exist_ok=True)
    orig_bytes = len(raw_bytes)

    (
        working,
        orig_format,
        orig_w,
        orig_h,
        raw_exif,
        exif_bytes,
        repaired_date,
        srgb_converted,
        cutout_done,
        redacted_count,
        exif_dt,
    ) = _transform_pipeline(raw_bytes, original_filename, opts)

    icc_bytes = normalize_to_srgb(Image.new("RGB", (2, 2)))[1] if opts.normalize_srgb else None
    out_data, used_quality, out_w, out_h = _encode_with_target_budget(
        working,
        opts,
        exif_bytes=exif_bytes,
        icc_bytes=icc_bytes,
    )

    ext = FORMAT_TO_EXT[opts.output_format]
    output_filename = render_output_filename(
        pattern=opts.rename_pattern,
        original_filename=original_filename,
        ext=ext,
        width=out_w,
        height=out_h,
        seq=seq,
        preset_slug=opts.preset_slug,
        exif_dt=exif_dt,
    )

    item_id = uuid.uuid4().hex[:12]
    stored_path = output_dir / f"{item_id}_{output_filename}"
    stored_path.write_bytes(out_data)

    out_bytes = len(out_data)
    saved_bytes = orig_bytes - out_bytes
    saved_pct = round((saved_bytes / orig_bytes) * 100.0, 1) if orig_bytes > 0 else 0.0
    digest = hashlib.sha256(out_data).hexdigest()

    return ConversionArtifact(
        id=item_id,
        original_filename=original_filename,
        output_filename=output_filename,
        original_format=orig_format,
        output_format=opts.output_format.upper(),
        mime_type=FORMAT_TO_MIME[opts.output_format],
        orig_width=orig_w,
        orig_height=orig_h,
        out_width=out_w,
        out_height=out_h,
        orig_bytes=orig_bytes,
        out_bytes=out_bytes,
        saved_bytes=saved_bytes,
        saved_pct=saved_pct,
        quality=used_quality,
        strip_exif=opts.strip_exif,
        exif_privacy_mode=opts.exif_privacy_mode,
        had_exif=bool(raw_exif),
        exif_kept=bool(exif_bytes),
        repaired_date=repaired_date,
        srgb_normalized=opts.normalize_srgb or srgb_converted,
        cutout_applied=cutout_done,
        redacted_regions=redacted_count,
        sha256=digest,
        output_path=str(stored_path),
        created_at=datetime.now(timezone.utc).isoformat(),
    )


def generate_loupe_preview(
    raw_bytes: bytes,
    original_filename: str,
    options: ConvertOptions,
) -> dict:
    """Generate side-by-side Split-Screen Before/After Loupe preview data."""
    opts = options.normalized()
    orig_bytes = len(raw_bytes)
    decoded_orig, orig_format, _ = decode_input_image(raw_bytes, original_filename)
    orig_w, orig_h = decoded_orig.size

    orig_preview_img = decoded_orig.copy()
    if max(orig_preview_img.size) > 1600:
        orig_preview_img.thumbnail((1600, 1600), Image.Resampling.LANCZOS)
    orig_buf = io.BytesIO()
    orig_preview_img.save(orig_buf, format="WEBP", quality=92)
    orig_data_url = f"data:image/webp;base64,{base64.b64encode(orig_buf.getvalue()).decode('ascii')}"

    working, _, _, _, _, exif_bytes, _, _, _, redacted_count, _ = _transform_pipeline(
        raw_bytes, original_filename, opts
    )
    icc_bytes = normalize_to_srgb(Image.new("RGB", (2, 2)))[1] if opts.normalize_srgb else None
    out_data, used_quality, out_w, out_h = _encode_with_target_budget(
        working,
        opts,
        exif_bytes=exif_bytes,
        icc_bytes=icc_bytes,
    )
    out_bytes = len(out_data)

    with Image.open(io.BytesIO(out_data)) as out_decoded:
        after_view = out_decoded.copy()
        if max(after_view.size) > 1600:
            after_view.thumbnail((1600, 1600), Image.Resampling.LANCZOS)
        after_buf = io.BytesIO()
        after_view.save(after_buf, format="PNG")
    after_data_url = f"data:image/png;base64,{base64.b64encode(after_buf.getvalue()).decode('ascii')}"

    saved_bytes = orig_bytes - out_bytes
    saved_pct = round((saved_bytes / orig_bytes) * 100.0, 1) if orig_bytes > 0 else 0.0

    return {
        "filename": original_filename,
        "original_format": orig_format,
        "output_format": opts.output_format.upper(),
        "orig_width": orig_w,
        "orig_height": orig_h,
        "out_width": out_w,
        "out_height": out_h,
        "orig_bytes": orig_bytes,
        "out_bytes": out_bytes,
        "saved_bytes": saved_bytes,
        "saved_pct": saved_pct,
        "used_quality": used_quality,
        "redacted_regions": redacted_count,
        "before_data_url": orig_data_url,
        "after_data_url": after_data_url,
    }


def build_sample_photos() -> list[tuple[str, bytes]]:
    """Create 3 crisp sample photos (high-res camera EXIF, studio subject on clean backdrop, transparent graphic)."""
    samples: list[tuple[str, bytes]] = []

    w1, h1 = 4800, 3600
    base1 = Image.new("RGB", (600, 450))
    draw1 = ImageDraw.Draw(base1)
    for y in range(450):
        t = y / 449.0
        r = int(24 + t * 212)
        g = int(42 + t * 96)
        b = int(98 + (1.0 - t) * 95)
        draw1.line([(0, y), (600, y)], fill=(r, g, b))
    draw1.ellipse((410, 80, 505, 175), fill=(255, 224, 158))
    draw1.polygon([(0, 450), (0, 310), (185, 195), (355, 325), (490, 220), (600, 295), (600, 450)], fill=(25, 36, 62))
    img1 = base1.resize((w1, h1), Image.Resampling.BICUBIC)

    exif_bytes = piexif.dump(
        {
            "0th": {
                piexif.ImageIFD.Make: b"Google",
                piexif.ImageIFD.Model: b"Pixel 9 Pro",
                piexif.ImageIFD.Orientation: 1,
            },
            "Exif": {
                piexif.ExifIFD.DateTimeOriginal: b"2026:10:18 18:42:10",
                piexif.ExifIFD.ISOSpeedRatings: 100,
                piexif.ExifIFD.FNumber: (18, 10),
                piexif.ExifIFD.ExposureTime: (1, 250),
                piexif.ExifIFD.FocalLength: (69, 10),
            },
            "GPS": {
                piexif.GPSIFD.GPSLatitudeRef: b"N",
                piexif.GPSIFD.GPSLatitude: ((43, 1), (27, 1), (5, 1)),
                piexif.GPSIFD.GPSLongitudeRef: b"W",
                piexif.GPSIFD.GPSLongitude: ((80, 1), (29, 1), (35, 1)),
            },
        }
    )
    buf1 = io.BytesIO()
    img1.save(buf1, format="JPEG", quality=95, exif=exif_bytes)
    samples.append(("IMG_20261018_184210_AlpineDusk.jpg", buf1.getvalue()))

    img2 = Image.new("RGB", (2400, 1800), (242, 245, 250))
    draw2 = ImageDraw.Draw(img2)
    draw2.rounded_rectangle((720, 340, 1680, 1460), radius=140, fill=(37, 99, 235))
    draw2.ellipse((940, 580, 1460, 1100), fill=(255, 255, 255))
    draw2.ellipse((1060, 700, 1340, 980), fill=(15, 23, 42))
    buf2 = io.BytesIO()
    img2.save(buf2, format="JPEG", quality=94)
    samples.append(("IMG-20261020-WA0042_StudioObject.jpg", buf2.getvalue()))

    img3 = Image.new("RGBA", (2000, 2000), (0, 0, 0, 0))
    draw3 = ImageDraw.Draw(img3)
    draw3.rounded_rectangle((180, 180, 1820, 1820), radius=380, fill=(16, 185, 129, 255))
    draw3.ellipse((540, 540, 1460, 1460), fill=(255, 255, 255, 235))
    buf3 = io.BytesIO()
    img3.save(buf3, format="PNG", compress_level=1)
    samples.append(("Screenshot_2026-10-22-09-15-00_Badge.png", buf3.getvalue()))

    return samples
