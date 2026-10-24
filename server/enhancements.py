"""Smart aspect-ratio framing (#7), tone/sharpening (#6), and text/logo/tiled watermarks (#7)."""

from __future__ import annotations

import io
import math

from PIL import Image, ImageDraw, ImageFilter, ImageFont, ImageOps

RATIO_MAP: dict[str, tuple[int, int]] = {
    "1:1": (1, 1),
    "4:5": (4, 5),
    "16:9": (16, 9),
    "9:16": (9, 16),
}


def apply_tone_and_sharpen(
    img: Image.Image,
    auto_levels: bool = False,
    grayscale: bool = False,
    post_sharpen: bool = False,
) -> Image.Image:
    """Apply optional auto-levels contrast stretch, monochrome conversion, and post-resize micro-sharpening."""
    has_alpha = img.mode == "RGBA"
    alpha_ch = img.split()[3] if has_alpha else None
    working = img.convert("RGB")

    if auto_levels:
        working = ImageOps.autocontrast(working, cutoff=0.5)

    if grayscale:
        working = ImageOps.grayscale(working).convert("RGB")

    if post_sharpen:
        working = working.filter(ImageFilter.UnsharpMask(radius=1.1, percent=115, threshold=2))

    if has_alpha and alpha_ch is not None:
        rgba = working.convert("RGBA")
        rgba.putalpha(alpha_ch)
        return rgba
    return working


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

    if current_ratio > target_ratio:
        canvas_w = src_w
        canvas_h = max(src_h, int(round(src_w / target_ratio)))
    else:
        canvas_h = src_h
        canvas_w = max(src_w, int(round(src_h * target_ratio)))

    has_alpha = img.mode == "RGBA"
    if frame_mode == "blur":
        bg_small = img.convert("RGB").resize(
            (max(32, canvas_w // 8), max(32, canvas_h // 8)),
            Image.Resampling.BILINEAR,
        )
        bg_blurred = bg_small.filter(ImageFilter.GaussianBlur(radius=6)).resize(
            (canvas_w, canvas_h),
            Image.Resampling.BICUBIC,
        )
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
    tiled: bool = False,
) -> Image.Image:
    """Render a crisp text watermark (single anchor or 45-degree repeating proof grid)."""
    clean_text = text.strip()
    if not clean_text:
        return img

    base_mode = "RGBA" if img.mode == "RGBA" else "RGB"
    rgba = img.convert("RGBA")
    w, h = rgba.size

    shortest = min(w, h)
    font_px = max(14, int(round(shortest * (0.032 if tiled else 0.036))))
    try:
        font = ImageFont.load_default(size=font_px)
    except TypeError:
        font = ImageFont.load_default()

    alpha = int(round(max(10, min(100, opacity_pct)) * 2.55))
    shadow_alpha = int(round(alpha * 0.65))
    shadow_offset = max(1, font_px // 14)

    if tiled:
        diag = int(math.hypot(w, h)) + 120
        tile_layer = Image.new("RGBA", (diag, diag), (0, 0, 0, 0))
        tdraw = ImageDraw.Draw(tile_layer)
        bbox = tdraw.textbbox((0, 0), clean_text, font=font)
        tw = max(40, bbox[2] - bbox[0])
        th = max(20, bbox[3] - bbox[1])
        step_x = int(tw * 1.85)
        step_y = int(th * 3.4)

        for row_idx, y in enumerate(range(0, diag, step_y)):
            x_shift = (step_x // 2) if (row_idx % 2 == 1) else 0
            for x in range(-x_shift, diag, step_x):
                tdraw.text((x + shadow_offset, y + shadow_offset), clean_text, font=font, fill=(0, 0, 0, shadow_alpha))
                tdraw.text((x, y), clean_text, font=font, fill=(255, 255, 255, alpha))

        rotated = tile_layer.rotate(35, resample=Image.Resampling.BILINEAR)
        cx = (rotated.width - w) // 2
        cy = (rotated.height - h) // 2
        overlay = rotated.crop((cx, cy, cx + w, cy + h))
    else:
        overlay = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        draw = ImageDraw.Draw(overlay)
        bbox = draw.textbbox((0, 0), clean_text, font=font)
        tw = bbox[2] - bbox[0]
        th = bbox[3] - bbox[1]
        margin = max(12, int(round(shortest * 0.032)))

        if position == "top-left":
            x, y = margin, margin
        elif position == "top-right":
            x, y = max(margin, w - tw - margin), margin
        elif position == "bottom-left":
            x, y = margin, max(margin, h - th - margin)
        elif position == "center":
            x, y = max(0, (w - tw) // 2), max(0, (h - th) // 2)
        else:
            x, y = max(margin, w - tw - margin), max(margin, h - th - margin)

        draw.text((x + shadow_offset, y + shadow_offset), clean_text, font=font, fill=(0, 0, 0, shadow_alpha))
        draw.text((x, y), clean_text, font=font, fill=(255, 255, 255, alpha))

    composited = Image.alpha_composite(rgba, overlay)
    return composited if base_mode == "RGBA" else composited.convert("RGB")


def apply_logo_watermark(
    img: Image.Image,
    logo_bytes: bytes,
    position: str = "bottom-right",
    opacity_pct: int = 75,
    tiled: bool = False,
) -> Image.Image:
    """Overlay a PNG/WebP brand logo (anchored or 45-degree tiled proof grid)."""
    if not logo_bytes:
        return img

    with Image.open(io.BytesIO(logo_bytes)) as raw_logo:
        logo = raw_logo.convert("RGBA")

    base_mode = "RGBA" if img.mode == "RGBA" else "RGB"
    rgba = img.convert("RGBA")
    w, h = rgba.size
    shortest = min(w, h)

    max_logo_edge = max(32, int(round(shortest * 0.16)))
    logo.thumbnail((max_logo_edge, max_logo_edge), Image.Resampling.LANCZOS)

    opacity_scale = max(0.10, min(1.0, opacity_pct / 100.0))
    if opacity_scale < 0.999:
        r, g, b, a = logo.split()
        a = a.point(lambda px: int(round(px * opacity_scale)))
        logo = Image.merge("RGBA", (r, g, b, a))

    lw, lh = logo.size
    margin = max(12, int(round(shortest * 0.032)))

    if tiled:
        diag = int(math.hypot(w, h)) + 160
        tile_layer = Image.new("RGBA", (diag, diag), (0, 0, 0, 0))
        step_x = max(lw + 48, int(lw * 2.2))
        step_y = max(lh + 48, int(lh * 2.2))
        for row_idx, y in enumerate(range(0, diag, step_y)):
            x_shift = (step_x // 2) if (row_idx % 2 == 1) else 0
            for x in range(-x_shift, diag - lw, step_x):
                if x >= 0 and y + lh <= diag:
                    tile_layer.paste(logo, (x, y), mask=logo.split()[3])
        rotated = tile_layer.rotate(35, resample=Image.Resampling.BILINEAR)
        cx = (rotated.width - w) // 2
        cy = (rotated.height - h) // 2
        overlay = rotated.crop((cx, cy, cx + w, cy + h))
        composited = Image.alpha_composite(rgba, overlay)
    else:
        overlay = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        if position == "top-left":
            x, y = margin, margin
        elif position == "top-right":
            x, y = max(margin, w - lw - margin), margin
        elif position == "bottom-left":
            x, y = margin, max(margin, h - lh - margin)
        elif position == "center":
            x, y = max(0, (w - lw) // 2), max(0, (h - lh) // 2)
        else:
            x, y = max(margin, w - lw - margin), max(margin, h - lh - margin)
        overlay.paste(logo, (x, y), mask=logo.split()[3])
        composited = Image.alpha_composite(rgba, overlay)

    return composited if base_mode == "RGBA" else composited.convert("RGB")
