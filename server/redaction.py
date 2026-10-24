"""Smart Privacy Blur & Redaction (#1): offline face/skin regions, text/ID strips, and custom normalized boxes."""

from __future__ import annotations

import numpy as np
from PIL import Image, ImageDraw, ImageFilter


def parse_normalized_boxes(spec: str, width: int, height: int) -> list[tuple[int, int, int, int]]:
    """Parse 'x1,y1,x2,y2;...' normalized [0..1] boxes into clamped pixel coordinates."""
    boxes: list[tuple[int, int, int, int]] = []
    if not spec or not spec.strip():
        return boxes

    for raw_part in spec.split(";"):
        part = raw_part.strip()
        if not part:
            continue
        pieces = [p.strip() for p in part.split(",")]
        if len(pieces) != 4:
            continue
        try:
            fx1, fy1, fx2, fy2 = (float(v) for v in pieces)
        except ValueError:
            continue
        nx1, nx2 = sorted((max(0.0, min(1.0, fx1)), max(0.0, min(1.0, fx2))))
        ny1, ny2 = sorted((max(0.0, min(1.0, fy1)), max(0.0, min(1.0, fy2))))
        px1 = int(round(nx1 * width))
        py1 = int(round(ny1 * height))
        px2 = int(round(nx2 * width))
        py2 = int(round(ny2 * height))
        if px2 - px1 >= 4 and py2 - py1 >= 4:
            boxes.append((px1, py1, px2, py2))
    return boxes


def _connected_boxes(mask: np.ndarray, min_area: int, max_area_ratio: float = 0.45) -> list[tuple[int, int, int, int]]:
    """Extract connected-component bounding boxes from a boolean 2D mask."""
    h, w = mask.shape
    visited = np.zeros_like(mask, dtype=bool)
    max_area = int(h * w * max_area_ratio)
    boxes: list[tuple[int, int, int, int]] = []

    ys, xs = np.nonzero(mask)
    for sy, sx in zip(ys.tolist(), xs.tolist()):
        if visited[sy, sx]:
            continue
        stack = [(sy, sx)]
        visited[sy, sx] = True
        min_x = max_x = sx
        min_y = max_y = sy
        count = 0

        while stack:
            cy, cx = stack.pop()
            count += 1
            if cx < min_x:
                min_x = cx
            if cx > max_x:
                max_x = cx
            if cy < min_y:
                min_y = cy
            if cy > max_y:
                max_y = cy

            for ny, nx in ((cy - 1, cx), (cy + 1, cx), (cy, cx - 1), (cy, cx + 1)):
                if 0 <= ny < h and 0 <= nx < w and mask[ny, nx] and not visited[ny, nx]:
                    visited[ny, nx] = True
                    stack.append((ny, nx))

        if min_area <= count <= max_area:
            boxes.append((min_x, min_y, max_x + 1, max_y + 1))

    return boxes


