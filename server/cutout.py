"""Offline border-seeded foreground cutout & feathered alpha matting (#10)."""

from __future__ import annotations

from collections import deque

import numpy as np
from PIL import Image, ImageFilter


def remove_background_offline(img: Image.Image) -> Image.Image:
    """Segment salient foreground subject from border background completely offline."""
    rgba = img.convert("RGBA")
    orig_w, orig_h = rgba.size

    # If image already has meaningful transparency, preserve and refine it
    alpha_arr = np.asarray(rgba.split()[3], dtype=np.uint8)
    if int(np.min(alpha_arr)) < 200 and float(np.mean(alpha_arr < 245)) > 0.04:
        return rgba

    # Work at <= 420px resolution for fast, clean connected-border flood segmentation
    work_max = 420
    scale = min(1.0, work_max / float(max(orig_w, orig_h)))
    work_w = max(16, int(round(orig_w * scale)))
    work_h = max(16, int(round(orig_h * scale)))

    small = rgba.convert("RGB").resize((work_w, work_h), Image.Resampling.BILINEAR)
    arr = np.asarray(small, dtype=np.float32)

    # Sample outer border strip (top, bottom, left, right) to model background palette
    border_px = max(2, min(work_w, work_h) // 24)
    top = arr[:border_px, :, :].reshape(-1, 3)
    bottom = arr[-border_px:, :, :].reshape(-1, 3)
    left = arr[:, :border_px, :].reshape(-1, 3)
    right = arr[:, -border_px:, :].reshape(-1, 3)
    border_samples = np.concatenate([top, bottom, left, right], axis=0)

    # Pick 4 representative background color centroids (median + quartiles)
    centroids = np.percentile(border_samples, [15, 40, 60, 85], axis=0)  # (4, 3)

    # Minimum Euclidean RGB distance from each pixel to any border background centroid
    diffs = arr[:, :, None, :] - centroids[None, None, :, :]
    dists = np.sqrt(np.sum(diffs * diffs, axis=-1))
    min_dist = np.min(dists, axis=-1)

    # Adaptive tolerance based on border variance
    border_spread = float(np.std(border_samples))
    threshold = max(28.0, min(68.0, 30.0 + border_spread * 0.75))

    candidate_bg = min_dist <= threshold

    # Flood-fill from outer perimeter so interior subject highlights of similar color are NOT erased
    visited = np.zeros((work_h, work_w), dtype=bool)
    queue: deque[tuple[int, int]] = deque()

    for x in range(work_w):
        if candidate_bg[0, x]:
            visited[0, x] = True
            queue.append((0, x))
        if candidate_bg[work_h - 1, x] and not visited[work_h - 1, x]:
            visited[work_h - 1, x] = True
            queue.append((work_h - 1, x))
    for y in range(work_h):
        if candidate_bg[y, 0] and not visited[y, 0]:
            visited[y, 0] = True
            queue.append((y, 0))
        if candidate_bg[y, work_w - 1] and not visited[y, work_w - 1]:
            visited[y, work_w - 1] = True
            queue.append((y, work_w - 1))

    while queue:
        cy, cx = queue.popleft()
        for ny, nx in ((cy - 1, cx), (cy + 1, cx), (cy, cx - 1), (cy, cx + 1)):
            if 0 <= ny < work_h and 0 <= nx < work_w and not visited[ny, nx] and candidate_bg[ny, nx]:
                visited[ny, nx] = True
                queue.append((ny, nx))

    # Build soft foreground alpha mask with smooth edge transition
    soft_fg = np.clip((min_dist - threshold * 0.65) / max(10.0, threshold * 0.55), 0.0, 1.0)
    fg_alpha = np.where(visited, soft_fg * 0.12, 1.0)
    mask_u8 = (np.clip(fg_alpha, 0.0, 1.0) * 255.0).astype(np.uint8)

    mask_img = Image.fromarray(mask_u8, mode="L").resize((orig_w, orig_h), Image.Resampling.BILINEAR)
    mask_img = mask_img.filter(ImageFilter.GaussianBlur(radius=1.2))

    out = rgba.copy()
    out.putalpha(mask_img)
    return out
