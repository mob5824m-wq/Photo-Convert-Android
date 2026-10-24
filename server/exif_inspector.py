"""EXIF / GPS Tag Inspector, Selective Scrubber (#2), and Dominant Hex Palette helper (#10)."""

from __future__ import annotations

import piexif
from PIL import Image

from server.dedupe import compute_dhash_hex, compute_sharpness_score
from server.naming_and_dates import infer_exif_datetime_from_filename

SERIAL_EXIF_TAGS: frozenset[int] = frozenset(
    {
        getattr(piexif.ExifIFD, "BodySerialNumber", 42033),
        getattr(piexif.ExifIFD, "LensSerialNumber", 42037),
        getattr(piexif.ExifIFD, "CameraOwnerName", 42032),
        getattr(piexif.ImageIFD, "HostComputer", 316),
    }
)


def extract_dominant_palette(img: Image.Image, count: int = 5) -> list[str]:
    """Extract up to `count` dominant hex colors using adaptive median-cut quantization."""
    small = img.convert("RGB")
    small.thumbnail((160, 160), Image.Resampling.BILINEAR)
    quantized = small.quantize(colors=max(2, count), method=Image.Quantize.MEDIANCUT)
    palette = quantized.getpalette() or []
    counts = sorted(quantized.getcolors() or [], key=lambda x: x[0], reverse=True)

    swatches: list[str] = []
    for _, idx in counts[:count]:
        base = idx * 3
        if base + 2 < len(palette):
            r, g, b = palette[base], palette[base + 1], palette[base + 2]
            hex_code = f"#{r:02X}{g:02X}{b:02X}"
            if hex_code not in swatches:
                swatches.append(hex_code)
    return swatches or ["#2563EB"]


def _ratio_to_float(val: object) -> float | None:
    if isinstance(val, tuple) and len(val) == 2 and val[1]:
        return float(val[0]) / float(val[1])
    return None


def _dms_to_decimal(dms: object, ref: bytes | str | None) -> float | None:
    if not isinstance(dms, (list, tuple)) or len(dms) != 3:
        return None
    deg = _ratio_to_float(dms[0])
    minutes = _ratio_to_float(dms[1])
    seconds = _ratio_to_float(dms[2])
    if deg is None or minutes is None or seconds is None:
        return None
    dec = deg + minutes / 60.0 + seconds / 3600.0
    ref_str = ref.decode("ascii", errors="ignore") if isinstance(ref, bytes) else (ref or "")
    if ref_str.upper().strip() in ("S", "W"):
        dec = -dec
    return round(dec, 6)


def _decode_str(raw: object) -> str:
    if isinstance(raw, bytes):
        return raw.decode("utf-8", errors="ignore").strip("\x00 ")
    if raw is None:
        return ""
    return str(raw).strip()


def inspect_exif_and_palette(
    decoded: Image.Image,
    orig_format: str,
    raw_exif: bytes | None,
    filename: str,
    orig_bytes: int,
) -> dict:
    """Return structured EXIF fields, GPS presence, sharpness, dHash, and 5 dominant hex colors."""
    tags: list[dict[str, str]] = []
    has_gps = False
    gps_lat: float | None = None
    gps_lon: float | None = None
    camera = ""
    lens = ""
    captured_at = ""

    if raw_exif:
        try:
            loaded = piexif.load(raw_exif)
            zeroth = loaded.get("0th") or {}
            exif_ifd = loaded.get("Exif") or {}
            gps_ifd = loaded.get("GPS") or {}

            make = _decode_str(zeroth.get(piexif.ImageIFD.Make))
            model = _decode_str(zeroth.get(piexif.ImageIFD.Model))
            camera = " ".join(p for p in (make, model) if p).strip()
            if camera:
                tags.append({"label": "Camera", "value": camera})

            lens = _decode_str(exif_ifd.get(piexif.ExifIFD.LensModel))
            if lens:
                tags.append({"label": "Lens", "value": lens})

            captured_at = _decode_str(exif_ifd.get(piexif.ExifIFD.DateTimeOriginal)) or _decode_str(
                zeroth.get(piexif.ImageIFD.DateTime)
            )
            if captured_at:
                tags.append({"label": "Captured", "value": captured_at})

            iso = exif_ifd.get(piexif.ExifIFD.ISOSpeedRatings)
            if iso is not None:
                tags.append({"label": "ISO", "value": str(iso)})

            fnum = _ratio_to_float(exif_ifd.get(piexif.ExifIFD.FNumber))
            if fnum:
                tags.append({"label": "Aperture", "value": f"f/{fnum:.1f}"})

            exp = exif_ifd.get(piexif.ExifIFD.ExposureTime)
            if isinstance(exp, tuple) and len(exp) == 2 and exp[1]:
                exp_str = f"{exp[0]}/{exp[1]}s" if exp[0] < exp[1] else f"{exp[0] / exp[1]:.1f}s"
                tags.append({"label": "Shutter", "value": exp_str})

            focal = _ratio_to_float(exif_ifd.get(piexif.ExifIFD.FocalLength))
            if focal:
                tags.append({"label": "Focal Length", "value": f"{focal:.1f} mm"})

            artist = _decode_str(zeroth.get(piexif.ImageIFD.Artist))
            if artist:
                tags.append({"label": "Artist", "value": artist})

            copyright_val = _decode_str(zeroth.get(piexif.ImageIFD.Copyright))
            if copyright_val:
                tags.append({"label": "Copyright", "value": copyright_val})

            if gps_ifd:
                gps_lat = _dms_to_decimal(
                    gps_ifd.get(piexif.GPSIFD.GPSLatitude),
                    gps_ifd.get(piexif.GPSIFD.GPSLatitudeRef),
                )
                gps_lon = _dms_to_decimal(
                    gps_ifd.get(piexif.GPSIFD.GPSLongitude),
                    gps_ifd.get(piexif.GPSIFD.GPSLongitudeRef),
                )
                has_gps = True
                if gps_lat is not None and gps_lon is not None:
                    tags.append({"label": "GPS Coordinates", "value": f"{gps_lat}, {gps_lon}"})
                else:
                    tags.append({"label": "GPS Metadata", "value": "Present"})
        except Exception:
            pass

    inferred_date = infer_exif_datetime_from_filename(filename)

    return {
        "filename": filename,
        "format": orig_format,
        "width": decoded.width,
        "height": decoded.height,
        "bytes": orig_bytes,
        "has_exif": bool(raw_exif),
        "has_gps": has_gps,
        "gps_lat": gps_lat,
        "gps_lon": gps_lon,
        "camera": camera,
        "lens": lens,
        "captured_at": captured_at,
        "inferred_filename_date": inferred_date,
        "sharpness_score": compute_sharpness_score(decoded),
        "dhash": compute_dhash_hex(decoded),
        "palette": extract_dominant_palette(decoded, 5),
        "tags": tags,
    }


