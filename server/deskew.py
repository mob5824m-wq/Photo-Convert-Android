"""Auto-Crop & 4-Corner Perspective Deskew for Document Scans (#4)."""

from __future__ import annotations

import math

import numpy as np
from PIL import Image


def _solve_perspective_coeffs(
    dst_pts: list[tuple[float, float]],
    src_pts: list[tuple[float, float]],
) -> list[float]:
    """Solve 8-parameter perspective transform coefficients mapping dst -> src for PIL."""
    matrix = []
    rhs = []
    for (dx, dy), (sx, sy) in zip(dst_pts, src_pts):
        matrix.append([dx, dy, 1.0, 0.0, 0.0, 0.0, -sx * dx, -sx * dy])
        matrix.append([0.0, 0.0, 0.0, dx, dy, 1.0, -sy * dx, -sy * dy])
        rhs.append(sx)
        rhs.append(sy)
    a = np.asarray(matrix, dtype=np.float64)
    b = np.asarray(rhs, dtype=np.float64)
    coeffs, _, _, _ = np.linalg.lstsq(a, b, rcond=None)
    return coeffs.tolist()


def warp_four_corners(
    img: Image.Image,
    quad_xy: list[tuple[float, float]],
) -> Image.Image:
    """Rectify an arbitrary 4-corner quadrilateral (TL, TR, BR, BL) into a flat rectangle."""
    if len(quad_xy) != 4:
        return img
    tl, tr, br, bl = quad_xy

    width_top = math.hypot(tr[0] - tl[0], tr[1] - tl[1])
    width_bot = math.hypot(br[0] - bl[0], br[1] - bl[1])
    out_w = max(32, int(round(max(width_top, width_bot))))

    height_left = math.hypot(bl[0] - tl[0], bl[1] - tl[1])
    height_right = math.hypot(br[0] - tr[0], br[1] - tr[1])
    out_h = max(32, int(round(max(height_left, height_right))))

    dst = [(0.0, 0.0), (float(out_w), 0.0), (float(out_w), float(out_h)), (0.0, float(out_h))]
    coeffs = _solve_perspective_coeffs(dst, [tl, tr, br, bl])
    return img.transform(
        (out_w, out_h),
        Image.Transform.PERSPECTIVE,
        coeffs,
        resample=Image.Resampling.BICUBIC,
    )


def auto_trim_borders(img: Image.Image, tolerance: float = 26.0) -> Image.Image:
    """Trim uniform surrounding background borders while preserving a clean 1.5% safety margin."""
    w, h = img.size
    if w < 48 or h < 48:
        return img

    arr = np.asarray(img.convert("RGB"), dtype=np.float32)
    border = np.concatenate(
        [arr[0, :, :], arr[-1, :, :], arr[:, 0, :], arr[:, -1, :]],
        axis=0,
    )
    bg_color = np.median(border, axis=0)
    dist = np.linalg.norm(arr - bg_color, axis=2)
    fg = dist > tolerance

    fg_ratio = float(np.mean(fg))
    if fg_ratio < 0.04 or fg_ratio > 0.97:
        return img

    ys, xs = np.nonzero(fg)
    pad_x = int(round(w * 0.015))
    pad_y = int(round(h * 0.015))
    x1 = max(0, int(xs.min()) - pad_x)
    y1 = max(0, int(ys.min()) - pad_y)
    x2 = min(w, int(xs.max()) + 1 + pad_x)
    y2 = min(h, int(ys.max()) + 1 + pad_y)

    if (x2 - x1) < w * 0.25 or (y2 - y1) < h * 0.25:
        return img
    return img.crop((x1, y1, x2, y2))


def auto_deskew_document(img: Image.Image) -> Image.Image:
    """Detect a bright/contrasting quadrilateral page on a darker surface and perspective-rectify it."""
    orig_w, orig_h = img.size
    if orig_w < 80 or orig_h < 80:
        return img

    work_max = 320
    scale = min(1.0, work_max / float(max(orig_w, orig_h)))
    work_w = max(48, int(round(orig_w * scale)))
    work_h = max(48, int(round(orig_h * scale)))

    arr = np.asarray(
        img.convert("RGB").resize((work_w, work_h), Image.Resampling.BILINEAR),
        dtype=np.float32,
    )
    border = np.concatenate([arr[0, :, :], arr[-1, :, :], arr[:, 0, :], arr[:, -1, :]], axis=0)
    bg_color = np.median(border, axis=0)
    dist = np.linalg.norm(arr - bg_color, axis=2)

    thresh = max(28.0, float(np.percentile(dist, 55)) * 0.65)
    fg = dist > thresh
    fg_frac = float(np.mean(fg))

    # Only perspective-warp when there is a distinct document region surrounded by background
    if fg_frac < 0.12 or fg_frac > 0.90:
        return auto_trim_borders(img)

    ys, xs = np.nonzero(fg)
    if len(xs) < 100:
        return img

    # Extreme corner points via (x+y) and (x-y) sums
    pts_x = xs.astype(np.float64) / scale
    pts_y = ys.astype(np.float64) / scale
    s = pts_x + pts_y
    d = pts_x - pts_y

    tl = (float(pts_x[int(np.argmin(s))]), float(pts_y[int(np.argmin(s))]))
    br = (float(pts_x[int(np.argmax(s))]), float(pts_y[int(np.argmax(s))]))
    tr = (float(pts_x[int(np.argmax(d))]), float(pts_y[int(np.argmax(d))]))
    bl = (float(pts_x[int(np.argmin(d))]), float(pts_y[int(np.argmin(d))]))

    warped = warp_four_corners(img, [tl, tr, br, bl])
    if warped.width < orig_w * 0.25 or warped.height < orig_h * 0.25:
        return auto_trim_borders(img)
    return warped
