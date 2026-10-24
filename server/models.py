"""Clean domain models, studio/privacy/tone enhancement options, and built-in conversion presets."""

from __future__ import annotations

from dataclasses import asdict, dataclass
from typing import Literal

OutputFormat = Literal["webp", "jpeg", "png", "avif", "heic", "tiff", "bmp"]
ResizeMode = Literal["original", "megapixels", "max_edge"]
ExifPrivacyMode = Literal["auto", "strip_all", "gps_only", "keep_all"]
AspectRatioPreset = Literal["original", "1:1", "4:5", "16:9", "9:16"]
FrameMode = Literal["crop", "blur", "white", "black"]
WatermarkPosition = Literal["bottom-right", "bottom-left", "top-right", "top-left", "center"]
RedactStyle = Literal["blur", "pixelate", "solid"]

SUPPORTED_FORMATS: tuple[OutputFormat, ...] = (
    "webp",
    "jpeg",
    "png",
    "avif",
    "heic",
    "tiff",
    "bmp",
)
LOSSLESS_FORMATS: frozenset[str] = frozenset({"png", "bmp"})
ALPHA_FORMATS: frozenset[str] = frozenset({"webp", "png", "avif", "heic", "tiff"})
RAW_EXTENSIONS: frozenset[str] = frozenset(
    {".dng", ".cr2", ".cr3", ".nef", ".arw", ".orf", ".rw2", ".pef", ".raf"}
)


