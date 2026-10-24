"""Core image conversion, resizing, EXIF sanitization, and sample photo generation."""

from __future__ import annotations

import hashlib
import io
import math
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

import piexif
from PIL import Image, ImageDraw, ImageOps

from server.models import ConvertOptions

FORMAT_TO_PILLOW: dict[str, str] = {
    "webp": "WEBP",
    "jpeg": "JPEG",
    "png": "PNG",
    "tiff": "TIFF",
    "bmp": "BMP",
}

FORMAT_TO_EXT: dict[str, str] = {
    "webp": ".webp",
    "jpeg": ".jpg",
    "png": ".png",
    "tiff": ".tiff",
    "bmp": ".bmp",
}

FORMAT_TO_MIME: dict[str, str] = {
    "webp": "image/webp",
    "jpeg": "image/jpeg",
    "png": "image/png",
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
    had_exif: bool
    exif_kept: bool
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


def _sanitize_preserved_exif(raw_exif: bytes | None) -> bytes | None:
    """Keep capture metadata while resetting Orientation=1 after pixel transpose."""
    if not raw_exif:
        return None
    try:
        exif_dict = piexif.load(raw_exif)
        if "0th" in exif_dict and piexif.ImageIFD.Orientation in exif_dict["0th"]:
            exif_dict["0th"][piexif.ImageIFD.Orientation] = 1
        exif_dict.pop("thumbnail", None)
        if "1st" in exif_dict:
            exif_dict["1st"] = {}
        return piexif.dump(exif_dict)
    except Exception:
        return None


def _flatten_alpha_if_needed(img: Image.Image, target_format: str) -> Image.Image:
    """Composite alpha onto clean white when saving to opaque formats (JPEG/BMP)."""
    has_alpha = img.mode in ("RGBA", "LA") or (
        img.mode == "P" and "transparency" in img.info
    )
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


def convert_image_bytes(
    raw_bytes: bytes,
    original_filename: str,
    options: ConvertOptions,
    output_dir: Path,
) -> ConversionArtifact:
    """Convert raw image bytes according to ConvertOptions and persist output."""
    opts = options.normalized()
    output_dir.mkdir(parents=True, exist_ok=True)

    orig_bytes = len(raw_bytes)
    if orig_bytes == 0:
        raise ValueError("Uploaded image file is empty.")

    with Image.open(io.BytesIO(raw_bytes)) as src_raw:
        orig_format = (src_raw.format or Path(original_filename).suffix.lstrip(".") or "IMG").upper()
        raw_exif = src_raw.info.get("exif")
        had_exif = bool(raw_exif)
        transposed = ImageOps.exif_transpose(src_raw)
        orig_w, orig_h = transposed.size

        out_w, out_h = calculate_target_dimensions(
            orig_w,
            orig_h,
            resize_mode=opts.resize_mode,
            max_megapixels=opts.max_megapixels,
            max_edge_px=opts.max_edge_px,
        )

        if (out_w, out_h) != (orig_w, orig_h):
            working = transposed.resize((out_w, out_h), Image.Resampling.LANCZOS)
        else:
            working = transposed.copy()

    prepared = _flatten_alpha_if_needed(working, opts.output_format)
    pillow_fmt = FORMAT_TO_PILLOW[opts.output_format]
    ext = FORMAT_TO_EXT[opts.output_format]

    exif_bytes: bytes | None = None
    if not opts.strip_exif and had_exif and opts.output_format in ("jpeg", "webp", "tiff"):
        exif_bytes = _sanitize_preserved_exif(raw_exif)

    save_kwargs: dict = {}
    if opts.output_format == "jpeg":
        save_kwargs["quality"] = opts.quality
        save_kwargs["optimize"] = True
        save_kwargs["progressive"] = True
    elif opts.output_format == "webp":
        save_kwargs["quality"] = opts.quality
        save_kwargs["method"] = 4
    elif opts.output_format == "png":
        save_kwargs["optimize"] = True
        save_kwargs["compress_level"] = 6
    elif opts.output_format == "tiff":
        save_kwargs["compression"] = "tiff_adobe_deflate"

    if exif_bytes:
        save_kwargs["exif"] = exif_bytes

    buf = io.BytesIO()
    prepared.save(buf, format=pillow_fmt, **save_kwargs)
    out_data = buf.getvalue()

    item_id = uuid.uuid4().hex[:12]
    stem = Path(original_filename).stem or "photo"
    safe_stem = "".join(ch if ch.isalnum() or ch in ("-", "_") else "_" for ch in stem).strip("_") or "photo"
    output_filename = f"{safe_stem}{ext}"
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
        quality=opts.quality,
        strip_exif=opts.strip_exif,
        had_exif=had_exif,
        exif_kept=bool(exif_bytes),
        sha256=digest,
        output_path=str(stored_path),
        created_at=datetime.now(timezone.utc).isoformat(),
    )


def build_sample_photos() -> list[tuple[str, bytes]]:
    """Create 3 crisp sample photos with realistic gradients, shapes, and EXIF metadata."""
    samples: list[tuple[str, bytes]] = []

    # Sample 1: High-res Alpine Dusk camera JPEG (4800 x 3600 = 17.3 MP) with EXIF + GPS
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
    draw1.polygon([(90, 450), (265, 255), (440, 450)], fill=(17, 25, 45))
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
    samples.append(("IMG_20261018_AlpineDusk_17MP.jpg", buf1.getvalue()))

    # Sample 2: Coastal Horizon studio JPEG (3840 x 2560 = 9.8 MP) with EXIF
    w2, h2 = 3840, 2560
    base2 = Image.new("RGB", (480, 320))
    draw2 = ImageDraw.Draw(base2)
    for y in range(320):
        t = y / 319.0
        r = int(14 + t * 52)
        g = int(116 + t * 92)
        b = int(144 + t * 76)
        draw2.line([(0, y), (480, y)], fill=(r, g, b))
    draw2.rounded_rectangle((70, 64, 410, 256), radius=28, outline=(255, 255, 255), width=5)
    draw2.ellipse((195, 110, 285, 200), fill=(240, 253, 250))
    img2 = base2.resize((w2, h2), Image.Resampling.BICUBIC)
    buf2 = io.BytesIO()
    img2.save(buf2, format="JPEG", quality=94, exif=exif_bytes)
    samples.append(("DSC_0492_CoastalStudio_10MP.jpg", buf2.getvalue()))

    # Sample 3: Crisp RGBA Transparent Graphic PNG (2000 x 2000)
    img3 = Image.new("RGBA", (2000, 2000), (0, 0, 0, 0))
    draw3 = ImageDraw.Draw(img3)
    draw3.rounded_rectangle((180, 180, 1820, 1820), radius=380, fill=(37, 99, 235, 255))
    draw3.ellipse((540, 540, 1460, 1460), fill=(255, 255, 255, 235))
    draw3.ellipse((740, 740, 1260, 1260), fill=(15, 23, 42, 255))
    buf3 = io.BytesIO()
    img3.save(buf3, format="PNG", compress_level=1)
    samples.append(("DesignCard_Transparent_2000px.png", buf3.getvalue()))

    return samples
