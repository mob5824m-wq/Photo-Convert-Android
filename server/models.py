"""Clean domain models and built-in conversion presets."""

from __future__ import annotations

from dataclasses import asdict, dataclass
from typing import Literal

OutputFormat = Literal["webp", "jpeg", "png", "tiff", "bmp"]
ResizeMode = Literal["original", "megapixels", "max_edge"]

SUPPORTED_FORMATS: tuple[OutputFormat, ...] = ("webp", "jpeg", "png", "tiff", "bmp")
LOSSLESS_FORMATS: frozenset[str] = frozenset({"png", "bmp"})


@dataclass(frozen=True)
class ConvertOptions:
    output_format: OutputFormat = "webp"
    quality: int = 85
    resize_mode: ResizeMode = "original"
    max_megapixels: float = 16.0
    max_edge_px: int = 2048
    strip_exif: bool = True

    def normalized(self) -> "ConvertOptions":
        fmt = self.output_format.lower().strip()
        if fmt == "jpg":
            fmt = "jpeg"
        if fmt not in SUPPORTED_FORMATS:
            raise ValueError(f"Unsupported output format: {self.output_format}")

        mode = self.resize_mode.lower().strip()
        if mode not in ("original", "megapixels", "max_edge"):
            raise ValueError(f"Unsupported resize mode: {self.resize_mode}")

        clamped_quality = max(1, min(100, int(self.quality)))
        clamped_mp = max(0.5, min(100.0, float(self.max_megapixels)))
        clamped_edge = max(128, min(16384, int(self.max_edge_px)))

        return ConvertOptions(
            output_format=fmt,  # type: ignore[arg-type]
            quality=clamped_quality,
            resize_mode=mode,  # type: ignore[arg-type]
            max_megapixels=clamped_mp,
            max_edge_px=clamped_edge,
            strip_exif=bool(self.strip_exif),
        )


@dataclass(frozen=True)
class PresetProfile:
    id: str
    name: str
    description: str
    badge: str
    output_format: OutputFormat
    quality: int
    resize_mode: ResizeMode
    max_megapixels: float
    max_edge_px: int
    strip_exif: bool
    built_in: bool = True

    def to_dict(self) -> dict:
        return asdict(self)


BUILTIN_PRESETS: tuple[PresetProfile, ...] = (
    PresetProfile(
        id="web-webp",
        name="Web & Messaging WebP",
        description="Crisp 2048px WebP at 82% quality with private metadata removed.",
        badge="Smallest Size",
        output_format="webp",
        quality=82,
        resize_mode="max_edge",
        max_megapixels=16.0,
        max_edge_px=2048,
        strip_exif=True,
    ),
    PresetProfile(
        id="storage-saver-16mp",
        name="16 MP Storage Saver",
        description="Downscales camera shots above 16 MP to 85% JPEG while keeping capture dates.",
        badge="Cloud Backup",
        output_format="jpeg",
        quality=85,
        resize_mode="megapixels",
        max_megapixels=16.0,
        max_edge_px=4096,
        strip_exif=False,
    ),
    PresetProfile(
        id="privacy-share",
        name="Privacy Share JPEG",
        description="Strips GPS location, camera serials, and EXIF tags at full 1080p HD.",
        badge="Zero Metadata",
        output_format="jpeg",
        quality=84,
        resize_mode="max_edge",
        max_megapixels=16.0,
        max_edge_px=1920,
        strip_exif=True,
    ),
    PresetProfile(
        id="uhd-4k-webp",
        name="4K Archive WebP",
        description="High-detail 3840px 4K WebP at 90% quality with EXIF timestamps intact.",
        badge="High Detail",
        output_format="webp",
        quality=90,
        resize_mode="max_edge",
        max_megapixels=16.0,
        max_edge_px=3840,
        strip_exif=False,
    ),
    PresetProfile(
        id="lossless-png",
        name="Lossless Transparent PNG",
        description="Pixel-exact PNG preserving alpha transparency at original dimensions.",
        badge="Lossless",
        output_format="png",
        quality=100,
        resize_mode="original",
        max_megapixels=16.0,
        max_edge_px=4096,
        strip_exif=True,
    ),
)
