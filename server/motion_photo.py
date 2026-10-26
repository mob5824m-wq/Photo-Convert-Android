"""Google Pixel / Android Motion Photo (MVIMG / .MP.jpg) detector & MP4 stream preserver."""

from __future__ import annotations

import re
from pathlib import Path
from typing import Optional, Tuple

MICROVIDEO_OFFSET_RE = re.compile(
    rb'(?:GCamera:MicroVideoOffset|MotionPhotoPresentationTimestampUs|Item:Length)\s*=\s*"(\d+)"'
)
FTYP_SIGNATURES = (b"ftypmp42", b"ftypisom", b"ftypiso2", b"ftypavc1", b"ftypqt  ")


def extract_motion_photo_mp4(source_path: Path) -> Optional[bytes]:
    """
    Detect and extract an embedded MP4 motion clip appended after the JPEG stream in
    Google Pixel Motion Photos (`MVIMG_*.jpg`, `PXL_*.MP.jpg`) or Samsung Motion Photos.
    """
    if source_path.suffix.lower() not in (".jpg", ".jpeg"):
        return None
    try:
        data = source_path.read_bytes()
    except OSError:
        return None

    size = len(data)
    if size < 4096:
        return None

    # 1. Check XMP GCamera:MicroVideoOffset (bytes from EOF)
    head_window = data[: min(size, 65536)]
    for match in re.finditer(rb'GCamera:MicroVideoOffset="(\d+)"', head_window):
        offset_from_eof = int(match.group(1))
        if 512 <= offset_from_eof < size - 256:
            start = size - offset_from_eof
            if b"ftyp" in data[start : start + 32]:
                return data[start:]

    # 2. Fallback scan for an MP4 `ftyp` box after a JPEG EOI (\xff\xd9) marker
    search_from = max(256, size // 5)
    for sig in FTYP_SIGNATURES:
        idx = data.find(sig, search_from)
        if idx > 8:
            box_start = idx - 4
            # Verify there is a JPEG EOI before box_start
            if b"\xff\xd9" in data[max(0, box_start - 64) : box_start + 2]:
                return data[box_start:]

    return None


def build_xmp_app1_segment(mp4_length: int) -> bytes:
    """Build a standard JPEG APP1 XMP segment advertising GCamera:MicroVideoOffset."""
    xmp_xml = (
        '<?xpacket begin="\xef\xbb\xbf" id="W5M0MpCehiHzreSzNTczkc9d"?>'
        '<x:xmpmeta xmlns:x="adobe:ns:meta/">'
        '<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">'
        '<rdf:Description rdf:about="" '
        'xmlns:GCamera="http://ns.google.com/photos/1.0/camera/" '
        'GCamera:MicroVideo="1" '
        'GCamera:MicroVideoVersion="1" '
        f'GCamera:MicroVideoOffset="{mp4_length}" '
        'GCamera:MicroVideoPresentationTimestampUs="0"/>'
        "</rdf:RDF>"
        "</x:xmpmeta>"
        '<?xpacket end="w"?>'
    ).encode("utf-8")
    payload = b"http://ns.adobe.com/xap/1.0/\x00" + xmp_xml
    seg_len = len(payload) + 2
    return b"\xff\xe1" + seg_len.to_bytes(2, "big") + payload


def attach_motion_mp4_to_jpeg(jpeg_path: Path, mp4_bytes: bytes) -> Tuple[bool, Optional[Path]]:
    """
    Injects XMP `GCamera:MicroVideoOffset` right after SOI (or APP0/APP1 EXIF) and appends
    the MP4 motion stream after JPEG EOI so Google Photos plays the resized JPEG as a Motion Photo.
    """
    if not mp4_bytes or len(mp4_bytes) < 32:
        return False, None
    raw = jpeg_path.read_bytes()
    if not raw.startswith(b"\xff\xd8"):
        return False, None

    # Insert XMP APP1 right after SOI (\xff\xd8) and any initial APP0/EXIF APP1 segments
    pos = 2
    n = len(raw)
    while pos + 4 <= n and raw[pos] == 0xFF and raw[pos + 1] in (0xE0, 0xE1):
        seg_size = int.from_bytes(raw[pos + 2 : pos + 4], "big")
        if seg_size < 2 or pos + 2 + seg_size > n:
            break
        # Skip existing GCamera XMP if any
        if raw[pos + 1] == 0xE1 and raw[pos + 4 : pos + 33] == b"http://ns.adobe.com/xap/1.0/\x00":
            raw = raw[:pos] + raw[pos + 2 + seg_size :]
            n = len(raw)
            break
        pos += 2 + seg_size

    xmp_seg = build_xmp_app1_segment(len(mp4_bytes))
    combined = raw[:pos] + xmp_seg + raw[pos:] + mp4_bytes
    jpeg_path.write_bytes(combined)
    return True, None
