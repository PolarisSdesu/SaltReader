//! 轻量 CSS 子集与标签默认样式（书籍自带样式表的解析与应用）。

#[derive(Clone, Copy, Debug)]
pub(crate) struct Style {
    pub(crate) size: f32,          // em
    pub(crate) line_height: Option<f32>, // None = 继承阅读器行距
    pub(crate) bold: bool,
    pub(crate) italic: bool,
    pub(crate) mono: bool,
    pub(crate) align: u8,          // 0 left, 1 center, 2 right
    pub(crate) left_indent: f32,   // em
    pub(crate) first_indent: f32,  // em
    pub(crate) space_before: f32,  // em
    pub(crate) space_after: f32,   // em
    pub(crate) bullet: bool,
}

impl Default for Style {
    fn default() -> Self {
        Style {
            size: 1.0,
            line_height: None,
            bold: false,
            italic: false,
            mono: false,
            align: 0,
            left_indent: 0.0,
            first_indent: 2.0,
            space_before: 0.0,
            space_after: 0.55,
            bullet: false,
        }
    }
}

/// 极简 CSS：只解析 `sel { decls }`，选择器支持 tag、.class、tag.class。
#[derive(Default)]
pub(crate) struct CssRule {
    pub(crate) tag: Option<String>,
    pub(crate) class_name: Option<String>,
    pub(crate) declarations: Vec<(String, String)>,
}

pub(crate) fn parse_css(css: &str) -> Vec<CssRule> {
    let mut rules = Vec::new();
    let mut rest = css;
    while let Some(open) = rest.find('{') {
        let selector = rest[..open].trim();
        let Some(close) = rest[open..].find('}') else { break };
        let body = &rest[open + 1..open + close];
        rest = &rest[open + close + 1..];
        for raw_selector in selector.split(',') {
            let raw_selector = raw_selector.trim();
            if raw_selector.is_empty() || raw_selector.contains(':') || raw_selector.contains('>') || raw_selector.contains(' ') {
                continue;
            }
            let mut rule = CssRule::default();
            let mut remaining = raw_selector;
            if let Some(dot) = remaining.find('.') {
                rule.class_name = Some(remaining[dot + 1..].to_string());
                remaining = &remaining[..dot];
            }
            if !remaining.is_empty() {
                rule.tag = Some(remaining.to_lowercase());
            }
            for declaration in body.split(';') {
                let Some(colon) = declaration.find(':') else { continue };
                let name = declaration[..colon].trim().to_lowercase();
                let value = declaration[colon + 1..].trim().to_string();
                if !name.is_empty() && !value.is_empty() {
                    rule.declarations.push((name, value));
                }
            }
            rules.push(rule);
        }
    }
    rules
}

pub(crate) fn apply_declarations(style: &mut Style, declarations: &[(String, String)]) {
    for (name, value) in declarations {
        let value = value.replace("!important", "").trim().to_string();
        match name.as_str() {
            "font-size" => {
                if let Some(em) = parse_em(&value) {
                    style.size = em.clamp(0.6, 3.0);
                }
            }
            "line-height" => {
                if let Ok(multiplier) = value.parse::<f32>() {
                    style.line_height = Some(multiplier.clamp(0.9, 3.0));
                }
            }
            "text-align" => {
                style.align = match value.as_str() {
                    "center" => 1,
                    "right" => 2,
                    "justify" => 3,
                    _ => 0,
                }
            }
            "text-indent" => {
                if let Some(em) = parse_em(&value) {
                    style.first_indent = em.clamp(0.0, 4.0);
                }
            }
            "margin" | "margin-top" | "margin-bottom" => {
                let parts: Vec<f32> = value
                    .split_whitespace()
                    .map(|part| parse_em(part).unwrap_or(0.0))
                    .collect();
                match name.as_str() {
                    "margin-top" => style.space_before = parts.first().copied().unwrap_or(0.0),
                    "margin-bottom" => style.space_after = parts.first().copied().unwrap_or(0.0),
                    _ => {
                        if parts.len() >= 3 {
                            style.space_before = parts[0];
                            style.space_after = parts[2];
                        } else if parts.len() == 2 {
                            style.space_before = parts[0];
                            style.space_after = parts[0];
                        } else if let Some(one) = parts.first() {
                            style.space_before = *one;
                            style.space_after = *one;
                        }
                    }
                }
            }
            "font-weight" => {
                style.bold = matches!(value.as_str(), "bold" | "700" | "800" | "900") || value.parse::<u32>().map(|w| w >= 600).unwrap_or(false)
            }
            "font-style" => style.italic = value == "italic" || value == "oblique",
            _ => {}
        }
    }
}

