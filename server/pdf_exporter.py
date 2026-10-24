"""Batch Photo-to-PDF & High-Contrast Scan Sheet Exporter (#3)."""

from __future__ import annotations

import io
from typing import Literal

from PIL import Image, ImageEnhance, ImageOps

from server.converter import decode_input_image

PageSize = Literal["a4", "letter", "fit"]
MarginSize = Literal["none", "small", "standard"]

# 150 DPI page dimensions (crisp print/screen balance with compact PDF size)
PAGE_SPECS_PX: dict[str, tuple[int, int]] = {
    "a4": (1240, 1754),
    "letter": (1275, 1650),
}

MARGIN_MAP_PX: dict[str, int] = {
    "none": 0,
    "small": 36,
    "standard": 76,
}


def build_pdf_from_images(
    images: list[tuple[str, bytes]],
    page_size: str = "a4",
    margin: str = "small",
    document_scan_filter: bool = False,
    jpeg_quality: int = 84,
) -> bytes:
    """Pack one or more images into an optimized multi-page PDF."""
    if not images:
        raise ValueError("Provide at least one image to build a PDF.")

    psize = page_size.lower().strip()
    if psize not in ("a4", "letter", "fit"):
        psize = "a4"
    msize = margin.lower().strip()
    margin_px = MARGIN_MAP_PX.get(msize, 36)

    rendered_pages: list[Image.Image] = []

    for filename, raw in images:
        decoded, _, _ = decode_input_image(raw, filename)

        # Composite transparency onto clean white paper
        if decoded.mode in ("RGBA", "LA") or ("transparency" in decoded.info):
            rgba = decoded.convert("RGBA")
            white = Image.new("RGB", rgba.size, (255, 255, 255))
            white.paste(rgba, mask=rgba.split()[3])
            img = white
        else:
            img = decoded.convert("RGB")

        if document_scan_filter:
            gray = ImageOps.grayscale(img)
            gray = ImageOps.autocontrast(gray, cutoff=1)
            gray = ImageEnhance.Contrast(gray).enhance(1.38)
            gray = ImageEnhance.Sharpness(gray).enhance(1.35)
            img = gray.convert("RGB")

        if psize == "fit":
            # Clamp extreme dimensions to keep PDF compact
            if max(img.size) > 2400:
                img.thumbnail((2400, 2400), Image.Resampling.LANCZOS)
            if margin_px > 0:
                page = Image.new(
                    "RGB",
                    (img.width + margin_px * 2, img.height + margin_px * 2),
                    (255, 255, 255),
                )
                page.paste(img, (margin_px, margin_px))
                rendered_pages.append(page)
            else:
                rendered_pages.append(img)
        else:
            base_w, base_h = PAGE_SPECS_PX[psize]
            # Match page orientation to photo orientation
            if img.width > img.height:
                page_w, page_h = base_h, base_w
            else:
                page_w, page_h = base_w, base_h

            avail_w = max(64, page_w - margin_px * 2)
            avail_h = max(64, page_h - margin_px * 2)
            fitted = img.copy()
            fitted.thumbnail((avail_w, avail_h), Image.Resampling.LANCZOS)

            page = Image.new("RGB", (page_w, page_h), (255, 255, 255))
            ox = (page_w - fitted.width) // 2
            oy = (page_h - fitted.height) // 2
            page.paste(fitted, (ox, oy))
            rendered_pages.append(page)

    # Pre-compress pages via JPEG at target quality so embedded PDF streams stay small
    compressed_pages: list[Image.Image] = []
    q = max(40, min(95, int(jpeg_quality)))
    for pg in rendered_pages:
        jbuf = io.BytesIO()
        pg.save(jbuf, format="JPEG", quality=q, optimize=True)
        jbuf.seek(0)
        compressed_pages.append(Image.open(jbuf).convert("RGB"))

    pdf_buf = io.BytesIO()
    first, rest = compressed_pages[0], compressed_pages[1:]
    first.save(
        pdf_buf,
        format="PDF",
        resolution=150.0,
        save_all=True,
        append_images=rest,
    )
    return pdf_buf.getvalue()
