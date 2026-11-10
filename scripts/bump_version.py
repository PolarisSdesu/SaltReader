#!/usr/bin/env python3
"""Manage SaltReader's version in one place.

Usage:
  python3 scripts/bump_version.py show
  python3 scripts/bump_version.py alpha|beta|rc      # bump pre-release counter (or switch stage)
  python3 scripts/bump_version.py release            # promote current base version to stable
  python3 scripts/bump_version.py patch|minor|major  # bump base version, reset to alpha.1
  python3 scripts/bump_version.py set 1.2.3-alpha.1  # set an explicit version name

Every non-show command increments versionCode by one, so APKs always upgrade in place.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FILE = ROOT / "version.properties"
STAGES = {"alpha", "beta", "rc", "stable"}


def load() -> dict[str, str]:
    data: dict[str, str] = {}
    for line in FILE.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            data[key.strip()] = value.strip()
    return data


def save(data: dict[str, str]) -> None:
    order = ["versionMajor", "versionMinor", "versionPatch", "versionStage", "versionStageNumber", "versionCode"]
    lines = ["# Single source of truth for the app version (managed by scripts/bump_version.py)."]
    lines += [f"{key}={data[key]}" for key in order]
    FILE.write_text("\n".join(lines) + "\n", encoding="utf-8")


def version_name(d: dict[str, str]) -> str:
    base = f"{d['versionMajor']}.{d['versionMinor']}.{d['versionPatch']}"
    if d["versionStage"] == "stable":
        return base
    return f"{base}-{d['versionStage']}.{d['versionStageNumber']}"


def show(d: dict[str, str]) -> None:
    print(f"versionName = {version_name(d)}")
    print(f"versionCode = {d['versionCode']}")


def bump_code(d: dict[str, str]) -> None:
    d["versionCode"] = str(int(d["versionCode"]) + 1)


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print(__doc__)
        return 2
    action = argv[1].lower()
    d = load()

    if action == "show":
        show(d)
        return 0

    if action in {"alpha", "beta", "rc"}:
        if d["versionStage"] == action:
            d["versionStageNumber"] = str(int(d["versionStageNumber"]) + 1)
        else:
            d["versionStage"] = action
            d["versionStageNumber"] = "1"
        bump_code(d)
    elif action == "release":
        if d["versionStage"] != "stable":
            d["versionStage"] = "stable"
            d["versionStageNumber"] = "0"
            bump_code(d)
        else:
            print("Already stable; use patch/minor/major for the next release.")
            return 1
    elif action in {"patch", "minor", "major"}:
        if action == "patch":
            d["versionPatch"] = str(int(d["versionPatch"]) + 1)
        elif action == "minor":
            d["versionMinor"] = str(int(d["versionMinor"]) + 1)
            d["versionPatch"] = "0"
        else:
            d["versionMajor"] = str(int(d["versionMajor"]) + 1)
            d["versionMinor"] = "0"
            d["versionPatch"] = "0"
        d["versionStage"] = "alpha"
        d["versionStageNumber"] = "1"
        bump_code(d)
    elif action == "set":
        if len(argv) != 3:
            print("usage: bump_version.py set <major>.<minor>.<patch>[-stage.N]")
            return 2
        match = re.fullmatch(r"(\d+)\.(\d+)\.(\d+)(?:-([a-z]+)\.(\d+))?", argv[2].strip())
        if not match or (match.group(4) and match.group(4) not in STAGES):
            print("invalid version name")
            return 2
        d["versionMajor"], d["versionMinor"], d["versionPatch"] = match.group(1), match.group(2), match.group(3)
        d["versionStage"] = match.group(4) or "stable"
        d["versionStageNumber"] = match.group(5) or "0"
        bump_code(d)
    else:
        print(__doc__)
        return 2

    save(d)
    show(d)
    print("Remember to tag the commit after building, e.g.: git tag v" + version_name(d))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