pub(crate) fn parse_em(value: &str) -> Option<f32> {
    let value = value.trim();
    if let Some(text) = value.strip_suffix("rem") {
        return text.trim().parse::<f32>().ok();
    }
    if let Some(text) = value.strip_suffix("em") {
        return text.trim().parse::<f32>().ok();
    }
    if let Some(text) = value.strip_suffix('%') {
        return text.trim().parse::<f32>().ok().map(|v| v / 100.0);
    }
    if let Some(text) = value.strip_suffix("px") {
        return text.trim().parse::<f32>().ok().map(|v| v / 16.0);
    }
    if let Some(text) = value.strip_suffix("pt") {
        return text.trim().parse::<f32>().ok().map(|v| v / 12.0);
    }
    None
}

pub(crate) fn default_style_for_tag(tag: &str) -> Option<Style> {
    let mut style = Style::default();
    match tag {
        "p" => {
            style.align = 3;
        }
        "h1" => {
            style.size = 1.6;
            style.line_height = Some(1.3);
            style.bold = true;
            style.align = 1;
            style.first_indent = 0.0;
            style.space_before = 0.9;
            style.space_after = 0.85;
        }
        "h2" => {
            style.size = 1.4;
            style.line_height = Some(1.3);
            style.bold = true;
            style.align = 1;
            style.first_indent = 0.0;
            style.space_before = 0.9;
            style.space_after = 0.85;
        }
        // 与 WebView #book h3 / h4-h6 一致：1.25em / 1.1em，居中加粗。
        "h3" => {
            style.size = 1.25;
            style.line_height = Some(1.3);
            style.bold = true;
            style.align = 1;
            style.first_indent = 0.0;
            style.space_before = 0.9;
            style.space_after = 0.85;
        }
        "h4" | "h5" | "h6" => {
            style.size = 1.1;
            style.line_height = Some(1.3);
            style.bold = true;
            style.align = 1;
            style.first_indent = 0.0;
            style.space_before = 0.9;
            style.space_after = 0.85;
        }
        "blockquote" => {
            style.left_indent = 1.5;
            style.first_indent = 0.0;
            style.space_before = 0.6;
            style.space_after = 0.6;
        }
        // 结构性容器：WebView 的 UA 样式没有首行缩进。
        "main" | "nav" | "aside" | "header" | "footer" => {
            style.first_indent = 0.0;
            style.space_after = 0.2;
        }
        "address" => {
            style.italic = true;
            style.first_indent = 0.0;
            style.space_before = 0.6;
            style.space_after = 0.6;
        }
        "details" | "form" | "fieldset" | "dialog" => {
            style.first_indent = 0.0;
            style.space_before = 0.5;
            style.space_after = 0.5;
        }
        "summary" => {
            style.bold = true;
            style.first_indent = 0.0;
            style.space_after = 0.4;
        }
        "legend" => {
            style.bold = true;
            style.first_indent = 0.0;
            style.space_before = 0.4;
            style.space_after = 0.2;
        }
        "figure" => {
            style.align = 1;
            style.first_indent = 0.0;
            style.space_before = 0.8;
            style.space_after = 0.4;
        }
        "figcaption" => {
            style.align = 1;
            style.size = 0.85;
            style.first_indent = 0.0;
            style.space_after = 0.8;
        }
        "dl" => {
            style.first_indent = 0.0;
            style.space_after = 0.2;
        }
        "dt" => {
            style.bold = true;
            style.first_indent = 0.0;
            style.space_before = 0.3;
            style.space_after = 0.1;
        }
        "dd" => {
            style.left_indent = 2.0;
            style.first_indent = 0.0;
            style.space_after = 0.3;
        }
        "li" => {
            style.left_indent = 1.5;
            style.first_indent = 0.0;
            style.bullet = true;
            style.space_after = 0.3;
        }
        "pre" => {
            style.size = 0.86;
            style.line_height = Some(1.5);
            style.mono = true;
            style.first_indent = 0.0;
            style.space_before = 0.7;
            style.space_after = 0.7;
        }
        "td" | "th" => {
            style.first_indent = 0.0;
            style.space_after = 0.2;
        }
        "div" | "section" | "article" | "body" | "html" => {
            style.first_indent = 0.0;
            style.space_after = 0.0;
        }
        "br" | "hr" | "img" => {
            style.first_indent = 0.0;
            style.space_after = 0.0;
        }
        _ => return None,
    }
    Some(style)
}

