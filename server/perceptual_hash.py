"""64-bit perceptual difference hash (dHash) & sharpness scoring for burst/near-duplicate guard."""

from __future__ import annotations

from PIL import Image, ImageFilter, ImageOps, ImageStat


def compute_dhash_hex(im: Image.Image) -> str:
    """Compute a 64-bit horizontal gradient difference hash (16-char lowercase hex)."""
    oriented = ImageOps.exif_transpose(im)
    gray = oriented.convert("L").resize((9, 8), Image.Resampling.BILINEAR)
    px = gray.tobytes()
    bits = 0
    for row in range(8):
        base = row * 9
        for col in range(8):
            bits = (bits << 1) | (1 if px[base + col + 1] > px[base + col] else 0)
    return f"{bits:016x}"


def hamming_distance_hex(a_hex: str, b_hex: str) -> int:
    """Bitwise Hamming distance between two 64-bit hex hashes."""
    try:
        return (int(a_hex, 16) ^ int(b_hex, 16)).bit_count()
    except ValueError:
        return 64


def estimate_sharpness(im: Image.Image) -> float:
    """Fast edge-variance sharpness metric on a downsampled grayscale preview."""
    gray = im.convert("L")
    gray.thumbnail((384, 384), Image.Resampling.BILINEAR)
    edges = gray.filter(ImageFilter.FIND_EDGES)
    stat = ImageStat.Stat(edges)
    return round(float(stat.var[0]), 2)
