"""Smart aspect-ratio framing (#7) and batch text watermark overlay (#6)."""

from __future__ import annotations

from PIL import Image, ImageDraw, ImageFilter, ImageFont

RATIO_MAP: dict[str, tuple[int, int]] = {
    "1:1": (1, 1),
    "4:5": (4, 5),
    "16:9": (16, 9),
    "9:16": (9, 16),
}


def apply_aspect_frame(
    img: Image.Image,
    aspect_ratio: str,
    frame_mode: str = "blur",
) -> Image.Image:
    """Crop or pad image onto target aspect ratio canvas."""
    if aspect_ratio not in RATIO_MAP:
        return img

    rw, rh = RATIO_MAP[aspect_ratio]
    target_ratio = rw / rh
    src_w, src_h = img.size
    current_ratio = src_w / src_h

    if abs(current_ratio - target_ratio) < 0.005:
        return img

    if frame_mode == "crop":
        if current_ratio > target_ratio:
            new_w = max(1, int(round(src_h * target_ratio)))
            left = (src_w - new_w) // 2
            return img.crop((left, 0, left + new_w, src_h))
        new_h = max(1, int(round(src_w / target_ratio)))
        top = (src_h - new_h) // 2
        return img.crop((0, top, src_w, top + new_h))

    # Pad modes: canvas encompasses entire source image
    if current_ratio > target_ratio:
        canvas_w = src_w
        canvas_h = max(src_h, int(round(src_w / target_ratio)))
    else:
        canvas_h = src_h
        canvas_w = max(src_w, int(round(src_h * target_ratio)))

    has_alpha = img.mode == "RGBA"
    if frame_mode == "blur":
        # Create soft-blurred ambient photo backdrop
        bg_small = img.convert("RGB").resize(
            (max(32, canvas_w // 8), max(32, canvas_h // 8)),
            Image.Resampling.BILINEAR,
        )
        bg_blurred = bg_small.filter(ImageFilter.GaussianBlur(radius=6)).resize(
            (canvas_w, canvas_h),
            Image.Resampling.BICUBIC,
        )
        # Slightly dim backdrop so foreground photo pops cleanly
        dim = Image.new("RGB", (canvas_w, canvas_h), (12, 18, 28))
        canvas = Image.blend(bg_blurred, dim, alpha=0.22)
        if has_alpha:
            canvas = canvas.convert("RGBA")
    else:
        fill = (0, 0, 0) if frame_mode == "black" else (255, 255, 255)
        canvas = Image.new("RGBA" if has_alpha else "RGB", (canvas_w, canvas_h), fill + ((255,) if has_alpha else ()))

    offset_x = (canvas_w - src_w) // 2
    offset_y = (canvas_h - src_h) // 2
    if has_alpha:
        rgba = img.convert("RGBA")
        canvas.paste(rgba, (offset_x, offset_y), mask=rgba.split()[3])
    else:
        canvas.paste(img.convert("RGB"), (offset_x, offset_y))
    return canvas


def apply_text_watermark(
    img: Image.Image,
    text: str,
    position: str = "bottom-right",
    opacity_pct: int = 65,
) -> Image.Image:
    """Render a crisp, proportional text watermark with subtle drop shadow."""
    clean_text = text.strip()
    if not clean_text:
        return img

    base_mode = "RGBA" if img.mode == "RGBA" else "RGB"
    rgba = img.convert("RGBA")
    overlay = Image.new("RGBA", rgba.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(overlay)

    shortest = min(rgba.size)
    font_px = max(14, int(round(shortest * 0.036)))
    try:
        font = ImageFont.load_default(size=font_px)
    except TypeError:
        font = ImageFont.load_default()

    bbox = draw.textbbox((0, 0), clean_text, font=font)
    tw = bbox[2] - bbox[0]
    th = bbox[3] - bbox[1]
    margin = max(12, int(round(shortest * 0.032)))

    w, h = rgba.size
    if position == "top-left":
        x, y = margin, margin
    elif position == "top-right":
        x, y = max(margin, w - tw - margin), margin
    elif position == "bottom-left":
        x, y = margin, max(margin, h - th - margin)
    elif position == "center":
        x, y = max(0, (w - tw) // 2), max(0, (h - th) // 2)
    else:  # bottom-right
        x, y = max(margin, w - tw - margin), max(margin, h - th - margin)

    alpha = int(round(max(10, min(100, opacity_pct)) * 2.55))
    shadow_alpha = int(round(alpha * 0.65))
    shadow_offset = max(1, font_px // 14)

    draw.text((x + shadow_offset, y + shadow_offset), clean_text, font=font, fill=(0, 0, 0, shadow_alpha))
    draw.text((x, y), clean_text, font=font, fill=(255, 255, 255, alpha))

    composited = Image.alpha_composite(rgba, overlay)
    return composited if base_mode == "RGBA" else composited.convert("RGB")