/// WebView 阅读器 CSS 里以 `#book .class`（id+class）定义的轻小说常见样式，优先于书籍 CSS。
pub(crate) fn apply_known_class(style: &mut Style, class_name: &str) {
    match class_name {
        "pius1" => {
            style.size = 1.05;
            style.align = 1;
            style.bold = true;
            style.first_indent = 0.0;
            style.line_height = Some(1.25);
            style.space_before = 1.5;
            style.space_after = 0.1;
        }
        "pius2" => {
            style.size = 1.4;
            style.align = 1;
            style.bold = true;
            style.first_indent = 0.0;
            style.line_height = Some(1.25);
            style.space_before = 0.0;
            style.space_after = 1.35;
        }
        "pius3" => {
            style.size = 1.3;
            style.align = 1;
            style.bold = true;
            style.first_indent = 0.0;
            style.line_height = Some(1.25);
            style.space_before = 1.2;
            style.space_after = 1.2;
        }
        "biaoti1" => {
            style.size = 1.2;
            style.align = 1;
            style.bold = true;
            style.first_indent = 0.0;
            style.space_before = 0.4;
            style.space_after = 0.9;
        }
        "biaoti2" => {
            style.size = 1.2;
            style.align = 0;
            style.bold = true;
            style.first_indent = 0.0;
            style.left_indent = 3.0;
            style.space_before = 0.4;
            style.space_after = 0.9;
        }
        "meg" => {
            style.size = 1.3;
            style.align = 1;
            style.bold = true;
            style.first_indent = 0.0;
            style.space_before = 0.6;
            style.space_after = 0.6;
        }
        "stress" => {
            style.bold = true;
            style.size = 1.1;
        }
        "center" | "illus" | "images" | "cover" | "summary" => {
            style.align = 1;
            style.first_indent = 0.0;
        }
        "right" | "author" => {
            style.align = 2;
            style.first_indent = 0.0;
        }
        "left" => {
            style.align = 0;
            style.first_indent = 0.0;
        }
        "message" => {
            style.first_indent = 0.0;
            style.space_before = 0.2;
            style.space_after = 0.2;
        }
        // 阅读器自动注入的章节标题：WebView 用 #book h2.chapter-heading 的紧凑上边距。
        "chapter-heading" => {
            style.align = 1;
            style.bold = true;
            style.first_indent = 0.0;
            style.space_before = 0.35;
            style.space_after = 1.15;
        }
        _ => {}
    }
}

/// 行内样式开/关标记：b/i/u/s/code 在排版文本里插入这些控制字符。
pub(crate) fn inline_marker(tag: &str, closing: bool) -> Option<char> {
    // <wbr> 是零宽断行机会：UAX #14 把它当成可行断点，但不占宽度。
    if tag == "wbr" {
        return if closing { None } else { Some('\u{200B}') };
    }
    // <q> 在浏览器里由 UA 样式自动加弯引号。
    if tag == "q" {
        return Some(if closing { '”' } else { '“' });
    }
    let pair = match tag {
        "b" | "strong" => ('\u{E000}', '\u{E001}'),
        // cite / var 在浏览器里默认斜体。
        "i" | "em" | "cite" | "var" => ('\u{E002}', '\u{E003}'),
        "u" | "ins" => ('\u{E004}', '\u{E005}'),
        "s" | "strike" | "del" => ('\u{E006}', '\u{E007}'),
        "code" | "tt" | "kbd" | "samp" => ('\u{E008}', '\u{E009}'),
        "sup" => ('\u{E00A}', '\u{E00B}'),
        "sub" => ('\u{E00C}', '\u{E00D}'),
        "small" => ('\u{E00E}', '\u{E00F}'),
        "big" => ('\u{E010}', '\u{E011}'),
        "mark" => ('\u{E012}', '\u{E013}'),
        _ => return None,
    };
    Some(if closing { pair.1 } else { pair.0 })
}

pub(crate) fn table_style() -> Style {
    Style {
        size: 0.86,
        line_height: Some(1.5),
        first_indent: 0.0,
        space_before: 0.5,
        space_after: 0.6,
        ..Style::default()
    }
}

pub(crate) fn is_block_tag(tag: &str) -> bool {
    matches!(
        tag,
        "p" | "h1" | "h2" | "h3" | "h4" | "h5" | "h6" | "blockquote" | "li" | "pre" | "div"
            | "section" | "article" | "header" | "footer" | "aside" | "figure" | "figcaption"
            | "main" | "nav" | "address" | "details" | "summary" | "form" | "fieldset" | "legend"
            | "dialog"
            | "ul" | "ol" | "dl" | "dt" | "dd" | "table" | "tbody" | "thead" | "tr" | "td" | "th"
            | "br" | "hr" | "body" | "html"
    )
}

pub(crate) fn is_inline_tag(tag: &str) -> bool {
    matches!(
        tag,
        "b" | "strong" | "i" | "em" | "span" | "a" | "u" | "small" | "sup" | "sub" | "code"
            | "font" | "big" | "mark" | "cite" | "q" | "s" | "strike" | "del" | "ins"
            | "kbd" | "samp" | "tt" | "var" | "time" | "abbr" | "bdi" | "bdo" | "ruby" | "wbr"
            | "label" | "output" | "meter" | "progress"
    )
}