def build_selective_exif(
    raw_exif: bytes | None,
    filename: str,
    privacy_mode: str = "strip_all",
    repair_filename_dates: bool = True,
    exif_artist: str = "",
    exif_copyright: str = "",
) -> tuple[bytes | None, str | None, bool]:
    """
    Build sanitized or preserved EXIF payload:
    - 'strip_all': removes all camera/GPS tags (still embeds explicit Artist/Copyright if user entered them).
    - 'gps_only': removes GPS IFD and body/lens serial numbers while keeping camera, lens & capture dates.
    - 'keep_all': preserves 0th, Exif & GPS tags with normalized upright Orientation=1.
    """
    exif_dict: dict = {"0th": {}, "Exif": {}, "GPS": {}, "1st": {}}
    existing_dt: str | None = None
    repaired = False
    mode = privacy_mode.lower().strip()

    if mode in ("gps_only", "keep_all") and raw_exif:
        try:
            loaded = piexif.load(raw_exif)
            for key in ("0th", "Exif"):
                if key in loaded and isinstance(loaded[key], dict):
                    exif_dict[key] = dict(loaded[key])
            if mode == "keep_all" and "GPS" in loaded and isinstance(loaded["GPS"], dict):
                exif_dict["GPS"] = dict(loaded["GPS"])

            if mode == "gps_only":
                for serial_tag in SERIAL_EXIF_TAGS:
                    exif_dict["Exif"].pop(serial_tag, None)
                    exif_dict["0th"].pop(serial_tag, None)

            if piexif.ImageIFD.Orientation in exif_dict["0th"]:
                exif_dict["0th"][piexif.ImageIFD.Orientation] = 1

            raw_dt = exif_dict["Exif"].get(piexif.ExifIFD.DateTimeOriginal)
            if isinstance(raw_dt, bytes):
                existing_dt = raw_dt.decode("ascii", errors="ignore").strip("\x00 ")
        except Exception:
            pass

    if mode in ("gps_only", "keep_all") and not existing_dt and repair_filename_dates:
        inferred = infer_exif_datetime_from_filename(filename)
        if inferred:
            encoded_dt = inferred.encode("ascii")
            exif_dict["Exif"][piexif.ExifIFD.DateTimeOriginal] = encoded_dt
            exif_dict["Exif"][piexif.ExifIFD.DateTimeDigitized] = encoded_dt
            exif_dict["0th"][piexif.ImageIFD.DateTime] = encoded_dt
            exif_dict["0th"][piexif.ImageIFD.Orientation] = 1
            existing_dt = inferred
            repaired = True

    clean_artist = exif_artist.strip()[:80]
    clean_copy = exif_copyright.strip()[:120]
    if clean_artist:
        exif_dict["0th"][piexif.ImageIFD.Artist] = clean_artist.encode("utf-8")
    if clean_copy:
        exif_dict["0th"][piexif.ImageIFD.Copyright] = clean_copy.encode("utf-8")

    has_any = bool(exif_dict["0th"] or exif_dict["Exif"] or exif_dict["GPS"])
    if not has_any:
        return None, existing_dt, False

    try:
        return piexif.dump(exif_dict), existing_dt, repaired
    except Exception:
        return None, existing_dt, False
