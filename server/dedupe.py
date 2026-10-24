"""Perceptual Duplicate & Burst De-Duplicator (#8) using 64-bit dHash + Sharpness Score."""

from __future__ import annotations

import numpy as np
from PIL import Image


def compute_dhash_int(img: Image.Image) -> int:
    """Compute a 64-bit horizontal difference hash (9x8 grayscale grid)."""
    small = img.convert("L").resize((9, 8), Image.Resampling.BILINEAR)
    arr = np.asarray(small, dtype=np.int16)
    diff = arr[:, 1:] > arr[:, :-1]
    value = 0
    for bit in diff.flatten().tolist():
        value = (value << 1) | (1 if bit else 0)
    return value


def compute_dhash_hex(img: Image.Image) -> str:
    return f"{compute_dhash_int(img):016x}"


def hamming_distance(hash_a: int, hash_b: int) -> int:
    return (hash_a ^ hash_b).bit_count()


def compute_sharpness_score(img: Image.Image) -> float:
    """Compute edge-gradient variance score (higher = sharper focus)."""
    work = img.convert("L")
    if max(work.size) > 512:
        work = work.copy()
        work.thumbnail((512, 512), Image.Resampling.BILINEAR)
    arr = np.asarray(work, dtype=np.float32)
    gx = np.diff(arr, axis=1)
    gy = np.diff(arr, axis=0)
    lap = (
        arr[1:-1, :-2]
        + arr[1:-1, 2:]
        + arr[:-2, 1:-1]
        + arr[2:, 1:-1]
        - 4.0 * arr[1:-1, 1:-1]
    )
    score = float(np.var(lap)) + 0.25 * (float(np.mean(gx * gx)) + float(np.mean(gy * gy)))
    return round(score, 2)


def analyze_burst_duplicates(
    items: list[tuple[int, str, bytes]],
    max_hamming: int = 10,
) -> dict:
    """Group near-duplicate burst images and select the sharpest/highest-res keeper per cluster."""
    from server.converter import decode_input_image

    analyzed: list[dict] = []
    for idx, filename, raw in items:
        decoded, _, _ = decode_input_image(raw, filename)
        h_int = compute_dhash_int(decoded)
        sharpness = compute_sharpness_score(decoded)
        pixels = decoded.width * decoded.height
        rank_score = sharpness * (1.0 + min(1.5, pixels / 12_000_000.0))
        analyzed.append(
            {
                "index": idx,
                "filename": filename,
                "width": decoded.width,
                "height": decoded.height,
                "dhash_int": h_int,
                "dhash": f"{h_int:016x}",
                "sharpness": sharpness,
                "rank_score": round(rank_score, 2),
            }
        )

    clusters: list[list[dict]] = []
    assigned = set()

    for i, item in enumerate(analyzed):
        if i in assigned:
            continue
        group = [item]
        assigned.add(i)
        for j in range(i + 1, len(analyzed)):
            if j in assigned:
                continue
            if hamming_distance(item["dhash_int"], analyzed[j]["dhash_int"]) <= max_hamming:
                group.append(analyzed[j])
                assigned.add(j)
        clusters.append(group)

    keep_indices: list[int] = []
    drop_indices: list[int] = []
    public_clusters: list[dict] = []

    for cid, group in enumerate(clusters, start=1):
        ordered = sorted(group, key=lambda x: x["rank_score"], reverse=True)
        winner = ordered[0]
        keep_indices.append(winner["index"])
        for loser in ordered[1:]:
            drop_indices.append(loser["index"])
        public_clusters.append(
            {
                "cluster_id": cid,
                "winner_index": winner["index"],
                "winner_filename": winner["filename"],
                "members": [
                    {k: v for k, v in m.items() if k != "dhash_int"}
                    for m in ordered
                ],
            }
        )

    keep_indices.sort()
    drop_indices.sort()
    return {
        "total_analyzed": len(analyzed),
        "duplicates_found": len(drop_indices),
        "keep_indices": keep_indices,
        "drop_indices": drop_indices,
        "clusters": public_clusters,
    }
