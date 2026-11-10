#!/usr/bin/env python3
"""Convert a fixed-layout EPUB comic into a Salt Reader CBZ.

Pages are ordered by the EPUB spine, so right-to-left / left-to-right reading
order is preserved exactly. Images are copied as-is (no recompression).
"""
from __future__ import annotations

import posixpath
import re
import sys
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path

OPF_NS = {"opf": "http://www.idpf.org/2007/opf"}
CONTAINER_NS = {"c": "urn:oasis:names:tc:opendocument:xmlns:container"}


def main() -> None:
    if len(sys.argv) < 3:
        raise SystemExit("usage: convert_epub.py input.epub output.cbz")
    src, dst = Path(sys.argv[1]), Path(sys.argv[2])
    dst.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(src) as z:
        container = ET.fromstring(z.read("META-INF/container.xml"))
        opf_path = container.find(".//c:rootfile", CONTAINER_NS).get("full-path")
        opf = ET.fromstring(z.read(opf_path))
        base = Path(opf_path).parent
        manifest = {
            item.get("id"): item.get("href")
            for item in opf.findall(".//opf:manifest/opf:item", OPF_NS)
        }
        spine = [ref.get("idref") for ref in opf.findall(".//opf:spine/opf:itemref", OPF_NS)]
        pages: list[str] = []
        for idref in spine:
            href = manifest.get(idref)
            if not href or not href.lower().endswith((".xhtml", ".html", ".htm")):
                continue
            doc = z.read(str(base / href)).decode("utf-8", "ignore")
            for raw in re.findall(r'<img[^>]+src="([^"]+)"', doc, flags=re.I):
                image_path = posixpath.normpath(
                    posixpath.join(str(base), posixpath.dirname(href), raw)
                )
                if image_path.startswith("./"):
                    image_path = image_path[2:]
                if image_path in z.namelist():
                    pages.append(image_path)
        if not pages:
            raise SystemExit("no image pages found")
        with zipfile.ZipFile(dst, "w", zipfile.ZIP_STORED) as out:
            for index, name in enumerate(pages, start=1):
                ext = Path(name).suffix.lower()
                out.writestr(f"{index:04d}{ext}", z.read(name))
    print(f"{dst}: {len(pages)} pages, {dst.stat().st_size / 1024 / 1024:.2f} MiB")
    print("\n".join(pages[:3]))
    print("...")


if __name__ == "__main__":
    main()
