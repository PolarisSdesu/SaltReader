#!/usr/bin/env python3
"""生成用于对照 Native / WebView 两端对齐的小型 EPUB。

用法: python3 tools/make_justify_test_epub.py [输出路径]

包含三个章节：纯中文基准、长行内代码（`std::char::from_digit(num, radix)`）、
以及代码内部有空格的对照。把生成的文件放进书库根目录后下拉刷新即可看到。
"""
import sys
import zipfile

OUT = sys.argv[1] if len(sys.argv) > 1 else "Justify-Test2.epub"


def xhtml(title, body):
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<!DOCTYPE html>\n<html xmlns="http://www.w3.org/1999/xhtml">'
        f"<head><title>{title}</title></head><body>{body}</body></html>"
    )


LONG_TOKEN = "std::char::from_digit(num, radix)"

chapters = [
    (
        "第一章 纯中文对照",
        "<h2>第一章 纯中文对照</h2>"
        "<p>这是一个纯中文段落，没有任何行内代码，也没有英文单词，用来作为两端对齐的基准。"
        "它应该被拉满到版心右边界，字距均匀，不应该出现任何异常的空隙。我们再多写一些文字，"
        "让这一段折成四行以上，方便观察每一行的右边缘是否一致，以及中文标点的位置是否自然。</p>"
        "<p>第二段继续使用纯中文，确认段落间距与首行缩进在两个阅读器里保持一致，"
        "同时观察标点收尾处的行末处理。如果这里的观感一致，就可以把差异限定在代码 token 上。</p>",
    ),
    (
        "第二章 长行内代码",
        "<h2>第二章 长行内代码</h2>"
        f"<p>如果调用 <code>{LONG_TOKEN}</code> 这种会把字符转成数字的函数，"
        "那么这一行会因为那个不可断开的 token 而提前折行，上一行只剩下不到六成的宽度，"
        "两端对齐时中文字符之间会被拉开很大的空隙，这正是需要对照的现象。</p>"
        f"<p>再试一次：前面的正文先占据大约一半的宽度，然后调用 <code>{LONG_TOKEN}</code> "
        "这个函数把字符转成数字，观察两个阅读器是否在同一个空格处断行。</p>"
        "<p>最后一段没有代码，用来确认上一段的断行没有影响到后续行的对齐。</p>",
    ),
    (
        "第三章 代码内部空格",
        "<h2>第三章 代码内部空格</h2>"
        f"<p>短前缀然后 <code>{LONG_TOKEN}.unwrap_or_default()</code> 收尾。</p>"
        "<p>对照：短前缀然后 <code>std::char::from_digit(num,radix).unwrap_or_default()</code> 收尾。</p>",
    ),
]

container = (
    '<?xml version="1.0" encoding="utf-8"?>\n'
    '<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">'
    '<rootfiles><rootfile full-path="OEBPS/content.opf" '
    'media-type="application/oebps-package+xml"/></rootfiles></container>'
)

manifest = "".join(
    f'<item id="c{i + 1}" href="c{i + 1}.xhtml" media-type="application/xhtml+xml"/>'
    for i in range(len(chapters))
)
spine = "".join(f'<itemref idref="c{i + 1}"/>' for i in range(len(chapters)))
opf = (
    '<?xml version="1.0" encoding="utf-8"?>\n'
    '<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="bookid">'
    '<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">'
    "<dc:title>Justify-Test2</dc:title><dc:identifier id=\"bookid\">justify-test2</dc:identifier>"
    "<dc:language>zh-CN</dc:language><dc:creator>SaltReader</dc:creator></metadata>"
    f'<manifest><item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>{manifest}</manifest>'
    f'<spine toc="ncx">{spine}</spine></package>'
)

nav_points = "".join(
    f'<navPoint id="n{i + 1}" playOrder="{i + 1}"><navLabel><text>{title}</text></navLabel>'
    f'<content src="c{i + 1}.xhtml"/></navPoint>'
    for i, (title, _) in enumerate(chapters)
)
ncx = (
    '<?xml version="1.0" encoding="utf-8"?>\n'
    '<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">'
    '<head><meta name="dtb:uid" content="justify-test2"/></head>'
    "<docTitle><text>Justify-Test2</text></docTitle>"
    f"<navMap>{nav_points}</navMap></ncx>"
)

with zipfile.ZipFile(OUT, "w") as archive:
    archive.writestr("mimetype", "application/epub+zip", compress_type=zipfile.ZIP_STORED)
    files = {
        "META-INF/container.xml": container,
        "OEBPS/content.opf": opf,
        "OEBPS/toc.ncx": ncx,
    }
    for i, (title, body) in enumerate(chapters):
        files[f"OEBPS/c{i + 1}.xhtml"] = xhtml(title, body)
    for name, content in files.items():
        archive.writestr(name, content, compress_type=zipfile.ZIP_DEFLATED)

print(f"wrote {OUT}")