def detect_face_like_regions(img: Image.Image) -> list[tuple[int, int, int, int]]:
    """Offline heuristic YCbCr skin-tone + oval aspect detector for portraits when requested."""
    orig_w, orig_h = img.size
    work_w = 180
    scale = work_w / max(1, orig_w)
    work_h = max(32, int(round(orig_h * scale)))

    ycbcr = np.asarray(
        img.convert("RGB").resize((work_w, work_h), Image.Resampling.BILINEAR).convert("YCbCr"),
        dtype=np.int16,
    )
    y_ch, cb_ch, cr_ch = ycbcr[:, :, 0], ycbcr[:, :, 1], ycbcr[:, :, 2]

    skin = (
        (y_ch > 65)
        & (cb_ch >= 77)
        & (cb_ch <= 127)
        & (cr_ch >= 133)
        & (cr_ch <= 173)
    )

    raw_boxes = _connected_boxes(skin, min_area=max(24, (work_w * work_h) // 180), max_area_ratio=0.35)
    scaled: list[tuple[int, int, int, int]] = []
    inv = 1.0 / scale
    for x1, y1, x2, y2 in raw_boxes:
        bw = x2 - x1
        bh = y2 - y1
        aspect = bw / max(1, bh)
        if 0.45 <= aspect <= 1.65:
            pad_x = int(bw * 0.12 * inv)
            pad_y = int(bh * 0.15 * inv)
            sx1 = max(0, int(round(x1 * inv)) - pad_x)
            sy1 = max(0, int(round(y1 * inv)) - pad_y)
            sx2 = min(orig_w, int(round(x2 * inv)) + pad_x)
            sy2 = min(orig_h, int(round(y2 * inv)) + pad_y)
            scaled.append((sx1, sy1, sx2, sy2))
    return scaled


def detect_sensitive_text_strips(img: Image.Image) -> list[tuple[int, int, int, int]]:
    """Offline horizontal gradient density detector for text lines, plates, and ID numbers."""
    orig_w, orig_h = img.size
    work_w = 240
    scale = work_w / max(1, orig_w)
    work_h = max(40, int(round(orig_h * scale)))

    gray = np.asarray(
        img.convert("L").resize((work_w, work_h), Image.Resampling.BILINEAR),
        dtype=np.float32,
    )
    gx = np.abs(np.diff(gray, axis=1, prepend=gray[:, :1]))
    # Smooth horizontally to merge adjacent characters on a line
    kernel_w = 9
    pad = kernel_w // 2
    padded = np.pad(gx, ((0, 0), (pad, pad)), mode="edge")
    smoothed = sum(padded[:, i : i + work_w] for i in range(kernel_w)) / kernel_w

    thresh = max(18.0, float(np.percentile(smoothed, 91)))
    mask = smoothed >= thresh

    raw_boxes = _connected_boxes(mask, min_area=20, max_area_ratio=0.22)
    inv = 1.0 / scale
    results: list[tuple[int, int, int, int]] = []
    for x1, y1, x2, y2 in raw_boxes:
        bw = x2 - x1
        bh = y2 - y1
        if bw >= bh * 1.6 and bw >= 12:
            sx1 = max(0, int(round(x1 * inv)) - 4)
            sy1 = max(0, int(round(y1 * inv)) - 4)
            sx2 = min(orig_w, int(round(x2 * inv)) + 4)
            sy2 = min(orig_h, int(round(y2 * inv)) + 4)
            results.append((sx1, sy1, sx2, sy2))
    return results


def apply_privacy_redaction(
    img: Image.Image,
    redact_faces: bool = False,
    redact_text: bool = False,
    redact_boxes_spec: str = "",
    style: str = "blur",
) -> tuple[Image.Image, int]:
    """Apply blur, pixelation, or solid redaction across auto-detected and manual boxes."""
    w, h = img.size
    boxes: list[tuple[int, int, int, int]] = []
    if redact_faces:
        boxes.extend(detect_face_like_regions(img))
    if redact_text:
        boxes.extend(detect_sensitive_text_strips(img))
    if redact_boxes_spec:
        boxes.extend(parse_normalized_boxes(redact_boxes_spec, w, h))

    if not boxes:
        return img, 0

    out = img.copy()
    clean_style = style.lower().strip()
    draw = ImageDraw.Draw(out) if clean_style == "solid" else None

    for x1, y1, x2, y2 in boxes:
        cx1 = max(0, min(w - 1, x1))
        cy1 = max(0, min(h - 1, y1))
        cx2 = max(cx1 + 1, min(w, x2))
        cy2 = max(cy1 + 1, min(h, y2))
        bw, bh = cx2 - cx1, cy2 - cy1

        if clean_style == "solid" and draw is not None:
            fill = (15, 23, 42, 255) if out.mode == "RGBA" else (15, 23, 42)
            draw.rectangle((cx1, cy1, cx2, cy2), fill=fill)
        elif clean_style == "pixelate":
            region = out.crop((cx1, cy1, cx2, cy2))
            block = max(8, min(bw, bh) // 7)
            small_w = max(2, bw // block)
            small_h = max(2, bh // block)
            mosaic = region.resize((small_w, small_h), Image.Resampling.BILINEAR).resize(
                (bw, bh), Image.Resampling.NEAREST
            )
            out.paste(mosaic, (cx1, cy1))
        else:  # blur
            region = out.crop((cx1, cy1, cx2, cy2))
            radius = max(12, int(round(min(bw, bh) * 0.16)))
            blurred = region.filter(ImageFilter.GaussianBlur(radius=radius))
            out.paste(blurred, (cx1, cy1))

    return out, len(boxes)
