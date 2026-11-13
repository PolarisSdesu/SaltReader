//! 轻小说文字排版核心（Rust → libtext_reader.so）。
//!
//! 输入：章节 HTML（含书籍自带 CSS）、版心尺寸、字号/行高、Kotlin Paint 实测字宽表；
//! 处理：轻量 HTML 词法 + CSS 子集（font-size / text-align / text-indent / margin /
//! font-weight / font-style / line-height / display），生成块级样式，再做 UAX #14 断行与分页；
//! 输出：`页面1\u{0}页面2…`，页内行用 `\n` 分隔；每行以 `\u{1}` + 5 位样式编码开头：
//! 字号倍率(0.1em)、行高倍率(0.1)、标志位(粗/斜/等宽)、对齐(0/1/2)、左缩进(0.1em)。

use std::collections::HashMap;

use jni::objects::{JFloatArray, JIntArray, JObject, JObjectArray, JString};
use jni::sys::{jboolean, jstring};
use jni::JNIEnv;
use unicode_linebreak::linebreaks;
use unicode_segmentation::UnicodeSegmentation;
use unicode_width::UnicodeWidthChar;

const LINE_STYLE: char = '\u{1}';

#[derive(Clone, Copy, Debug)]
struct Style {
    size: f32,          // em
    line_height: Option<f32>, // None = 继承阅读器行距
    bold: bool,
    italic: bool,
    mono: bool,
    align: u8,          // 0 left, 1 center, 2 right
    left_indent: f32,   // em
    first_indent: f32,  // em
    space_before: f32,  // em
    space_after: f32,   // em
    bullet: bool,
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

#[derive(Clone)]
struct Block {
    text: String,
    style: Style,
    table: Option<Vec<Vec<String>>>,
    image: Option<u32>,
    preserve: bool,
    /// 段落内存在连续 <br>（空行）时需要保留空行行盒。
    keep_blank_lines: bool,
}

use std::cell::RefCell;
thread_local! {
    /// 未解析行内图片时的占位文本（由 Kotlin 侧按语言传入）。
    static IMAGE_PLACEHOLDER: RefCell<String> = RefCell::new("［图片］".to_string());
}

fn image_placeholder() -> String {
    IMAGE_PLACEHOLDER.with(|value| value.borrow().clone())
}

/// 输出协议使用 \u0000..\u0005 等控制字符做分隔，正文里的控制字符必须清洗掉。
fn is_protocol_control(ch: char) -> bool {
    ch.is_control() && ch != '\n' && ch != '\t'
}

fn decode_entities(input: &str) -> String {
    let mut out = String::with_capacity(input.len());
    let mut chars = input.char_indices().peekable();
    while let Some((index, ch)) = chars.next() {
        if ch != '&' {
            out.push(if is_protocol_control(ch) { ' ' } else { ch });
            continue;
        }
        let rest = &input[index..];
        let end = rest.find(';').unwrap_or(0);
        if end == 0 || end > 10 {
            out.push(ch);
            continue;
        }
        let entity = &rest[1..end];
        let decoded = match entity {
            "amp" => Some('&'),
            "lt" => Some('<'),
            "gt" => Some('>'),
            "quot" => Some('"'),
            "apos" => Some('\''),
            "nbsp" => Some('\u{00A0}'),
            "mdash" => Some('—'),
            "ndash" => Some('–'),
            "hellip" => Some('…'),
            "ldquo" => Some('“'),
            "rdquo" => Some('”'),
            "lsquo" => Some('‘'),
            "rsquo" => Some('’'),
            _ => {
                if let Some(number) = entity.strip_prefix('#') {
                    let code = if let Some(hex) = number.strip_prefix('x').or_else(|| number.strip_prefix('X')) {
                        u32::from_str_radix(hex, 16).ok()
                    } else {
                        number.parse::<u32>().ok()
                    };
                    code.and_then(char::from_u32)
                } else {
                    None
                }
            }
        };
        if let Some(value) = decoded {
            out.push(if is_protocol_control(value) { ' ' } else { value });
            // 迭代器已越过 '&'：只需再吃掉 entity 字符（end-1 个）+ ';'（1 个）。
            for _ in 0..end {
                chars.next();
            }
        } else {
            out.push(ch);
        }
    }
    out
}

/// 极简 CSS：只解析 `sel { decls }`，选择器支持 tag、.class、tag.class。
#[derive(Default)]
struct CssRule {
    tag: Option<String>,
    class_name: Option<String>,
    declarations: Vec<(String, String)>,
}

fn parse_css(css: &str) -> Vec<CssRule> {
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

fn apply_declarations(style: &mut Style, declarations: &[(String, String)]) {
    for (name, value) in declarations {
        let value = value.replace("!important", "").trim().to_string();
        match name.as_str() {
            "font-size" => {
                if let Some(em) = parse_em(&value) {
                    style.size = em.clamp(0.6, 3.0);
                }
            }
            "line-height" => {
                if let Some(multiplier) = value.parse::<f32>().ok() {
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
                        if parts.len() >= 4 {
                            style.space_before = parts[0];
                            style.space_after = parts[2];
                        } else if parts.len() == 3 {
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

fn parse_em(value: &str) -> Option<f32> {
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

fn default_style_for_tag(tag: &str) -> Option<Style> {
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
        "h3" | "h4" | "h5" | "h6" => {
            style.size = 1.2;
            style.line_height = Some(1.3);
            style.bold = true;
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
fn apply_known_class(style: &mut Style, class_name: &str) {
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
        _ => {}
    }
}

/// 行内样式开/关标记：b/i/u/s/code 在排版文本里插入这些控制字符。
fn inline_marker(tag: &str, closing: bool) -> Option<char> {
    let pair = match tag {
        "b" | "strong" => ('\u{0006}', '\u{0007}'),
        "i" | "em" => ('\u{0008}', '\u{0009}'),
        "u" => ('\u{000A}', '\u{000B}'),
        "s" | "strike" | "del" => ('\u{000C}', '\u{000D}'),
        "code" | "tt" | "kbd" | "samp" => ('\u{000E}', '\u{000F}'),
        "sup" => ('\u{0010}', '\u{0011}'),
        "sub" => ('\u{0012}', '\u{0013}'),
        _ => return None,
    };
    Some(if closing { pair.1 } else { pair.0 })
}

fn table_style() -> Style {
    Style {
        size: 0.86,
        line_height: Some(1.5),
        first_indent: 0.0,
        space_before: 0.5,
        space_after: 0.6,
        ..Style::default()
    }
}

fn is_block_tag(tag: &str) -> bool {
    matches!(
        tag,
        "p" | "h1" | "h2" | "h3" | "h4" | "h5" | "h6" | "blockquote" | "li" | "pre" | "div"
            | "section" | "article" | "header" | "footer" | "aside" | "figure" | "figcaption"
            | "ul" | "ol" | "dl" | "dt" | "dd" | "table" | "tbody" | "thead" | "tr" | "td" | "th"
            | "br" | "hr" | "body" | "html"
    )
}

fn is_inline_tag(tag: &str) -> bool {
    matches!(
        tag,
        "b" | "strong" | "i" | "em" | "span" | "a" | "u" | "small" | "sup" | "sub" | "code"
            | "font" | "big" | "mark" | "cite" | "q"
    )
}

#[derive(Default)]
struct InlineState {
    bold: bool,
    italic: bool,
    mono: bool,
    depth: [u8; 6],
}

fn normalize_block_text(text: &str, preserve: bool) -> String {
    if preserve {
        return text.trim_matches('\n').to_string();
    }
    let mut out = String::with_capacity(text.len());
    let mut last_space = false;
    for ch in text.chars() {
        if ch == '\u{00A0}' {
            out.push(' ');
            last_space = true;
        } else if ch.is_whitespace() {
            if !last_space {
                out.push(' ');
                last_space = true;
            }
        } else {
            out.push(ch);
            last_space = false;
        }
    }
    out.trim().to_string()
}

/// 解析章节 HTML 得到块序列。
fn parse_html_blocks(html: &str, css_rules: &[CssRule], base_after: &mut f32) -> Vec<Block> {
    // 规则索引：避免每个块开/关都全量扫所有 CSS 规则。
    let mut tag_rules: HashMap<&str, Vec<usize>> = HashMap::new();
    let mut class_rules: HashMap<&str, Vec<usize>> = HashMap::new();
    for (rule_index, rule) in css_rules.iter().enumerate() {
        match (&rule.tag, &rule.class_name) {
            (Some(tag), None) => tag_rules.entry(tag.as_str()).or_default().push(rule_index),
            (_, Some(class_name)) => class_rules
                .entry(class_name.as_str())
                .or_default()
                .push(rule_index),
            _ => {}
        }
    }
    let mut blocks: Vec<Block> = Vec::new();
    let mut text = String::new();
    let mut tag_stack: Vec<String> = Vec::new();
    let mut current_style = Style::default();
    let mut inline = InlineState::default();
    let mut preserve = false;
    let mut skip_until: Option<String> = None;
    // display:none / hidden 子树：记录标签名与嵌套深度。
    let mut hidden: Option<(String, usize)> = None;
    let mut in_table = false;
    let mut table_rows: Vec<Vec<String>> = Vec::new();
    let mut table_row: Vec<String> = Vec::new();
    let mut table_cell: Option<String> = None;
    // <ol> 序号栈；<hr> 直接用分隔行近似 WebView 的水平线。
    let mut ol_stack: Vec<u32> = Vec::new();

    // 统一换行，避免 \r\n 作为单个 grapheme 破坏行协议。
    let normalized_html;
    let html = if html.contains('\r') {
        normalized_html = html.replace("\r\n", "\n").replace('\r', "\n");
        normalized_html.as_str()
    } else {
        html
    };
    let mut index = 0usize;
    let mut pending_space = false;
    let bytes = html.as_bytes();
    let len = bytes.len();

    while index < len {
        if bytes[index] == b'<' {
            // 注释
            if html[index..].starts_with("<!--") {
                if let Some(end) = html[index + 4..].find("-->") {
                    index += 4 + end + 3;
                } else {
                    break;
                }
                continue;
            }
            let Some(offset) = html[index..].find('>') else {
                // 没有匹配的 '>'：把 '<' 当普通文本，按 UTF-8 前进，绝不切片。
                text.push('<');
                index += '<'.len_utf8();
                continue;
            };
            let end = index + offset;
            let raw = &html[index + 1..end];
            index = end + 1;
            let trimmed = raw.trim();
            let closing = trimmed.starts_with('/');
            let body = trimmed.trim_start_matches('/').trim();
            if body.is_empty() {
                continue;
            }
            let mut parts = body.splitn(2, char::is_whitespace);
            let tag = parts.next().unwrap_or("").to_lowercase();
            if let Some(waiting) = &skip_until {
                if tag == *waiting {
                    skip_until = None;
                }
                continue;
            }
            if !closing && (tag == "rt" || tag == "rp") {
                skip_until = Some(tag.clone());
                continue;
            }
            if !closing && (tag == "script" || tag == "head") {
                skip_until = Some(if tag == "head" { "head".to_string() } else { "script".to_string() });
                continue;
            }
            if tag == "style" {
                if let Some(close) = html[index..].find("</style>") {
                    index += close + "</style>".len();
                }
                continue;
            }
            if tag.is_empty() {
                continue;
            }
            let attributes = parts.next().unwrap_or("");

            if let Some((hidden_tag, depth)) = hidden.as_mut() {
                if tag == *hidden_tag {
                    if closing {
                        *depth = depth.saturating_sub(1);
                        if *depth == 0 {
                            hidden = None;
                        }
                    } else {
                        *depth += 1;
                    }
                }
                continue;
            }
            if !closing {
                let style_attr = attr_value(attributes, "style").unwrap_or_default();
                let class_attr = attr_value(attributes, "class").unwrap_or_default();
                let inline_none = style_attr
                    .to_ascii_lowercase()
                    .split(';')
                    .any(|decl| decl.replace(' ', "") == "display:none");
                let hidden_attr = attributes
                    .split_whitespace()
                    .any(|part| part.eq_ignore_ascii_case("hidden"));
                let void = matches!(
                    tag.as_str(),
                    "img" | "br" | "hr" | "meta" | "link" | "input" | "source" | "col"
                );
                if inline_none || hidden_attr || rules_display_none(&tag, &class_attr, css_rules) {
                    if !void {
                        hidden = Some((tag.clone(), 1));
                    }
                    continue;
                }
            }

            if !closing && tag == "hr" {
                flush_block(&mut blocks, &mut text, current_style, preserve, base_after);
                let mut separator = current_style;
                separator.align = 1;
                separator.first_indent = 0.0;
                separator.space_before = 0.6;
                separator.space_after = 0.6;
                blocks.push(Block {
                    text: "———".to_string(),
                    style: separator,
                    table: None,
                    image: None,
                    preserve: false,
                    keep_blank_lines: false,
                });
                continue;
            }

            if in_table {
                match (closing, tag.as_str()) {
                    (false, "tr") => table_row.clear(),
                    (true, "tr") => {
                        if !table_row.is_empty() {
                            table_rows.push(std::mem::take(&mut table_row));
                        }
                    }
                    (false, "td") | (false, "th") => table_cell = Some(String::new()),
                    (true, "td") | (true, "th") => {
                        if let Some(cell) = table_cell.take() {
                            table_row.push(normalize_block_text(&cell, false));
                        }
                    }
                    (true, "table") => {
                        in_table = false;
                        table_cell = None;
                        if !table_row.is_empty() {
                            table_rows.push(std::mem::take(&mut table_row));
                        }
                        if !table_rows.is_empty() {
                            blocks.push(Block {
                                text: String::new(),
                                style: table_style(),
                                table: Some(std::mem::take(&mut table_rows)),
                                image: None,
                                preserve: false,
                                keep_blank_lines: false,
                            });
                        }
                    }
                    _ => {}
                }
                continue;
            }

            if !closing && tag == "ol" {
                ol_stack.push(0);
                continue;
            }
            if closing && tag == "ol" {
                ol_stack.pop();
                continue;
            }

            if !closing && tag == "table" {
                flush_block(&mut blocks, &mut text, current_style, preserve, base_after);
                in_table = true;
                table_rows.clear();
                table_row.clear();
                table_cell = None;
                continue;
            }

            if closing {
                if is_block_tag(&tag) {
                    flush_block(&mut blocks, &mut text, current_style, preserve, base_after);
                    if let Some(position) = tag_stack.iter().rposition(|value| *value == tag) {
                        tag_stack.truncate(position);
                    }
                    current_style = tag_stack
                        .iter()
                        .rev()
                        .find_map(|parent| default_style_for_tag(parent))
                        .unwrap_or_default();
                    // 书籍 class 规则（如 .calibre { margin: 0 }）特异性低于 WebView 的 #book p，
                // 因此 #book 规则放在 class 之后重新应用。
                match tag.as_str() {
                    "p" => {
                        current_style.align = 3;
                        current_style.first_indent = 2.0;
                        current_style.space_before = 0.0;
                        current_style.space_after = 0.55;
                        current_style.line_height = None;
                    }
                    "div" | "li" | "dd" | "dt" | "ol" | "ul" | "aside" | "section"
                    | "article" | "blockquote" => {
                        current_style.line_height = None;
                    }
                    "h1" | "h2" | "h3" | "h4" | "h5" | "h6" => {
                        current_style.align = 1;
                        current_style.bold = true;
                        current_style.first_indent = 0.0;
                        current_style.space_before = 0.9;
                        current_style.space_after = 0.85;
                        current_style.line_height = Some(1.3);
                        current_style.size = match tag.as_str() {
                            "h1" => 1.6,
                            "h2" => 1.4,
                            "h3" => 1.25,
                            _ => 1.1,
                        };
                    }
                    _ => {}
                }
                apply_stack_css(&mut current_style, &tag_stack, css_rules, &tag_rules);
                    preserve = false;
                } else if is_inline_tag(&tag) {
                    if let Some(marker) = inline_marker(&tag, true) {
                        text.push(marker);
                    }
                    match tag.as_str() {
                        "b" | "strong" => inline.depth[0] = inline.depth[0].saturating_sub(1),
                        "i" | "em" => inline.depth[1] = inline.depth[1].saturating_sub(1),
                        "code" | "tt" => inline.depth[2] = inline.depth[2].saturating_sub(1),
                        _ => {}
                    }
                } else if tag == "p" || tag == "li" || tag == "td" || tag == "th" {
                    if !text.ends_with('\n') && !text.is_empty() {
                        text.push('\n');
                    }
                }
                continue;
            }

            // 开始标签
            match tag.as_str() {
                "br" => {
                    text.push('\n');
                    continue;
                }
                "hr" => {
                    flush_block(&mut blocks, &mut text, current_style, preserve, base_after);
                    continue;
                }
                "img" => {
                    let classes = attr_value(attributes, "class").unwrap_or_default();
                    let inline_style = attr_value(attributes, "style").unwrap_or_default();
                    let inline_image = tag_stack.iter().any(|tag| tag == "sup")
                        || classes.contains("footnote")
                        || classes.contains("duokan-footnote")
                        || inline_style.contains("display:inline")
                        || inline_style.contains("display: inline");
                    if let Some(id) = attr_value(attributes, "data-salt-img") {
                        let mid_paragraph = !text.trim().is_empty()
                            && tag_stack.iter().any(|tag| {
                                matches!(tag.as_str(), "p" | "div" | "span" | "a" | "sup")
                            });
                        if inline_image || mid_paragraph {
                            // 内联小图：作为行内单元参与排版（WebView 脚注/图标行为），
                            // 用 \u0004 + 5 位 id + \u0005 的 token 交给绘制端。
                            text.push('\u{0004}');
                            text.push_str(&format!("{:05}", id.parse::<u32>().unwrap_or(0)));
                            text.push('\u{0005}');
                        } else {
                            flush_block(&mut blocks, &mut text, current_style, preserve, base_after);
                            let mut image_style = current_style;
                            // WebView: #book img { margin: 0.8em auto; display: block }
                            image_style.space_before = 0.8;
                            image_style.space_after = 0.8;
                            image_style.first_indent = 0.0;
                            image_style.align = 1;
                            blocks.push(Block {
                                text: String::new(),
                                style: image_style,
                                table: None,
                                image: id.parse::<u32>().ok(),
                                preserve: false,
                                keep_blank_lines: false,
                            });
                        }
                    } else {
                        text.push_str(&image_placeholder());
                    }
                    continue;
                }
                _ => {}
            }

            if is_block_tag(&tag) {
                flush_block(&mut blocks, &mut text, current_style, preserve, base_after);
                if tag == "li" {
                    if let Some(counter) = ol_stack.last_mut() {
                        *counter += 1;
                        text.push_str(&format!("{}. ", counter));
                    }
                }
                tag_stack.push(tag.clone());
                let class_attr = attr_value(attributes, "class");
                let mut style = default_style_for_tag(&tag).unwrap_or_default();
                current_style = style;
                apply_stack_css(&mut current_style, &tag_stack, css_rules, &tag_rules);
                if let Some(classes) = &class_attr {
                    for class_name in classes.split_whitespace() {
                        apply_class_css(&mut current_style, &tag, class_name, css_rules, &class_rules);
                    }
                }
                if let Some(style_attr) = attr_value(attributes, "style") {
                    let declarations: Vec<(String, String)> = style_attr
                        .split(';')
                        .filter_map(|declaration| {
                            let (name, value) = declaration.split_once(':')?;
                            Some((name.trim().to_lowercase(), value.trim().to_string()))
                        })
                        .collect();
                    apply_declarations(&mut current_style, &declarations);
                }
                if let Some(classes) = &class_attr {
                    for class_name in classes.split_whitespace() {
                        apply_known_class(&mut current_style, class_name);
                        apply_class_css(&mut current_style, &tag, class_name, css_rules, &class_rules);
                    }
                }
                preserve = tag == "pre";
                if tag == "p" && current_style.first_indent == 2.0 {
                    // 章节标题段落（class .chapter-heading 等）交给 CSS 覆盖。
                }
            } else if is_inline_tag(&tag) {
                if let Some(marker) = inline_marker(&tag, false) {
                    text.push(marker);
                }
                match tag.as_str() {
                    "b" | "strong" => inline.depth[0] += 1,
                    "i" | "em" => inline.depth[1] += 1,
                    "code" | "tt" => inline.depth[2] += 1,
                    _ => {}
                }
            }
            continue;
        }

        // 文本
        let next = html[index..].find('<').map(|offset| index + offset).unwrap_or(len);
        let chunk = &html[index..next];
        index = next;
        if chunk.is_empty() {
            continue;
        }
        let decoded = decode_entities(chunk);
        if hidden.is_some() {
            continue;
        }
        if in_table {
            if let Some(cell) = table_cell.as_mut() {
                cell.push_str(&decoded);
            }
            continue;
        }
        if preserve {
            text.push_str(&decoded);
        } else {
            let leading_space = decoded.chars().next().is_some_and(|ch| ch.is_whitespace());
            let trailing_space = decoded.chars().last().is_some_and(|ch| ch.is_whitespace());
            let collapsed = normalize_block_text(&decoded, false);
            if !collapsed.is_empty() {
                // 只有源码本身在边界处有空白时才补一个空格；<b>/<span> 等行内标签不产生空格。
                if (leading_space || pending_space) && !text.is_empty() && !text.ends_with(['\n', ' ']) {
                    text.push(' ');
                }
                text.push_str(&collapsed);
            }
            pending_space = trailing_space;
        }
    }
    flush_block(&mut blocks, &mut text, current_style, preserve, base_after);

    for block in blocks.iter_mut() {
        if !block.preserve {
            block.text = block.text.trim().to_string();
        }
    }
    blocks.retain(|block| {
        !block.text.is_empty() || block.style.bullet || block.table.is_some() || block.image.is_some()
    });
    blocks
}

fn rule_specificity(rule: &CssRule) -> u32 {
    let mut score = 0;
    if rule.tag.is_some() {
        score += 1;
    }
    if rule.class_name.is_some() {
        score += 10;
    }
    score
}

fn apply_stack_css(
    style: &mut Style,
    stack: &[String],
    rules: &[CssRule],
    index: &HashMap<&str, Vec<usize>>,
) {
    // 外层标签的规则也要生效：按「特异性升序 + 源序」应用，后面的高特异性规则覆盖前者。
    let mut matched: Vec<usize> = Vec::new();
    for tag in stack {
        if let Some(rule_ids) = index.get(tag.as_str()) {
            matched.extend(rule_ids.iter().copied());
        }
    }
    matched.sort_by_key(|rule_index| {
        (rules.get(*rule_index).map(rule_specificity).unwrap_or(0), *rule_index)
    });
    for rule_index in matched {
        if let Some(rule) = rules.get(rule_index) {
            apply_declarations(style, &rule.declarations);
        }
    }
}

fn apply_class_css(
    style: &mut Style,
    tag: &str,
    class_name: &str,
    rules: &[CssRule],
    index: &HashMap<&str, Vec<usize>>,
) {
    let Some(rule_ids) = index.get(class_name) else { return };
    let mut matched: Vec<usize> = rule_ids
        .iter()
        .copied()
        .filter(|rule_index| {
            match rules.get(*rule_index).and_then(|rule| rule.tag.as_deref()) {
                None => true,
                Some(rule_tag) => rule_tag == tag,
            }
        })
        .collect();
    matched.sort_by_key(|rule_index| {
        (rules.get(*rule_index).map(rule_specificity).unwrap_or(0), *rule_index)
    });
    for rule_index in matched {
        if let Some(rule) = rules.get(rule_index) {
            apply_declarations(style, &rule.declarations);
        }
    }
}

/// CSS 规则里是否对该元素设置了 display:none（tag / .class 选择器）。
fn rules_display_none(tag: &str, class_attr: &str, rules: &[CssRule]) -> bool {
    let classes: Vec<&str> = class_attr.split_whitespace().collect();
    for rule in rules {
        if let Some(rule_tag) = &rule.tag {
            if rule_tag != tag {
                continue;
            }
        }
        if let Some(rule_class) = &rule.class_name {
            if !classes.contains(&rule_class.as_str()) {
                continue;
            }
        }
        for (name, value) in &rule.declarations {
            if name == "display" && value.eq_ignore_ascii_case("none") {
                return true;
            }
        }
    }
    false
}

fn attr_value(attributes: &str, name: &str) -> Option<String> {
    // 单遍扫描属性名/值：不能对整串 to_lowercase 后再按字节偏移切（Unicode 大小写变换会改长度）。
    let bytes = attributes.as_bytes();
    let len = bytes.len();
    let mut index = 0usize;
    while index < len {
        while index < len && bytes[index].is_ascii_whitespace() {
            index += 1;
        }
        if index >= len {
            break;
        }
        let name_start = index;
        while index < len && !bytes[index].is_ascii_whitespace() && bytes[index] != b'=' {
            index += 1;
        }
        let attr_name = &attributes[name_start..index];
        while index < len && bytes[index].is_ascii_whitespace() {
            index += 1;
        }
        if index >= len || bytes[index] != b'=' {
            continue;
        }
        index += 1;
        while index < len && bytes[index].is_ascii_whitespace() {
            index += 1;
        }
        let value: &str;
        if index < len && (bytes[index] == b'"' || bytes[index] == b'\'') {
            let quote = bytes[index];
            index += 1;
            let value_start = index;
            while index < len && bytes[index] != quote {
                index += 1;
            }
            value = &attributes[value_start..index];
            if index < len {
                index += 1;
            }
        } else {
            let value_start = index;
            while index < len && !bytes[index].is_ascii_whitespace() {
                index += 1;
            }
            value = &attributes[value_start..index];
        }
        if attr_name.eq_ignore_ascii_case(name) {
            return Some(value.to_string());
        }
    }
    None
}

fn flush_block(blocks: &mut Vec<Block>, text: &mut String, style: Style, preserve: bool, base_after: &mut f32) {
    let normalized = text.trim_matches('\n');
    *base_after = base_after.min(style.space_after);
    if normalized.trim().is_empty() {
        text.clear();
        return;
    }
    let stored = if preserve {
        // <pre>: 保留首行缩进、空行与连续空格。
        normalized.to_string()
    } else {
        normalized.trim().to_string()
    };
    let keep_blank_lines = !preserve && stored.contains("\n\n");
    blocks.push(Block {
        text: stored,
        style,
        table: None,
        image: None,
        preserve,
        keep_blank_lines,
    });
    text.clear();
}

#[derive(Clone, Copy)]
struct TableCellMeta {
    col: u8,
    cols: u8,
    row_start: bool,
    last_row: bool,
    row_end: bool,
}

struct RenderLine {
    text: String,
    style: Style,
    line_height_factor: f32,
    gap_em: f32,
    table: Option<TableCellMeta>,
    image: Option<u32>,
}

fn is_no_line_start(ch: char) -> bool {
    matches!(
        ch,
        '，' | '。' | '、' | '；' | '：' | '？' | '！' | '）' | '】' | '》' | '」' | '』'
            | '”' | '’' | '…' | '—' | '·' | ',' | '.' | ';' | ':' | '?' | '!' | ')' | ']' | '}'
    )
}

/// 字宽表：BMP 用数组直接索引，非 BMP 回退 HashMap（避免逐字 SipHash）。
#[derive(Default)]
struct WidthTable {
    bmp: Vec<f32>,
    other: HashMap<u32, f32>,
}

impl WidthTable {
    fn from_map(map: &HashMap<u32, f32>) -> Self {
        let max_bmp = map.keys().copied().filter(|cp| *cp < 0x10000).max().unwrap_or(0) as usize;
        let mut bmp = vec![0f32; max_bmp + 1];
        let mut other = HashMap::new();
        for (cp, width) in map {
            if (*cp as usize) < bmp.len() {
                bmp[*cp as usize] = *width;
            } else if *cp >= 0x10000 {
                other.insert(*cp, *width);
            }
        }
        Self { bmp, other }
    }

    #[inline]
    fn get(&self, cp: u32) -> Option<f32> {
        if (cp as usize) < self.bmp.len() {
            let width = self.bmp[cp as usize];
            if width > 0.0 {
                return Some(width);
            }
            None
        } else {
            self.other.get(&cp).copied().filter(|width| *width > 0.0)
        }
    }
}

fn char_width(ch: char, font_size: f32, scale: f32, advances: &WidthTable) -> f32 {
    // 行内样式标记（\u0006..\u000F）不占排版宽度。
    if ('\u{0006}'..='\u{0013}').contains(&ch) {
        return 0.0;
    }
    if let Some(w) = advances.get(ch as u32) {
        if w > 0.0 {
            return w * scale;
        }
    }
    match UnicodeWidthChar::width(ch).unwrap_or(1) {
        2 => font_size,
        _ if ch == ' ' => font_size * 0.28,
        _ => font_size * 0.55,
    }
}

/// 等宽字体的近似字宽：代码块的排版宽度与绘制用的 monospace 字体一致，
/// 否则用正文比例字体估算会偏窄，长代码行会被裁切而不是折行。
fn mono_char_width(ch: char, size_px: f32) -> f32 {
    if ('\u{0006}'..='\u{0013}').contains(&ch) {
        return 0.0;
    }
    match UnicodeWidthChar::width(ch).unwrap_or(1) {
        2 => size_px,
        _ => size_px * 0.6,
    }
}

/// 直接写入输出缓冲，避免每行一次 String 分配。
fn write_style(out: &mut String, style: &Style, line_height_factor: f32, gap_em: f32) {
    let size = (style.size * 10.0).round().clamp(6.0, 40.0) as u32;
    let line_height = (line_height_factor * 10.0).round().clamp(9.0, 40.0) as u32;
    let mut flags = 0u32;
    if style.bold {
        flags |= 1;
    }
    if style.italic {
        flags |= 2;
    }
    if style.mono {
        flags |= 4;
    }
    let left_indent = (style.left_indent * 10.0).round().clamp(0.0, 90.0) as u32;
    let gap = (gap_em * 10.0).round().clamp(0.0, 99.0) as u32;
    use std::fmt::Write as _;
    let _ = write!(
        out,
        "{:02}{:02}{}{:02}{:02}{:02}",
        size,
        line_height,
        flags,
        style.align,
        left_indent,
        gap,
    );
}

fn wrap_simple(
    text: &str,
    width: f32,
    size_px: f32,
    scale: f32,
    advances: &WidthTable,
    mono: bool,
) -> Vec<String> {
    if text.trim().is_empty() {
        return vec![String::new()];
    }
    let mut lines: Vec<String> = Vec::new();
    let mut line = String::new();
    let mut line_width = 0.0f32;
    for (_, grapheme) in text.grapheme_indices(true) {
        let w: f32 = grapheme
            .chars()
            .map(|ch| {
                if mono {
                    mono_char_width(ch, size_px)
                } else {
                    char_width(ch, size_px, scale, advances)
                }
            })
            .sum();
        if !line.is_empty() && line_width + w > width {
            lines.push(std::mem::take(&mut line));
            line_width = 0.0;
        }
        line.push_str(grapheme);
        line_width += w;
    }
    lines.push(line);
    lines
}

fn image_box_height(width: f32, page_height: f32, aspect: f32, max_height_ratio: f32) -> f32 {
    let aspect = aspect.clamp(0.05, 20.0);
    let ratio = max_height_ratio.clamp(0.5, 20.0);
    let full = width / aspect;
    if full > page_height * ratio {
        page_height * ratio
    } else {
        full
    }
}

fn layout_blocks(
    blocks: &[Block],
    chapter_starts: &[usize],
    width: f32,
    height: f32,
    font_size: f32,
    line_height_base: f32,
    advances: &HashMap<u32, f32>,
    image_aspects: &[f32],
    image_base_width: f32,
    image_max_height_ratio: f32,
) -> Vec<Vec<RenderLine>> {
    let widths = WidthTable::from_map(advances);
    let advances = &widths;
    let mut pages: Vec<Vec<RenderLine>> = Vec::new();
    let mut page_lines: Vec<RenderLine> = Vec::new();
    let mut y = 0.0f32;
    let mut pending_after = 0.0f32;

    let mut new_page = |pages: &mut Vec<Vec<RenderLine>>, page_lines: &mut Vec<RenderLine>| {
        if !page_lines.is_empty() {
            pages.push(std::mem::take(page_lines));
        }
    };

    for (block_index, block) in blocks.iter().enumerate() {
        // 与 WebView 阅读器一致：每个章节都从新的一页开始。
        if chapter_starts.binary_search(&block_index).is_ok() {
            if !page_lines.is_empty() {
                pages.push(std::mem::take(&mut page_lines));
            }
            y = 0.0;
            pending_after = 0.0;
        }
        if let Some(image_id) = block.image {
            let aspect = image_aspects
                .get(image_id as usize)
                .copied()
                .unwrap_or(1.0)
                .max(0.05);
            let image_height = image_box_height(image_base_width, height, aspect, image_max_height_ratio);
            let gap = pending_after.max(block.style.space_before * font_size);
            pending_after = 0.0;
            let mut gap_em = 0.0f32;
            if !page_lines.is_empty() || y > 0.0 {
                if y + gap + image_height > height {
                    if !page_lines.is_empty() {
                        pages.push(std::mem::take(&mut page_lines));
                    }
                    y = 0.0;
                } else {
                    y += gap;
                    gap_em = gap / font_size;
                }
            }
            page_lines.push(RenderLine {
                text: String::new(),
                style: block.style,
                line_height_factor: 1.0,
                gap_em,
                table: None,
                image: Some(image_id),
            });
            y += image_height;
            pending_after = block.style.space_after * font_size;
            if y > height {
                if !page_lines.is_empty() {
                    pages.push(std::mem::take(&mut page_lines));
                }
                y = 0.0;
            }
            continue;
        }
        if let Some(rows) = block.table.clone() {
            let style = block.style;
            let size_px = font_size * style.size;
            let line_height_factor = style.line_height.unwrap_or(line_height_base);
            let line_height = size_px * line_height_factor;
            let cols = rows.iter().map(|row| row.len()).max().unwrap_or(0);
            if cols == 0 {
                continue;
            }
            let pad = (size_px * 0.35).max(1.0);
            let col_width = width / cols as f32;
            let cell_width = (col_width - pad * 2.0).max(8.0);
            let gap = pending_after.max(style.space_before * font_size);
            pending_after = 0.0;
            let mut table_gap_em = 0.0f32;
            if !page_lines.is_empty() || y > 0.0 {
                if y + gap > height {
                    if !page_lines.is_empty() {
                        pages.push(std::mem::take(&mut page_lines));
                    }
                    y = 0.0;
                } else {
                    y += gap;
                    table_gap_em = gap / font_size;
                }
            }
            for (row_index, row) in rows.iter().enumerate() {
                let mut cell_lines: Vec<Vec<String>> = Vec::with_capacity(cols);
                for column in 0..cols {
                    let text = row.get(column).cloned().unwrap_or_default();
                    cell_lines.push(wrap_simple(&text, cell_width, size_px, style.size, advances, style.mono));
                }
                let max_lines = cell_lines
                    .iter()
                    .map(|lines| lines.len())
                    .max()
                    .unwrap_or(1)
                    .max(1);
                let row_height = line_height * max_lines as f32;
                if y + row_height > height && y > 0.0 {
                    if !page_lines.is_empty() {
                        pages.push(std::mem::take(&mut page_lines));
                    }
                    y = 0.0;
                }
                for line_index in 0..max_lines {
                    // 单个表格行高于一页时也要按视觉行拆页，不能画到页外。
                    if y + line_height > height && !page_lines.is_empty() {
                        pages.push(std::mem::take(&mut page_lines));
                        y = 0.0;
                        table_gap_em = 0.0;
                    }
                    for column in 0..cols {
                        let text = cell_lines
                            .get(column)
                            .and_then(|lines| lines.get(line_index))
                            .cloned()
                            .unwrap_or_default();
                        page_lines.push(RenderLine {
                            text,
                            style,
                            line_height_factor,
                            image: None,
                            gap_em: if row_index == 0 && line_index == 0 && column == 0 {
                                table_gap_em
                            } else {
                                0.0
                            },
                            table: Some(TableCellMeta {
                                col: column as u8,
                                cols: cols as u8,
                                row_start: line_index == 0,
                                last_row: row_index + 1 == rows.len(),
                                row_end: line_index + 1 == max_lines,
                            }),
                        });
                    }
                    y += line_height;
                }
                if y > height {
                    if !page_lines.is_empty() {
                        pages.push(std::mem::take(&mut page_lines));
                    }
                    y = 0.0;
                }
            }
            pending_after = style.space_after * font_size;
            continue;
        }
        let style = block.style;
        let size_px = font_size * style.size;
        let line_height_factor = style.line_height.unwrap_or(line_height_base);
        let line_height = size_px * line_height_factor;
        let left_indent_px = style.left_indent * font_size;
        let first_indent_em = style.first_indent;
        let content_width = (width - left_indent_px).max(32.0);

        // WebView/CSS margin 折叠：相邻块间距取两者较大值，而不是相加。
        let gap = pending_after.max(style.space_before * font_size);
        pending_after = 0.0;
        let mut line_gap_em = gap / font_size;
        if !page_lines.is_empty() || y > 0.0 {
            if y + gap > height {
                new_page(&mut pages, &mut page_lines);
                y = 0.0;
                line_gap_em = 0.0;
            } else {
                y += gap;
            }
        } else {
            line_gap_em = 0.0;
        }

        let bullet_prefix = if style.bullet { "• " } else { "" };
        let graphemes: Vec<(usize, &str)> = block.text.grapheme_indices(true).collect();
        let break_positions: std::collections::HashSet<usize> =
            linebreaks(&block.text).map(|(index, _)| index).collect();

        let mut line = String::new();
        let mut line_width = 0.0f32;
        let mut start_indent = first_indent_em;
        let mut has_content = false;
        let mut index = 0usize;
        let mut first_line = true;
        let mut line_start_byte = 0usize;
        let mut inline_mono = false;

        while index < graphemes.len() {
            let (byte_start, grapheme) = graphemes[index];
            if grapheme == "\u{0004}" {
                // 行内图片 token：\u0004 + 5 位 id + \u0005，高度约 1.15 行高。
                let mut token = String::from("\u{0004}");
                let mut cursor = index + 1;
                while cursor < graphemes.len() && graphemes[cursor].1 != "\u{0005}" {
                    token.push_str(graphemes[cursor].1);
                    cursor += 1;
                }
                if cursor < graphemes.len() {
                    token.push('\u{0005}');
                    cursor += 1;
                }
                let id: usize = token
                    .trim_start_matches('\u{0004}')
                    .trim_end_matches('\u{0005}')
                    .parse()
                    .unwrap_or(0);
                let inline_height = size_px * 1.15;
                let inline_width = (inline_height
                    * image_aspects.get(id).copied().unwrap_or(1.0).clamp(0.2, 8.0))
                .min(content_width * 0.9);
                let indent_px = if first_line { start_indent * size_px } else { 0.0 };
                if has_content && line_width + inline_width > content_width - indent_px {
                    push_line(
                        &mut pages,
                        &mut page_lines,
                        &mut y,
                        &mut line,
                        &mut line_width,
                        style,
                        line_height,
                        line_height_factor,
                        content_width,
                        start_indent,
                        bullet_prefix,
                        first_line,
                        height,
                        true,
                        if first_line { line_gap_em } else { 0.0 },
                        block.preserve,
                        block.preserve || block.keep_blank_lines,
                    );
                    first_line = false;
                    start_indent = 0.0;
                    has_content = false;
                }
                line.push_str(&token);
                line_width += inline_width;
                has_content = true;
                index = cursor;
                continue;
            }
            if grapheme == "\n" {
                push_line(
                    &mut pages,
                    &mut page_lines,
                    &mut y,
                    &mut line,
                    &mut line_width,
                    style,
                    line_height,
                    line_height_factor,
                    content_width,
                    start_indent,
                    bullet_prefix,
                    first_line,
                    height,
                    true,
                    if first_line { line_gap_em } else { 0.0 },
                    block.preserve,
                    block.preserve || block.keep_blank_lines,
                );
                index += 1;
                first_line = false;
                start_indent = 0.0;
                has_content = false;
                line_start_byte = byte_start + 1;
                continue;
            }
            // 行内代码标记 \u000E / \u000F：与绘制一致按 0.92em 等宽字宽估算。
            if grapheme == "\u{000E}" {
                inline_mono = true;
            } else if grapheme == "\u{000F}" {
                inline_mono = false;
            }
            let mut width = grapheme
                .chars()
                .map(|ch| {
                    if style.mono {
                        mono_char_width(ch, size_px)
                    } else if inline_mono {
                        mono_char_width(ch, size_px) * 0.92
                    } else {
                        char_width(ch, size_px, style.size, advances)
                    }
                })
                .sum::<f32>();
            // 行内代码的左右 padding（0.15em × 0.92）也要计入行宽，否则胶囊会压出页边距。
            if grapheme == "\u{000E}" {
                width += size_px * 0.92 * 0.3;
            }
            let indent_px = if first_line { start_indent * size_px } else { 0.0 };
            if has_content && line_width + width > content_width - indent_px {
                let mut break_at: Option<usize> = None;
                for (position, _) in graphemes[..index].iter().rev().take(64) {
                    if *position <= byte_start.saturating_sub(1) && break_positions.contains(position) {
                        break_at = Some(*position);
                        break;
                    }
                }
                let min_break = (line_start_byte + 1).min(block.text.len());
                let mut break_byte = break_at
                    .filter(|position| *position > line_start_byte)
                    .unwrap_or(byte_start)
                    .max(min_break)
                    .min(block.text.len());
                // 行首禁则：收尾标点不能出现在下一行行首（悬挂到本行）。
                let mut cursor = graphemes.iter().position(|(position, _)| *position == break_byte);
                while let Some(at) = cursor {
                    let Some((_, grapheme)) = graphemes.get(at) else { break };
                    if !grapheme.chars().all(is_no_line_start) {
                        break;
                    }
                    match graphemes.get(at + 1) {
                        Some((next_position, _)) => {
                            break_byte = *next_position;
                            cursor = Some(at + 1);
                        }
                        None => {
                            break_byte = block.text.len();
                            break;
                        }
                    }
                }
                let take = &block.text[line_start_byte..break_byte];
                line.clear();
                line.push_str(take.trim_end());
                line_width = 0.0;
                push_line(
                    &mut pages,
                    &mut page_lines,
                    &mut y,
                    &mut line,
                    &mut line_width,
                    style,
                    line_height,
                    line_height_factor,
                    content_width,
                    start_indent,
                    bullet_prefix,
                    first_line,
                    height,
                    false,
                    if first_line { line_gap_em } else { 0.0 },
                    block.preserve,
                    block.preserve || block.keep_blank_lines,
                );
                let next_start = graphemes
                    .iter()
                    .position(|(position, _)| *position >= break_byte)
                    .unwrap_or(graphemes.len());
                index = next_start;
                let mut skip = index;
                while skip < graphemes.len() {
                    let (_, grapheme) = graphemes[skip];
                    if grapheme == " " {
                        skip += 1;
                    } else {
                        break;
                    }
                }
                index = skip;
                line_start_byte = graphemes
                    .get(index)
                    .map(|(position, _)| *position)
                    .unwrap_or(block.text.len());
                first_line = false;
                start_indent = 0.0;
                has_content = false;
                continue;
            }
            line.push_str(grapheme);
            line_width += width;
            has_content = true;
            index += 1;
            let _ = byte_start;
        }

        if has_content {
            push_line(
                &mut pages,
                &mut page_lines,
                &mut y,
                &mut line,
                &mut line_width,
                style,
                line_height,
                line_height_factor,
                content_width,
                start_indent,
                bullet_prefix,
                first_line,
                height,
                true,
                if first_line { line_gap_em } else { 0.0 },
                block.preserve,
                block.preserve || block.keep_blank_lines,
            );
        }
        pending_after = style.space_after * font_size;
    }

    if !page_lines.is_empty() {
        pages.push(page_lines);
    }
    if pages.is_empty() {
        pages.push(Vec::new());
    }
    pages
}

#[allow(clippy::too_many_arguments)]
fn push_line(
    pages: &mut Vec<Vec<RenderLine>>,
    page_lines: &mut Vec<RenderLine>,
    y: &mut f32,
    line: &mut String,
    line_width: &mut f32,
    style: Style,
    line_height: f32,
    line_height_factor: f32,
    content_width: f32,
    start_indent_em: f32,
    bullet_prefix: &str,
    first_line: bool,
    height: f32,
    is_last_line: bool,
    gap_em: f32,
    preserve: bool,
    keep_empty: bool,
) {
    if !keep_empty && line.trim().is_empty() {
        return;
    }
    let mut gap_em = gap_em;
    if *y + line_height > height {
        if !page_lines.is_empty() {
            pages.push(std::mem::take(page_lines));
        }
        *y = 0.0;
        // 新页顶部不需要再重复块的段前距。
        gap_em = 0.0;
    }
    let mut text = String::new();
    if preserve {
        text.push_str(line);
    } else {
        if style.bullet && first_line {
            text.push_str(bullet_prefix);
        }
        let indent_chars = start_indent_em.round().clamp(0.0, 8.0) as usize;
        for _ in 0..indent_chars {
            text.push('　');
        }
        text.push_str(line.trim());
    }
    *y += line_height;
    let mut line_style = style;
    if line_style.align == 3 && is_last_line {
        line_style.align = 0; // 段落最后一行不拉伸
    }
    page_lines.push(RenderLine {
        text,
        style: line_style,
        line_height_factor,
        gap_em,
        table: None,
        image: None,
    });
    line.clear();
    *line_width = 0.0;
    let _ = content_width;
}

#[no_mangle]
pub extern "system" fn Java_moe_gbxin_saltreader_data_NativeTextLayout_paginateHtml(
    mut env: JNIEnv,
    _this: JObject,
    chapters: JObjectArray,
    width: f32,
    height: f32,
    font_size: f32,
    line_height_base: f32,
    code_points: JIntArray,
    advances: JFloatArray,
    image_aspects: JFloatArray,
    image_full_width: jboolean,
    full_page_width: f32,
    image_max_height_ratio: f32,
    image_placeholder: JString,
) -> jstring {
    let mut advance_map: HashMap<u32, f32> = HashMap::new();
    if let (Ok(cp_len), Ok(adv_len)) = (
        env.get_array_length(&code_points),
        env.get_array_length(&advances),
    ) {
        let n = cp_len.min(adv_len) as usize;
        if n > 0 {
            let mut cps = vec![0i32; n];
            let mut advs = vec![0f32; n];
            if env.get_int_array_region(&code_points, 0, &mut cps).is_ok()
                && env.get_float_array_region(&advances, 0, &mut advs).is_ok()
            {
                for i in 0..n {
                    advance_map.insert(cps[i] as u32, advs[i]);
                }
            }
        }
    }

    let mut aspect_list: Vec<f32> = Vec::new();
    if let Ok(length) = env.get_array_length(&image_aspects) {
        if length > 0 {
            let mut values = vec![0f32; length as usize];
            if env.get_float_array_region(&image_aspects, 0, &mut values).is_ok() {
                aspect_list = values;
            }
        }
    }

    if let Ok(text) = env.get_string(&image_placeholder) {
        let placeholder: String = text.into();
        IMAGE_PLACEHOLDER.with(|value| *value.borrow_mut() = placeholder);
    }
    let width = width.max(32.0);
    let height = height.max(32.0);
    let font_size = font_size.max(1.0);
    let line_height_base = line_height_base.clamp(0.9, 3.5);

    let mut blocks: Vec<Block> = Vec::new();
    let mut chapter_starts: Vec<usize> = Vec::new();
    let mut css_rules: Vec<CssRule> = Vec::new();
    if let Ok(count) = env.get_array_length(&chapters) {
        for i in 0..count {
            if i > 0 {
                chapter_starts.push(blocks.len());
            }
            if let Ok(element) = env.get_object_array_element(&chapters, i) {
                if element.is_null() {
                    continue;
                }
                let jstring: JString = JString::from(element);
                let html: String = match env.get_string(&jstring) {
                    Ok(value) => value.into(),
                    Err(_) => continue,
                };
                // 章节自带 <style>：先抽取一次 CSS。
                let mut cursor = 0usize;
                while let Some(open) = html[cursor..].find("<style") {
                    let open = cursor + open;
                    let Some(gt) = html[open..].find('>') else { break };
                    let body_start = open + gt + 1;
                    let Some(close) = html[body_start..].find("</style>") else { break };
                    css_rules.extend(parse_css(&html[body_start..body_start + close]));
                    cursor = body_start + close + 7;
                }
                let mut base_after = 1.0f32;
                blocks.extend(parse_html_blocks(&html, &css_rules, &mut base_after));
            }
        }
    }

    let image_base_width = if image_full_width != 0 {
        full_page_width.max(width)
    } else {
        width
    };
    let pages = layout_blocks(
        &blocks,
        &chapter_starts,
        width,
        height,
        font_size,
        line_height_base,
        &advance_map,
        &aspect_list,
        image_base_width,
        image_max_height_ratio,
    );

    let estimated = pages
        .iter()
        .flat_map(|page| page.iter())
        .map(|line| line.text.len() + 32)
        .sum::<usize>()
        .max(64);
    let mut out = String::with_capacity(estimated);
    use std::fmt::Write as _;
    for (page_index, page) in pages.iter().enumerate() {
        if page_index > 0 {
            out.push('\u{0}');
        }
        for (line_index, line) in page.iter().enumerate() {
            if line_index > 0 {
                out.push('\n');
            }
            if let Some(image_id) = line.image {
                out.push('\u{3}');
                let _ = write!(
                    out,
                    "{:05}{:02}",
                    image_id,
                    (line.gap_em * 10.0).round().clamp(0.0, 99.0) as u32,
                );
                continue;
            }
            if let Some(meta) = line.table {
                out.push('\u{2}');
                let mut flags = 0u32;
                if meta.row_start {
                    flags |= 1;
                }
                if meta.last_row {
                    flags |= 2;
                }
                if meta.row_end {
                    flags |= 4;
                }
                let _ = write!(out, "{:02}{:02}{}", meta.col, meta.cols, flags);
            }
            out.push('\u{1}');
            write_style(&mut out, &line.style, line.line_height_factor, line.gap_em);
            out.push_str(&line.text);
        }
    }

    match env.new_string(out) {
        Ok(value) => value.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_table_into_block() {
        let html = "<p>before</p><table><tr><td>a</td><td>b</td></tr><tr><td>c</td><td>d</td></tr></table><p>after</p>";
        let mut after = 0.0;
        let blocks = parse_html_blocks(html, &[], &mut after);
        let tables: Vec<_> = blocks.iter().filter(|b| b.table.is_some()).collect();
        assert_eq!(tables.len(), 1, "blocks: {:?}", blocks.iter().map(|b| (&b.text, &b.table)).collect::<Vec<_>>());
        assert_eq!(tables[0].table.as_ref().unwrap(), &vec![vec!["a".to_string(), "b".to_string()], vec!["c".to_string(), "d".to_string()]]);
    }
}

#[cfg(test)]
mod gap_tests {
    use super::*;



    #[test]
    fn chapter_pages_preserve_all_paragraphs() {
        let html = "<p>花城很快就回来了，右手还拿着Cheerio的可乐。然后，她在川崎同学面前拉起拉环，噗咻一声打开，接着大口喝起可乐。花城突如其来的举止，令教室的气氛瞬间冻结，川崎同学惊讶得说不出话来。只见花城将可乐喝了个底朝天，然后噗哈一声，樱唇离开可乐罐口。</p><p>「嗯，多谢招待。」</p><p>花城将可乐空罐放在川崎同学的桌上，随后便若无其事地回到座位，开始读起书。下一刻，川崎同学才宛如大梦初醒，气呼呼地大喊：「喂，你这是做什么啊？为什么擅自喝掉我的饮料！」她站起身，朝花城的座位走去。然而好巧不巧地，老师刚好在这时走进教室，川崎同学只能咂舌一声，恶狠狠地瞪着花城。</p><p>「花城好强，竟然把川崎的可乐喝光了。」「我也想像那样做一次。」「话说，她喝可乐的动作还真豪迈啊。」</p><p>听见同学们的对话，川崎同学涨红了脸，宛如Cheerio可乐的罐子。</p><p>面对班上的女王川崎同学，花城竟敢做出那样的举动，她究竟是什么人？我这么想着，心里同时预想着这样的未来——「啊啊，就算到毕业，我大概也不会跟她说上一句话。毕业后她很快就会连我的名字都忘记吧」。像她那样不会随波逐流的女孩，应该不会对我这般不起眼的人感兴趣。况且，我自己也不太想和她扯上关系。因为我和她是活在不同世界的人。</p><p>即使有转学生转入，我们还是一如往常地上课。上完第五和第六节课后，今天的课程终于全部结束。于是我拿起书包，从座位站起。</p><p>「塔野。」</p><p>回头一看，叫住我的人是被花城愚弄、现在仍满肚子气的川崎同学。</p><p>「什么事？」</p><p>「去福利社帮我买冰淇淋回来。」</p><p>冰淇淋，其正式名称是前端冰淇淋</p><p>。就如同它的名字，那是一款前端呈圆顶造型的冰品。不，这种事根本无关紧要。</p>";
        let mut after = 0.0;
        let blocks = parse_html_blocks(html, &[], &mut after);
        let advances: HashMap<u32, f32> = HashMap::new();
        let pages = layout_blocks(&blocks, &[], 900.0, 1634.0, 38.0, 1.8, &advances, &[], 1080.0, 1.0);
        for (pi, page) in pages.iter().enumerate() {
            let first = page.first().map(|l| l.text.clone()).unwrap_or_default();
            let last = page.last().map(|l| l.text.clone()).unwrap_or_default();
            println!("page{} lines={} first={:?} last={:?}", pi, page.len(), first, last);
        }
        let laid: String = pages
            .iter()
            .flat_map(|page| page.iter())
            .map(|line| line.text.trim().trim_start_matches('　').to_string())
            .collect();
        let expected: String = blocks.iter().map(|b| b.text.clone()).collect::<Vec<_>>().join("");
        let norm = |value: &str| -> String {
            value.chars().filter(|ch| !ch.is_whitespace()).collect()
        };
        assert_eq!(norm(&laid), norm(&expected), "pages={} laid_len={} expected_len={}", pages.len(), norm(&laid).len(), norm(&expected).len());
    }

    #[test]
    fn decode_entities_keeps_following_char() {
        assert_eq!(decode_entities("Tom &amp; Jerry"), "Tom & Jerry");
        assert_eq!(decode_entities("a&amp;b"), "a&b");
        assert_eq!(decode_entities("&#x4E2D;文"), "中文");
        assert_eq!(decode_entities("&hellip;」下一句"), "…」下一句");
        assert_eq!(decode_entities("&unknown;x"), "&unknown;x");
    }

    #[test]
    fn pre_preserves_indent_and_blank_lines() {
        let mut after = 0.0;
        let blocks = parse_html_blocks("<pre>line1\n  indented\n\nlast</pre>", &[], &mut after);
        let text: String = blocks.iter().map(|b| b.text.clone()).collect();
        assert!(text.contains("  indented"), "text={text:?}");
        assert!(text.contains("\n\n"), "text={text:?}");
        let advances: HashMap<u32, f32> = HashMap::new();
        let pages = layout_blocks(&blocks, &[], 600.0, 1634.0, 38.0, 1.8, &advances, &[], 600.0, 1.0);
        let lines: Vec<String> = pages.iter().flatten().map(|line| line.text.clone()).collect();
        assert!(lines.iter().any(|line| line.is_empty() || line.trim().is_empty()), "lines={lines:?}");
        assert!(lines.iter().any(|line| line.starts_with("  indented")), "lines={lines:?}");
    }

    #[test]
    fn display_none_subtree_is_hidden() {
        let mut after = 0.0;
        let inline = parse_html_blocks(
            "<p>a</p><span style='display:none'>hidden</span><p>b</p>",
            &[],
            &mut after,
        );
        let text: String = inline.iter().map(|b| b.text.clone()).collect();
        assert!(text.contains('a') && text.contains('b') && !text.contains("hidden"), "text={text:?}");

        let rules = parse_css(".hidden { display: none }");
        let mut after2 = 0.0;
        let class_hidden = parse_html_blocks(
            "<p>keep</p><p class=\"hidden\">drop</p><p hidden>drop2</p>",
            &rules,
            &mut after2,
        );
        let text2: String = class_hidden.iter().map(|b| b.text.clone()).collect();
        assert!(text2.contains("keep") && !text2.contains("drop"), "text={text2:?}");
    }

    #[test]
    fn tall_table_row_splits_across_pages() {
        let cell = "很长的单元格内容。".repeat(30);
        let html = format!("<table><tr><td>{cell}</td><td>x</td></tr></table>");
        let mut after = 0.0;
        let blocks = parse_html_blocks(&html, &[], &mut after);
        let advances: HashMap<u32, f32> = HashMap::new();
        let height = 220.0;
        let pages = layout_blocks(&blocks, &[], 300.0, height, 38.0, 1.8, &advances, &[], 300.0, 1.0);
        assert!(pages.len() > 1, "pages={}", pages.len());
        let style = blocks[0].style;
        let line_height = 38.0 * style.size * style.line_height.unwrap_or(1.8);
        for page in &pages {
            let max_cell_lines = page
                .iter()
                .filter(|line| line.table.is_some() && line.table.unwrap().col == 0)
                .count();
            let page_height = max_cell_lines as f32 * line_height;
            assert!(page_height <= height + 1.0, "page height {page_height} > {height}");
        }
    }

    #[test]
    fn line_does_not_start_with_closing_punctuation() {
        let mut after = 0.0;
        let blocks = parse_html_blocks("<p>Hello。World</p>", &[], &mut after);
        let advances: HashMap<u32, f32> = HashMap::new();
        let pages = layout_blocks(&blocks, &[], 150.0, 1634.0, 38.0, 1.8, &advances, &[], 150.0, 1.0);
        let lines: Vec<String> = pages.iter().flatten().map(|line| line.text.clone()).collect();
        assert!(!lines.iter().any(|line| line.trim_start().starts_with('。')), "lines={lines:?}");
        let joined: String = lines.iter().map(|line| line.trim_start_matches('　').trim()).collect::<Vec<_>>().join("");
        assert_eq!(joined.replace(' ', ""), "Hello。World");
    }

    #[test]
    fn inline_markup_adds_no_space() {
        let norm = |html: &str| -> String {
            let mut after = 0.0;
            let blocks = parse_html_blocks(html, &[], &mut after);
            blocks.iter().map(|b| b.text.clone()).collect()
        };
        let plain = |html: &str| -> String {
            norm(html)
                .chars()
                .filter(|ch| !('\u{0006}'..='\u{0013}').contains(ch))
                .collect::<String>()
                .replace(' ', "")
        };
        assert_eq!(plain("<p>第<b>1</b>章</p>"), "第1章");
        assert_eq!(plain("<p>a<i>b</i>c</p>"), "abc");
        assert_eq!(plain("<p>a <b>b</b> c</p>"), "abc");
        assert_eq!(plain("<p>a<span style=\"color:red\">b</span>c</p>"), "abc");
    }

    #[test]
    fn control_chars_do_not_reach_output_protocol() {
        let mut after = 0.0;
        let blocks = parse_html_blocks("<p>a&#0;b&#1;c&#4;d&#5;e&#10;f</p>", &[], &mut after);
        let text: String = blocks.iter().map(|b| b.text.clone()).collect();
        assert!(!text.chars().any(|ch| ch.is_control() && ch != ' ' && ch != '\n' && ch != '\t'), "text={text:?}");
        assert!(text.contains('a') && text.contains('f'), "text={text:?}");
    }

    #[test]
    fn parse_em_supports_rem_and_percent() {
        assert_eq!(parse_em("1.5rem"), Some(1.5));
        assert_eq!(parse_em("2em"), Some(2.0));
        assert_eq!(parse_em("150%"), Some(1.5));
        assert_eq!(parse_em("16px"), Some(1.0));
    }

    #[test]
    fn margin_three_values_use_bottom() {
        let rules = parse_css("p { margin: 1em 0 3em }");
        let mut after = 0.0;
        let blocks = parse_html_blocks("<p>x</p>", &rules, &mut after);
        assert!((blocks[0].style.space_before - 1.0).abs() < 0.001, "before={}", blocks[0].style.space_before);
        assert!((blocks[0].style.space_after - 3.0).abs() < 0.001, "after={}", blocks[0].style.space_after);
    }

    #[test]
    fn page_start_line_has_no_block_gap() {
        let text = "第一段文字。".repeat(40);
        let html = format!("<p>{text}</p><p>{text}</p>");
        let mut after = 0.0;
        let blocks = parse_html_blocks(&html, &[], &mut after);
        let advances: HashMap<u32, f32> = HashMap::new();
        let pages = layout_blocks(&blocks, &[], 300.0, 220.0, 38.0, 1.8, &advances, &[], 300.0, 1.0);
        assert!(pages.len() > 2, "pages={}", pages.len());
        for page in pages.iter().skip(1) {
            if let Some(first) = page.first() {
                assert_eq!(first.gap_em, 0.0, "page-top gap must be cleared");
            }
        }
    }

    #[test]
    fn lone_angle_bracket_does_not_panic() {
        for html in ["价格 < 10元", "<p>abc<", "a < b", "a<"] {
            let mut after = 0.0;
            let blocks = parse_html_blocks(html, &[], &mut after);
            let text: String = blocks.iter().map(|b| b.text.clone()).collect();
            assert!(text.contains('<') || !html.contains('<'), "html={html} text={text}");
        }
    }

    #[test]
    fn attr_value_non_ascii_and_no_substring_match() {
        assert_eq!(attr_value("class=\"x\"", "class").as_deref(), Some("x"));
        assert_eq!(attr_value("data-class=\"y\" class=\"z\"", "class").as_deref(), Some("z"));
        assert_eq!(attr_value("title=\"标题\" class=\"中文\"", "class").as_deref(), Some("中文"));
        assert_eq!(attr_value("title='a b'", "title").as_deref(), Some("a b"));
        assert_eq!(attr_value("id=abc def=1", "def").as_deref(), Some("1"));
    }

    #[test]
    fn long_paragraph_keeps_all_text_across_pages() {
        let sentence = "这是用于验证纵向分页不会丢失内容的一段很长的测试文字，";
        let text = sentence.repeat(80);
        let html = format!("<p>{}</p>", text);
        let mut after = 0.0;
        let blocks = parse_html_blocks(&html, &[], &mut after);
        let advances: HashMap<u32, f32> = HashMap::new();
        let pages = layout_blocks(&blocks, &[], 900.0, 800.0, 38.0, 1.8, &advances, &[], 1080.0, 1.0);
        let laid: String = pages
            .iter()
            .flat_map(|page| page.iter())
            .map(|line| line.text.trim().trim_start_matches('　').to_string())
            .collect();
        let norm = |value: &str| -> String {
            value.chars().filter(|ch| !ch.is_whitespace()).collect()
        };
        assert_eq!(
            norm(&laid),
            norm(&text),
            "pages={} laid_len={} text_len={}",
            pages.len(),
            norm(&laid).len(),
            norm(&text).len(),
        );
    }

    #[test]
    fn paragraphs_keep_webview_spacing() {
        let html = "<p>aaa</p><p>bbb</p>";
        let mut after = 0.0;
        let blocks = parse_html_blocks(html, &[], &mut after);
        assert_eq!(blocks.len(), 2, "blocks: {}", blocks.len());
        for block in &blocks {
            println!("space_after={} first_indent={} align={}", block.style.space_after, block.style.first_indent, block.style.align);
        }
        assert!((blocks[0].style.space_after - 0.55).abs() < 0.001);
    }
}
