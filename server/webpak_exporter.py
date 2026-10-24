"""Responsive <picture> Web Pack + LQIP & Dominant Color Palette Generator (#10)."""

from __future__ import annotations

import base64
import io
import json
import re
import zipfile
from pathlib import Path

from PIL import Image, ImageFilter

from server.color_profile import normalize_to_srgb
from server.converter import decode_input_image
from server.exif_inspector import extract_dominant_palette

DEFAULT_BREAKPOINTS: tuple[int, ...] = (480, 960, 1920)


def generate_lqip_data_url(img: Image.Image) -> str:
    """Create an ultra-tiny (<600 byte) blurred WebP base64 LQIP placeholder."""
    tiny = img.convert("RGB")
    tiny.thumbnail((28, 28), Image.Resampling.BILINEAR)
    tiny = tiny.filter(ImageFilter.GaussianBlur(radius=1.2))
    buf = io.BytesIO()
    tiny.save(buf, format="WEBP", quality=45)
    b64 = base64.b64encode(buf.getvalue()).decode("ascii")
    return f"data:image/webp;base64,{b64}"


def build_responsive_webpack(
    raw_bytes: bytes,
    filename: str,
    quality: int = 82,
    breakpoints: tuple[int, ...] = DEFAULT_BREAKPOINTS,
) -> tuple[bytes, dict]:
    """
    Generate a deployable responsive asset bundle (.zip) containing:
    - 480w / 960w / 1920w variants in AVIF, WebP, and fallback JPEG
    - snippet.html (<picture> + LQIP background style)
    - palette.json (5 dominant hex colors + LQIP data URI + dimensions)
    """
    decoded, _, _ = decode_input_image(raw_bytes, filename)
    srgb_img, srgb_icc, _ = normalize_to_srgb(decoded)
    rgb = srgb_img.convert("RGB")
    orig_w, orig_h = rgb.size

    stem_raw = Path(filename).stem or "hero"
    stem = re.sub(r"[^A-Za-z0-9_-]+", "-", stem_raw).strip("-").lower() or "image"

    palette = extract_dominant_palette(rgb, 5)
    lqip_uri = generate_lqip_data_url(rgb)

    active_widths = sorted({min(w, orig_w) for w in breakpoints if w <= orig_w} | {min(orig_w, max(breakpoints))})
    q = max(40, min(95, int(quality)))

    avif_srcset: list[str] = []
    webp_srcset: list[str] = []
    jpg_srcset: list[str] = []
    generated_files: list[dict] = []

    zip_buf = io.BytesIO()
    fallback_jpg_name = ""
    fallback_w, fallback_h = orig_w, orig_h

    with zipfile.ZipFile(zip_buf, mode="w", compression=zipfile.ZIP_DEFLATED) as zf:
        for w in active_widths:
            h = max(1, int(round(orig_h * (w / float(orig_w)))))
            resized = rgb.resize((w, h), Image.Resampling.LANCZOS) if w != orig_w else rgb

            for ext, pil_fmt, srcset_bucket in (
                ("avif", "AVIF", avif_srcset),
                ("webp", "WEBP", webp_srcset),
                ("jpg", "JPEG", jpg_srcset),
            ):
                asset_name = f"{stem}-{w}w.{ext}"
                abuf = io.BytesIO()
                save_kw: dict = {"quality": q}
                if srgb_icc:
                    save_kw["icc_profile"] = srgb_icc
                if pil_fmt == "JPEG":
                    save_kw["optimize"] = True
                    save_kw["progressive"] = True
                resized.save(abuf, format=pil_fmt, **save_kw)
                payload = abuf.getvalue()
                zf.writestr(f"images/{asset_name}", payload)
                srcset_bucket.append(f"images/{asset_name} {w}w")
                generated_files.append({"name": asset_name, "width": w, "height": h, "bytes": len(payload)})
                if ext == "jpg":
                    fallback_jpg_name = f"images/{asset_name}"
                    fallback_w, fallback_h = w, h

        snippet_html = (
            f'<picture>\n'
            f'  <source type="image/avif" srcset="{", ".join(avif_srcset)}" sizes="(max-width: 960px) 100vw, 960px" />\n'
            f'  <source type="image/webp" srcset="{", ".join(webp_srcset)}" sizes="(max-width: 960px) 100vw, 960px" />\n'
            f'  <img\n'
            f'    src="{fallback_jpg_name}"\n'
            f'    srcset="{", ".join(jpg_srcset)}"\n'
            f'    width="{fallback_w}"\n'
            f'    height="{fallback_h}"\n'
            f'    loading="lazy"\n'
            f'    decoding="async"\n'
            f'    alt="{stem}"\n'
            f'    style="background: {palette[0]} url(\'{lqip_uri}\') center/cover no-repeat;"\n'
            f'  />\n'
            f'</picture>\n'
        )
        zf.writestr("snippet.html", snippet_html)

        manifest = {
            "name": stem,
            "original_width": orig_w,
            "original_height": orig_h,
            "breakpoints": active_widths,
            "dominant_palette": palette,
            "lqip_data_url": lqip_uri,
            "files": generated_files,
            "html_snippet": snippet_html,
        }
        zf.writestr("palette.json", json.dumps(manifest, indent=2))

    return zip_buf.getvalue(), manifest
