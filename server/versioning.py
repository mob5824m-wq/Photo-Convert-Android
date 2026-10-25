"""Deterministic base-10 rollover versioning (v1.0.0 -> v1.0.9 -> v1.1.0 -> v2.0.0)."""

from __future__ import annotations

import argparse
import re
import subprocess
from dataclasses import dataclass
from pathlib import Path

TAG_RE = re.compile(r"^v?(\d+)\.(\d)\.(\d)$")


@dataclass(frozen=True)
class ReleaseVersion:
    major: int
    minor: int
    patch: int
    prev_tag: str = ""

    @property
    def version(self) -> str:
        return f"{self.major}.{self.minor}.{self.patch}"

    @property
    def tag(self) -> str:
        return f"v{self.version}"

    @property
    def version_code(self) -> int:
        return self.major * 100 + self.minor * 10 + self.patch + 1


def bump_version(prev: tuple[int, int, int] | None) -> tuple[int, int, int]:
    if prev is None:
        return (1, 0, 0)
    major, minor, patch = prev
    total = major * 100 + minor * 10 + patch + 1
    return (total // 100, (total % 100) // 10, total % 10)


def parse_tag(tag_str: str) -> tuple[int, int, int] | None:
    match = TAG_RE.match(tag_str.strip())
    if not match:
        return None
    return (int(match.group(1)), int(match.group(2)), int(match.group(3)))


def resolve_next_release() -> ReleaseVersion:
    try:
        out = subprocess.check_output(
            ["git", "tag", "--list", "v*"],
            stderr=subprocess.DEVNULL,
            text=True,
        )
    except Exception:
        out = ""

    parsed: list[tuple[tuple[int, int, int], str]] = []
    for line in out.splitlines():
        tup = parse_tag(line)
        if tup is not None:
            parsed.append((tup, line.strip()))

    if not parsed:
        return ReleaseVersion(1, 0, 0, "")

    parsed.sort(key=lambda item: item[0])
    highest_tup, highest_tag = parsed[-1]
    nxt = bump_version(highest_tup)
    return ReleaseVersion(nxt[0], nxt[1], nxt[2], highest_tag)


def read_current_version(root: Path | None = None) -> str:
    base = root or Path(__file__).resolve().parent.parent
    version_file = base / "VERSION"
    if version_file.exists():
        raw = version_file.read_text(encoding="utf-8").strip()
        if raw:
            return raw.lstrip("v")
    return "1.0.0"


def main() -> None:
    parser = argparse.ArgumentParser(description="Resolve next rollover release version.")
    parser.add_argument("--github-output", type=Path, default=None)
    args = parser.parse_args()

    rel = resolve_next_release()
    if args.github_output:
        with args.github_output.open("a", encoding="utf-8") as fh:
            fh.write(f"version={rel.version}\n")
            fh.write(f"tag={rel.tag}\n")
            fh.write(f"version_code={rel.version_code}\n")
            fh.write(f"prev_tag={rel.prev_tag}\n")
    else:
        print(f"{rel.tag} (versionCode={rel.version_code})")


if __name__ == "__main__":
    main()