@dataclass(frozen=True)
class ConvertOptions:
    output_format: OutputFormat = "webp"
    quality: int = 85
    target_size_kb: int = 0
    resize_mode: ResizeMode = "original"
    max_megapixels: float = 16.0
    max_edge_px: int = 2048
    strip_exif: bool = True
    exif_privacy_mode: ExifPrivacyMode = "auto"
    exif_artist: str = ""
    exif_copyright: str = ""
    repair_filename_dates: bool = True
    normalize_srgb: bool = True
    remove_background: bool = False
    auto_deskew: bool = False
    post_sharpen: bool = False
    auto_levels: bool = False
    grayscale: bool = False
    redact_faces: bool = False
    redact_text: bool = False
    redact_boxes: str = ""
    redact_style: RedactStyle = "blur"
    aspect_ratio: AspectRatioPreset = "original"
    frame_mode: FrameMode = "blur"
    watermark_text: str = ""
    watermark_position: WatermarkPosition = "bottom-right"
    watermark_opacity: int = 65
    watermark_tiled: bool = False
    watermark_logo_bytes: bytes | None = None
    rename_pattern: str = "{orig}"
    preset_slug: str = "custom"

    def normalized(self) -> "ConvertOptions":
        fmt = self.output_format.lower().strip()
        if fmt == "jpg":
            fmt = "jpeg"
        if fmt == "heif":
            fmt = "heic"
        if fmt not in SUPPORTED_FORMATS:
            raise ValueError(f"Unsupported output format: {self.output_format}")

        if self.remove_background and fmt not in ALPHA_FORMATS:
            fmt = "webp"

        mode = self.resize_mode.lower().strip()
        if mode not in ("original", "megapixels", "max_edge"):
            raise ValueError(f"Unsupported resize mode: {self.resize_mode}")

        raw_priv = self.exif_privacy_mode.lower().strip()
        if raw_priv in ("strip_all", "gps_only", "keep_all"):
            resolved_priv = raw_priv
            resolved_strip = raw_priv == "strip_all"
        else:
            resolved_strip = bool(self.strip_exif)
            resolved_priv = "strip_all" if resolved_strip else "keep_all"

        ratio = self.aspect_ratio.strip()
        if ratio not in ("original", "1:1", "4:5", "16:9", "9:16"):
            ratio = "original"

        fmode = self.frame_mode.lower().strip()
        if fmode not in ("crop", "blur", "white", "black"):
            fmode = "blur"

        wpos = self.watermark_position.lower().strip()
        if wpos not in ("bottom-right", "bottom-left", "top-right", "top-left", "center"):
            wpos = "bottom-right"

        rstyle = self.redact_style.lower().strip()
        if rstyle not in ("blur", "pixelate", "solid"):
            rstyle = "blur"

        clamped_quality = max(1, min(100, int(self.quality)))
        clamped_target_kb = max(0, min(51200, int(self.target_size_kb)))
        clamped_mp = max(0.5, min(100.0, float(self.max_megapixels)))
        clamped_edge = max(128, min(16384, int(self.max_edge_px)))
        clamped_wm_opacity = max(10, min(100, int(self.watermark_opacity)))
        pattern = self.rename_pattern.strip() or "{orig}"

        return ConvertOptions(
            output_format=fmt,  # type: ignore[arg-type]
            quality=clamped_quality,
            target_size_kb=clamped_target_kb,
            resize_mode=mode,  # type: ignore[arg-type]
            max_megapixels=clamped_mp,
            max_edge_px=clamped_edge,
            strip_exif=resolved_strip,
            exif_privacy_mode=resolved_priv,  # type: ignore[arg-type]
            exif_artist=self.exif_artist.strip()[:80],
            exif_copyright=self.exif_copyright.strip()[:120],
            repair_filename_dates=bool(self.repair_filename_dates),
            normalize_srgb=bool(self.normalize_srgb),
            remove_background=bool(self.remove_background),
            auto_deskew=bool(self.auto_deskew),
            post_sharpen=bool(self.post_sharpen),
            auto_levels=bool(self.auto_levels),
            grayscale=bool(self.grayscale),
            redact_faces=bool(self.redact_faces),
            redact_text=bool(self.redact_text),
            redact_boxes=self.redact_boxes.strip()[:600],
            redact_style=rstyle,  # type: ignore[arg-type]
            aspect_ratio=ratio,  # type: ignore[arg-type]
            frame_mode=fmode,  # type: ignore[arg-type]
            watermark_text=self.watermark_text.strip()[:80],
            watermark_position=wpos,  # type: ignore[arg-type]
            watermark_opacity=clamped_wm_opacity,
            watermark_tiled=bool(self.watermark_tiled),
            watermark_logo_bytes=self.watermark_logo_bytes,
            rename_pattern=pattern,
            preset_slug=self.preset_slug.strip() or "custom",
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
    exif_privacy_mode: str = "strip_all"
    target_size_kb: int = 0
    normalize_srgb: bool = True
    remove_background: bool = False
    auto_deskew: bool = False
    post_sharpen: bool = False
    auto_levels: bool = False
    grayscale: bool = False
    aspect_ratio: AspectRatioPreset = "original"
    frame_mode: FrameMode = "blur"
    watermark_text: str = ""
    watermark_tiled: bool = False
    rename_pattern: str = "{orig}"
    built_in: bool = True

    def to_dict(self) -> dict:
        return asdict(self)


BUILTIN_PRESETS: tuple[PresetProfile, ...] = (
    PresetProfile(
        id="web-webp",
        name="Web & Messaging WebP",
        description="Crisp 2048px sRGB WebP at 82% quality with micro-sharpening and metadata removed.",
        badge="Smallest Size",
        output_format="webp",
        quality=82,
        resize_mode="max_edge",
        max_megapixels=16.0,
        max_edge_px=2048,
        strip_exif=True,
        exif_privacy_mode="strip_all",
        post_sharpen=True,
    ),
    PresetProfile(
        id="portal-under-500kb",
        name="Portal & Forms (≤ 500 KB)",
        description="Binary-searches JPEG quality and resolution to guarantee files stay under 500 KB.",
        badge="Exact Cap",
        output_format="jpeg",
        quality=88,
        resize_mode="max_edge",
        max_megapixels=16.0,
        max_edge_px=2400,
        strip_exif=True,
        exif_privacy_mode="strip_all",
        target_size_kb=500,
    ),
    PresetProfile(
        id="safe-social-no-gps",
        name="Safe Social (Scrub GPS Only)",
        description="Strips GPS location & device serials while keeping camera, lens, and capture date.",
        badge="GPS Scrubbed",
        output_format="webp",
        quality=86,
        resize_mode="max_edge",
        max_megapixels=16.0,
        max_edge_px=2560,
        strip_exif=False,
        exif_privacy_mode="gps_only",
        post_sharpen=True,
    ),
    PresetProfile(
        id="client-proof-tiled",
        name="Client Proof (45° Tiled Watermark)",
        description="1920px preview JPEG with repeating diagonal proof watermark and auto-levels.",
        badge="Proof Grid",
        output_format="jpeg",
        quality=84,
        resize_mode="max_edge",
        max_megapixels=16.0,
        max_edge_px=1920,
        strip_exif=True,
        exif_privacy_mode="strip_all",
        auto_levels=True,
        watermark_text="© PROOF PREVIEW",
        watermark_tiled=True,
    ),
    PresetProfile(
        id="nextgen-avif",
        name="Next-Gen AVIF Archive",
        description="Ultra-compact AVIF output with sRGB normalization and preserved capture dates.",
        badge="AVIF",
        output_format="avif",
        quality=78,
        resize_mode="megapixels",
        max_megapixels=16.0,
        max_edge_px=4096,
        strip_exif=False,
        exif_privacy_mode="keep_all",
    ),
    PresetProfile(
        id="social-4x5-blur",
        name="Social 4:5 Blurred Frame",
        description="Frames any photo onto a 4:5 portrait canvas with a soft-blurred backdrop.",
        badge="Social Ready",
        output_format="jpeg",
        quality=88,
        resize_mode="max_edge",
        max_megapixels=16.0,
        max_edge_px=2160,
        strip_exif=True,
        exif_privacy_mode="strip_all",
        aspect_ratio="4:5",
        frame_mode="blur",
    ),
    PresetProfile(
        id="subject-cutout-webp",
        name="Transparent Subject Cutout",
        description="Strips solid/studio backgrounds offline into a clean transparent WebP sticker.",
        badge="AI Cutout",
        output_format="webp",
        quality=90,
        resize_mode="max_edge",
        max_megapixels=16.0,
        max_edge_px=2048,
        strip_exif=True,
        exif_privacy_mode="strip_all",
        remove_background=True,
    ),
    PresetProfile(
        id="storage-saver-16mp",
        name="16 MP Storage Saver",
        description="Downscales camera shots above 16 MP to 85% JPEG while repairing filename dates.",
        badge="Cloud Backup",
        output_format="jpeg",
        quality=85,
        resize_mode="megapixels",
        max_megapixels=16.0,
        max_edge_px=4096,
        strip_exif=False,
        exif_privacy_mode="keep_all",
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
        exif_privacy_mode="strip_all",
    ),
)
