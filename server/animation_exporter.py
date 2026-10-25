"""Burst-to-Animated WebP / GIF Maker (#5)."""

from __future__ import annotations

import io

from PIL import Image

from server.converter import decode_input_image


def build_animated_sequence(
    images: list[tuple[str, bytes]],
    output_format: str = "webp",
    frame_ms: int = 240,
    boomerang: bool = False,
    max_edge_px: int = 960,
    loop: int = 0,
) -> tuple[bytes, str, str]:
    """
    Assemble 2+ photos into a looping Animated WebP or Animated GIF.
    Returns (file_bytes, mime_type, suggested_filename).
    """
    if len(images) < 2:
        raise ValueError("Select at least 2 photos to create an animation.")

    clean_fmt = output_format.lower().strip()
    if clean_fmt not in ("webp", "gif"):
        clean_fmt = "webp"

    clamped_ms = max(40, min(3000, int(frame_ms)))
    clamped_edge = max(160, min(2048, int(max_edge_px)))

    decoded_frames: list[Image.Image] = []
    for fname, raw in images:
        dec, _, _ = decode_input_image(raw, fname)
        decoded_frames.append(dec)

    # Determine target frame canvas from first frame's aspect ratio
    first = decoded_frames[0]
    scale = min(1.0, clamped_edge / float(max(first.size)))
    canvas_w = max(64, int(round(first.width * scale)))
    canvas_h = max(64, int(round(first.height * scale)))

    normalized_frames: list[Image.Image] = []
    for frm in decoded_frames:
        fitted = frm.convert("RGBA")
        fitted.thumbnail((canvas_w, canvas_h), Image.Resampling.LANCZOS)
        stage = Image.new("RGB", (canvas_w, canvas_h), (15, 23, 42))
        ox = (canvas_w - fitted.width) // 2
        oy = (canvas_h - fitted.height) // 2
        stage.paste(fitted, (ox, oy), mask=fitted.split()[3])
        normalized_frames.append(stage)

    if boomerang and len(normalized_frames) >= 3:
        normalized_frames = normalized_frames + list(reversed(normalized_frames[1:-1]))

    buf = io.BytesIO()
    if clean_fmt == "gif":
        gif_frames = [
            f.quantize(colors=256, method=Image.Quantize.MEDIANCUT)
            for f in normalized_frames
        ]
        gif_frames[0].save(
            buf,
            format="GIF",
            save_all=True,
            append_images=gif_frames[1:],
            duration=clamped_ms,
            loop=loop,
            optimize=True,
        )
        return buf.getvalue(), "image/gif", "PhotoConvert_Burst.gif"

    normalized_frames[0].save(
        buf,
        format="WEBP",
        save_all=True,
        append_images=normalized_frames[1:],
        duration=clamped_ms,
        loop=loop,
        quality=84,
        method=4,
    )
    return buf.getvalue(), "image/webp", "PhotoConvert_Burst.webp"
