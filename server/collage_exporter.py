"""Contact Sheet, Collage & 2-Up ID Card Layout Exporter (#3)."""

from __future__ import annotations

import io
import math

from PIL import Image, ImageDraw, ImageFont

from server.converter import decode_input_image

LAYOUT_GRIDS: dict[str, tuple[int, int]] = {
    "2x1": (2, 1),
    "1x2": (1, 2),
    "2x2": (2, 2),
    "3x3": (3, 3),
}


def build_collage_or_contact_sheet(
    images: list[tuple[str, bytes]],
    layout: str = "2x2",
    output_format: str = "jpeg",
    show_captions: bool = False,
    background: str = "white",
    canvas_width: int = 2400,
) -> tuple[bytes, str, str]:
    """
    Build a Contact Sheet, Side-by-Side Collage, or 2-Up ID Card sheet.
    Returns (file_bytes, mime_type, suggested_filename).
    """
    if not images:
        raise ValueError("Select at least one image to build a collage or contact sheet.")

    clean_layout = layout.lower().strip()
    clean_fmt = output_format.lower().strip()
    if clean_fmt == "jpg":
        clean_fmt = "jpeg"
    if clean_fmt not in ("jpeg", "png", "webp", "pdf"):
        clean_fmt = "jpeg"

    bg_rgb = (15, 23, 42) if background.lower().strip() == "dark" else (255, 255, 255)
    text_rgb = (226, 232, 240) if bg_rgb == (15, 23, 42) else (30, 41, 59)

    decoded_items: list[tuple[str, Image.Image]] = []
    for fname, raw in images:
        dec, _, _ = decode_input_image(raw, fname)
        if dec.mode in ("RGBA", "LA") or ("transparency" in dec.info):
            rgba = dec.convert("RGBA")
            flat = Image.new("RGB", rgba.size, bg_rgb)
            flat.paste(rgba, mask=rgba.split()[3])
            decoded_items.append((fname, flat))
        else:
            decoded_items.append((fname, dec.convert("RGB")))

    if clean_layout == "id_card_2up":
        sheet = _render_id_card_2up(decoded_items, bg_rgb=bg_rgb)
    else:
        cols, rows = LAYOUT_GRIDS.get(clean_layout, (2, 2))
        # Expand rows automatically if more images are supplied for proof sheets
        needed_rows = max(rows, math.ceil(len(decoded_items) / cols))
        sheet = _render_grid_sheet(
            decoded_items,
            cols=cols,
            rows=needed_rows,
            canvas_width=max(800, min(4800, canvas_width)),
            bg_rgb=bg_rgb,
            text_rgb=text_rgb,
            show_captions=show_captions,
        )

    buf = io.BytesIO()
    if clean_fmt == "pdf":
        sheet.save(buf, format="PDF", resolution=150.0)
        return buf.getvalue(), "application/pdf", f"PhotoConvert_{clean_layout}.pdf"
    if clean_fmt == "png":
        sheet.save(buf, format="PNG", optimize=True)
        return buf.getvalue(), "image/png", f"PhotoConvert_{clean_layout}.png"
    if clean_fmt == "webp":
        sheet.save(buf, format="WEBP", quality=88, method=4)
        return buf.getvalue(), "image/webp", f"PhotoConvert_{clean_layout}.webp"

    sheet.save(buf, format="JPEG", quality=88, optimize=True)
    return buf.getvalue(), "image/jpeg", f"PhotoConvert_{clean_layout}.jpg"


def _render_id_card_2up(
    items: list[tuple[str, Image.Image]],
    bg_rgb: tuple[int, int, int],
) -> Image.Image:
    """Render Front + Back ID cards at standard CR80 proportions centered on an A4 150-DPI page."""
    # A4 @ 150 DPI = 1240 x 1754 px; CR80 ID card (85.6mm x 54mm) @ 150 DPI ~= 506 x 319 px
    page_w, page_h = 1240, 1754
    card_w, card_h = 560, 354
    sheet = Image.new("RGB", (page_w, page_h), bg_rgb)
    draw = ImageDraw.Draw(sheet)

    slots_y = [360, 960]
    for idx, (_, img) in enumerate(items[:2]):
        fitted = img.copy()
        fitted.thumbnail((card_w, card_h), Image.Resampling.LANCZOS)
        slot_x = (page_w - card_w) // 2
        slot_y = slots_y[idx]
        ox = slot_x + (card_w - fitted.width) // 2
        oy = slot_y + (card_h - fitted.height) // 2

        # Subtle trim border around each card slot
        draw.rectangle(
            (slot_x - 6, slot_y - 6, slot_x + card_w + 6, slot_y + card_h + 6),
            outline=(203, 213, 225),
            width=2,
        )
        sheet.paste(fitted, (ox, oy))
    return sheet


def _render_grid_sheet(
    items: list[tuple[str, Image.Image]],
    cols: int,
    rows: int,
    canvas_width: int,
    bg_rgb: tuple[int, int, int],
    text_rgb: tuple[int, int, int],
    show_captions: bool,
) -> Image.Image:
    gap = max(16, canvas_width // 90)
    cell_w = max(120, (canvas_width - gap * (cols + 1)) // cols)
    cell_h = int(round(cell_w * 0.78))
    caption_h = max(26, cell_w // 12) if show_captions else 0
    canvas_h = gap + rows * (cell_h + caption_h + gap)

    sheet = Image.new("RGB", (canvas_width, canvas_h), bg_rgb)
    draw = ImageDraw.Draw(sheet)

    font_px = max(13, caption_h - 8) if show_captions else 14
    try:
        font = ImageFont.load_default(size=font_px)
    except TypeError:
        font = ImageFont.load_default()

    for idx, (fname, img) in enumerate(items[: cols * rows]):
        r = idx // cols
        c = idx % cols
        cell_x = gap + c * (cell_w + gap)
        cell_y = gap + r * (cell_h + caption_h + gap)

        fitted = img.copy()
        fitted.thumbnail((cell_w, cell_h), Image.Resampling.LANCZOS)
        ox = cell_x + (cell_w - fitted.width) // 2
        oy = cell_y + (cell_h - fitted.height) // 2
        sheet.paste(fitted, (ox, oy))

        if show_captions:
            short_name = fname if len(fname) <= 28 else (fname[:25] + "...")
            bbox = draw.textbbox((0, 0), short_name, font=font)
            tw = bbox[2] - bbox[0]
            tx = cell_x + max(0, (cell_w - tw) // 2)
            ty = cell_y + cell_h + 4
            draw.text((tx, ty), short_name, font=font, fill=text_rgb)

    return sheet
