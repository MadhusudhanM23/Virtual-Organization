"""SHA-256 checks of the downloaded, untrusted assets (model weights, demo clip). Files are only hashed, never run."""

from __future__ import annotations

import hashlib
from pathlib import Path


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def read_manifest(manifest: Path) -> dict[str, str]:
    """Lines ``<sha256>  <file name>``; '#' starts a comment."""
    out: dict[str, str] = {}
    for raw in manifest.read_text(encoding="utf-8").splitlines():
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue
        digest, name = line.split(None, 1)
        out[name.strip()] = digest.lower()
    return out


def verify_assets(assets: Path, manifest: Path) -> list[str]:
    problems = []
    for name, digest in read_manifest(manifest).items():
        path = assets / name
        if not path.is_file():
            problems.append(f"missing: {name}")
        elif sha256_of(path) != digest:
            problems.append(f"sha256 mismatch: {name}")
    return problems
