#!/usr/bin/env python3
"""Package the shared Jarvis Python source for Android in-app updates."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PYTHON_ROOT = ROOT / "android" / "app" / "src" / "main" / "python"


def add_tree(archive: zipfile.ZipFile, source: Path, prefix: str) -> list[str]:
    files: list[str] = []
    for path in sorted(source.rglob("*")):
        if not path.is_file():
            continue
        relative = path.relative_to(source)
        if "__pycache__" in relative.parts or path.suffix in (".pyc", ".pyo"):
            continue
        name = f"{prefix}/{relative.as_posix()}"
        info = zipfile.ZipInfo(name, date_time=(2020, 1, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        info.external_attr = 0o644 << 16
        archive.writestr(info, path.read_bytes())
        files.append(name)
    return files


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", default="dist")
    parser.add_argument("--version", default=os.environ.get("GITHUB_SHA", "dev")[:12])
    args = parser.parse_args()

    output = Path(args.out)
    output.mkdir(parents=True, exist_ok=True)
    archive_path = output / "jarvis-src.zip"
    files: list[str] = []
    with zipfile.ZipFile(archive_path, "w", zipfile.ZIP_DEFLATED) as archive:
        files.extend(add_tree(archive, ROOT / "jarvis", "jarvis"))
        files.extend(add_tree(archive, PYTHON_ROOT / "jarvis_android", "jarvis_android"))

    digest = hashlib.sha256(archive_path.read_bytes()).hexdigest()
    (output / "jarvis-src.sha256").write_text(
        f"{digest}  {archive_path.name}\n", encoding="utf-8"
    )
    (output / "jarvis-src.json").write_text(
        json.dumps(
            {
                "version": args.version,
                "sha256": digest,
                "files": len(files),
                "bytes": archive_path.stat().st_size,
            },
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    print(f"{archive_path} ({len(files)} files, {archive_path.stat().st_size} bytes)")
    print(digest)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
