"""Wide-gamut ICC profile detection and Display P3 / Adobe RGB -> sRGB normalization (#9)."""

from __future__ import annotations

import io
from PIL import Image, ImageCms

_SRGB_PROFILE = ImageCms.createProfile("sRGB")
_SRGB_BYTES = ImageCms.ImageCmsProfile(_SRGB_PROFILE).tobytes()


def normalize_to_srgb(img: Image.Image) -> tuple[Image.Image, bytes, bool]:
    """Convert embedded wide-gamut ICC profiles to standard sRGB and return sRGB ICC bytes."""
    raw_icc = img.info.get("icc_profile")
    converted_from_wide = False

    if raw_icc:
        try:
            src_profile = ImageCms.ImageCmsProfile(io.BytesIO(raw_icc))
            desc = ImageCms.getProfileDescription(src_profile).strip().lower()
            is_already_srgb = "srgb" in desc

            if not is_already_srgb:
                has_alpha = img.mode == "RGBA" or (img.mode == "LA")
                alpha_channel = img.convert("RGBA").split()[3] if has_alpha else None
                rgb_src = img.convert("CMYK") if img.mode == "CMYK" else img.convert("RGB")
                src_mode = rgb_src.mode
                transformed = ImageCms.profileToProfile(
                    rgb_src,
                    src_profile,
                    _SRGB_PROFILE,
                    renderingIntent=ImageCms.Intent.PERCEPTUAL,
                    outputMode="RGB",
                )
                if transformed is not None:
                    if alpha_channel is not None:
                        transformed = transformed.convert("RGBA")
                        transformed.putalpha(alpha_channel)
                    img = transformed
                    converted_from_wide = True
                elif src_mode == "CMYK":
                    img = img.convert("RGB")
        except Exception:
            if img.mode == "CMYK":
                img = img.convert("RGB")
    elif img.mode == "CMYK":
        img = img.convert("RGB")
        converted_from_wide = True

    return img, _SRGB_BYTES, converted_from_wide
