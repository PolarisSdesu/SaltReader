#!/usr/bin/env python3
"""生成用于对照 WebView / Native 渲染的 HTML5 标签预览 EPUB。

用法: python3 tools/make_html5_tags_epub.py [输出路径]

章节按标签类别划分，每个标签用小标题标注，便于在阅读器里逐屏比对。
生成的文件放进书库根目录后下拉刷新即可。
"""
from __future__ import annotations

import base64
import struct
import sys
import zipfile
import zlib

OUT = sys.argv[1] if len(sys.argv) > 1 else "Html5-Tags.epub"


def png_bytes(width: int = 180, height: int = 110) -> bytes:
    """生成一张渐变色 PNG（不依赖 Pillow）。"""
    rows = []
    for y in range(height):
        row = bytearray([0])  # filter: none
        for x in range(width):
            row += bytes((40 + x * 180 // width, 120 + y * 100 // height, 200 - x * 120 // width))
        rows.append(bytes(row))
    raw = b"".join(rows)

    def chunk(kind: bytes, data: bytes) -> bytes:
        return (
            struct.pack(">I", len(data))
            + kind
            + data
            + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
        )

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")


# 图片必须作为 EPUB 内的独立文件、用相对路径引用：EpubIndexer 会丢弃源码里直接写死的
# `data:` URI（它只负责把相对路径图片内联），否则阅读器里整张图都不会出现。
IMAGE_SRC = "images/sample.png"
IMAGE = IMAGE_SRC


def xhtml(title: str, body: str) -> str:
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<!DOCTYPE html>\n<html xmlns="http://www.w3.org/1999/xhtml">'
        f"<head><title>{title}</title></head><body>{body}</body></html>"
    )


def sec(tag: str, body: str) -> str:
    return f'<h3 class="tag">[{tag}]</h3>{body}'


C1 = "".join(
    [
        "<h1>第一章 文档结构与块级元素</h1>",
        sec("h1~h6", "<h1>一级标题 H1</h1><h2>二级标题 H2</h2><h3>三级标题 H3</h3>"
                     "<h4>四级标题 H4</h4><h5>五级标题 H5</h5><h6>六级标题 H6</h6>"),
        sec("p / div", "<p>普通段落 P：中文两端对齐，英文 mixed Latin text，数字 12345。</p>"
                       "<div>div 块级容器，没有默认边距，内部文字应当正常换行。</div>"),
        sec("header / footer / main", "<header>header 页眉区域</header><main>main 主内容区域里的正文。</main>"
                                      "<footer>footer 页脚区域</footer>"),
        sec("section / article", "<section><h4>section 小节标题</h4><p>section 内的段落正文。</p></section>"
                                 "<article><h4>article 文章标题</h4><p>article 内的段落正文。</p></article>"),
        sec("aside / nav", "<aside>aside 旁注：不属于正文的补充说明。</aside>"
                           "<nav><a href=\"#\">nav 导航链接一</a> · <a href=\"#\">导航链接二</a></nav>"),
        sec("address", "<address>address 地址：上海市某某区某某路 123 号</address>"),
        sec("blockquote", "<blockquote><p>blockquote 引用段落：这是一段被引用的文字，通常会有左侧竖线和缩进。</p></blockquote>"),
        sec("pre", "<pre>pre 预格式化文本\n    缩进四个空格\n\t制表符\t与空行\n\n结束</pre>"),
        sec("hr", "<p>hr 之前</p><hr/><p>hr 之后</p>"),
    ]
)

C2 = "".join(
    [
        "<h1>第二章 列表、表格与图</h1>",
        sec("ul / li", "<ul><li>无序列表项一</li><li>无序列表项二，带 <b>粗体</b></li><li>无序列表项三</li></ul>"),
        sec("ol / li", "<ol><li>有序列表项一</li><li>有序列表项二</li><li>有序列表项三</li></ol>"),
        sec("dl / dt / dd", "<dl><dt>dt 术语一</dt><dd>dd 术语一的解释文字。</dd>"
                            "<dt>dt 术语二</dt><dd>dd 术语二的解释文字。</dd></dl>"),
        sec(
            "table / caption / colgroup / thead / tbody / tfoot",
            "<table><caption>caption 表格标题</caption>"
            "<colgroup><col/><col/></colgroup>"
            "<thead><tr><th>th 列一</th><th>th 列二</th></tr></thead>"
            "<tbody><tr><td>td 单元格 1</td><td>td 单元格 2</td></tr>"
            "<tr><td>td 单元格 3</td><td>td 单元格 4</td></tr></tbody>"
            "<tfoot><tr><td>tfoot 合计</td><td>100</td></tr></tfoot></table>",
        ),
        sec("figure / figcaption / img", f'<figure><img src="{IMAGE}" alt="示例图片"/>'
                                          "<figcaption>figcaption 图片说明文字</figcaption></figure>"),
    ]
)

C3 = "".join(
    [
        "<h1>第三章 行内语义元素</h1>",
        sec("a", '<p>这是一个 <a href="#">a 链接（阅读器内不可跳转）</a> 示例。</p>'),
        sec("strong / b", "<p><strong>strong 重要</strong> 与 <b>b 粗体</b> 的对比。</p>"),
        sec("em / i", "<p><em>em 强调（斜体）</em> 与 <i>i 斜体</i> 的对比。</p>"),
        sec("u / s / del / ins", "<p><u>u 下划线</u>、<s>s 删除线</s>、<del>del 删除</del>、<ins>ins 插入</ins>。</p>"),
        sec("mark", "<p><mark>mark 高亮标记</mark> 后面的普通文字。</p>"),
        sec("small / big", "<p><small>small 小号文字</small> 与 <big>big 大号文字</big>。</p>"),
        sec("sub / sup", "<p>H<sub>2</sub>O 与 E=mc<sup>2</sup> 的上下标。</p>"),
        sec("code / kbd / samp / var", "<p><code>code 行内代码</code>、<kbd>Ctrl+C</kbd>、"
                                        "<samp>samp 程序输出</samp>、<var>var 变量</var>。</p>"),
        sec("cite / q", '<p><cite>cite 作品名</cite> 与 <q>q 短引用</q> 的对比。</p>'),
        sec("abbr / time", '<p><abbr title="HyperText Markup Language">HTML</abbr> 标准，'
                           '发布于 <time datetime="2026-11-14">2026 年 11 月 14 日</time>。</p>'),
        sec("ruby / rt / rp", "<p><ruby>漢字<rp>(</rp><rt>かんじ</rt><rp>)</rp></ruby> 的注音。</p>"),
        sec("br / wbr", "<p>第一行<br/>第二行（br 换行），这里有一个很长的词"
                        "Supercalifragilisticexpialidocious<wbr/>后面继续。</p>"),
        sec("span / bdi / bdo", '<p><span>span 行内容器</span>、<bdi>bdi 隔离</bdi>、'
                                '<bdo dir="rtl">bdo 反向文字</bdo>。</p>'),
    ]
)

C4 = "".join(
    [
        "<h1>第四章 多媒体、内嵌与交互</h1>",
        sec("svg", '<svg xmlns="http://www.w3.org/2000/svg" width="160" height="80">'
                   '<rect x="2" y="2" width="156" height="76" fill="#8ecae6"/>'
                   '<circle cx="80" cy="40" r="26" fill="#fb8500"/></svg>'),
        sec("canvas / video / audio", '<canvas width="160" height="80">canvas 后备文字</canvas>'
                                      '<video controls="controls" width="160">video 后备文字</video>'
                                      '<audio controls="controls">audio 后备文字</audio>'),
        sec("iframe", '<iframe width="160" height="80" src="about:blank" title="iframe"></iframe>'),
        sec("map / area", f'<img src="{IMAGE}" alt="map 底图" usemap="#demo"/>'
                          '<map name="demo"><area shape="rect" coords="0,0,90,55" href="#" alt="左半区"/></map>'),
        sec("details / summary", "<details><summary>summary 点击展开</summary>"
                                 "<p>details 展开后的正文内容。</p></details>"),
        sec("dialog", '<dialog open="open">dialog 对话框内容（open 状态）</dialog>'),
        sec(
            "form 控件",
            '<form><label>label 文本 <input type="text" value="input 文本" size="12"/></label>'
            '<input type="checkbox" checked="checked"/> checkbox '
            '<input type="radio" checked="checked"/> radio '
            '<button type="button">button 按钮</button>'
            '<select><option>select 选项一</option><option>选项二</option></select>'
            '<textarea rows="2" cols="18">textarea 多行文本</textarea>'
            '<fieldset><legend>legend 分组标题</legend>fieldset 内容</fieldset>'
            '<meter value="0.6">meter 60%</meter><progress value="0.4">progress 40%</progress>'
            '<output>output 计算结果</output></form>',
        ),
        sec("template / noscript", "<template><p>template 模板内容（不应显示）</p></template>"
                                   "<noscript><p>noscript 无脚本时的提示。</p></noscript>"),
        sec("object / embed / script（仅源码展示）", "<pre>&lt;object data=\"example.svg\"&gt;&lt;/object&gt;\n"
                                                    "&lt;embed src=\"example.svg\"/&gt;\n"
                                                    "&lt;script&gt;alert(1)&lt;/script&gt;</pre>"),
    ]
)

chapters = [
    ("第一章 文档结构与块级元素", C1),
    ("第二章 列表、表格与图", C2),
    ("第三章 行内语义元素", C3),
    ("第四章 多媒体、内嵌与交互", C4),
]

container = (
    '<?xml version="1.0" encoding="utf-8"?>\n'
    '<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">'
    '<rootfiles><rootfile full-path="OEBPS/content.opf" '
    'media-type="application/oebps-package+xml"/></rootfiles></container>'
)

manifest = (
    '<item id="img1" href="images/sample.png" media-type="image/png"/>'
    + "".join(
        f'<item id="c{i + 1}" href="c{i + 1}.xhtml" media-type="application/xhtml+xml"/>'
        for i in range(len(chapters))
    )
)
spine = "".join(f'<itemref idref="c{i + 1}"/>' for i in range(len(chapters)))
opf = (
    '<?xml version="1.0" encoding="utf-8"?>\n'
    '<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="bookid">'
    '<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">'
    '<dc:title>HTML5 标签预览</dc:title><dc:identifier id="bookid">html5-tags</dc:identifier>'
    '<dc:language>zh-CN</dc:language><dc:creator>SaltReader</dc:creator></metadata>'
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
    '<head><meta name="dtb:uid" content="html5-tags"/></head>'
    "<docTitle><text>HTML5 标签预览</text></docTitle>"
    f"<navMap>{nav_points}</navMap></ncx>"
)

with zipfile.ZipFile(OUT, "w") as archive:
    archive.writestr("mimetype", "application/epub+zip", compress_type=zipfile.ZIP_STORED)
    files = {
        "META-INF/container.xml": container,
        "OEBPS/content.opf": opf,
        "OEBPS/toc.ncx": ncx,
    }
    archive.writestr("OEBPS/images/sample.png", png_bytes(), compress_type=zipfile.ZIP_DEFLATED)
    for i, (title, body) in enumerate(chapters):
        files[f"OEBPS/c{i + 1}.xhtml"] = xhtml(title, body)
    for name, content in files.items():
        archive.writestr(name, content, compress_type=zipfile.ZIP_DEFLATED)

print(f"wrote {OUT}")
