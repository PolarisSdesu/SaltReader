//! 章节 HTML -> 块级结构：轻量词法 + 标签/样式栈，不依赖完整 DOM。

use std::collections::HashMap;

use crate::style::*;

#[derive(Clone)]
pub(crate) struct Block {
    pub(crate) text: String,
    pub(crate) style: Style,
    pub(crate) table: Option<Vec<Vec<String>>>,
    pub(crate) image: Option<u32>,
    pub(crate) preserve: bool,
    /// 段落内存在连续 <br>（空行）时需要保留空行行盒。
    pub(crate) keep_blank_lines: bool,
}

use std::cell::RefCell;
thread_local! {
    /// 未解析行内图片时的占位文本（由 Kotlin 侧按语言传入）。
    static IMAGE_PLACEHOLDER: RefCell<String> = RefCell::new("［图片］".to_string());
}

pub(crate) fn image_placeholder() -> String {
    IMAGE_PLACEHOLDER.with(|value| value.borrow().clone())
}

/// 输出协议使用 \u0000..\u0005 等控制字符做分隔，正文里的控制字符必须清洗掉。
pub(crate) fn is_protocol_control(ch: char) -> bool {
    ch.is_control() && ch != '\n' && ch != '\t'
}

pub(crate) fn decode_entities(input: &str) -> String {
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

#[derive(Default)]
pub(crate) struct InlineState {
    /// b / i / code / u / s / sup-sub 六层嵌套深度，用来判断闭合标签是否还在行内。
    pub(crate) depth: [u8; 6],
}

pub(crate) fn normalize_block_text(text: &str, preserve: bool) -> String {
    if preserve {
        // WebView 默认 tab-size: 8；Canvas 不能直接画制表符，先展开成空格。
        return text.trim_matches('\n').replace('\t', "        ");
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
pub(crate) fn parse_html_blocks(html: &str, css_rules: &[CssRule], base_after: &mut f32) -> Vec<Block> {
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
    let mut table_caption = String::new();
    let mut in_caption = false;
    let mut table_cell_span: (usize, usize) = (1, 1);
    let mut table_rowspans: Vec<(usize, usize)> = Vec::new();
    let mut table_active_rowspans: Vec<usize> = Vec::new();
    // <ruby> 基字/读音收集：(base, reading, in_rt)
    let mut ruby: Option<(String, String, bool)> = None;
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
            // XHTML 常见的自闭合写法 <hr/> / <br/>：标签名后可能直接跟 '/'。
            let tag = parts.next().unwrap_or("").trim_end_matches('/').to_lowercase();
            if let Some(waiting) = &skip_until {
                if tag == *waiting {
                    skip_until = None;
                }
                continue;
            }
            // <rp> 是给不支持 ruby 的浏览器看的回退括号，直接跳过。
            if !closing && tag == "rp" {
                skip_until = Some("rp".to_string());
                continue;
            }
            if tag == "ruby" {
                if closing {
                    if let Some((base, reading, _)) = ruby.take() {
                        let base = base.trim();
                        let reading = reading.trim();
                        if !base.is_empty() {
                            text.push('\u{E014}');
                            text.push_str(base);
                            if !reading.is_empty() {
                                text.push('\u{E015}');
                                text.push_str(reading);
                            }
                            text.push('\u{E016}');
                        }
                    }
                } else {
                    ruby = Some((String::new(), String::new(), false));
                }
                continue;
            }
            if tag == "rt" {
                if let Some((_, _, in_rt)) = ruby.as_mut() {
                    *in_rt = !closing;
                }
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
                // 私有标记行：Kotlin 绘制端识别后画一条与版心同宽的 1px 分隔线。
                let mut separator = current_style;
                separator.align = 0;
                separator.left_indent = 0.0;
                separator.first_indent = 0.0;
                separator.space_before = 0.8;
                separator.space_after = 0.8;
                blocks.push(Block {
                    text: "\u{0014}".to_string(),
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
                    (false, "tr") => {
                        table_row.clear();
                        table_active_rowspans.clear();
                        for (col, remaining) in table_rowspans.iter_mut() {
                            if *remaining > 0 {
                                table_active_rowspans.push(*col);
                                *remaining -= 1;
                            }
                        }
                        table_rowspans.retain(|(_, remaining)| *remaining > 0);
                    }
                    (true, "tr") => {
                        if !table_row.is_empty() {
                            table_rows.push(std::mem::take(&mut table_row));
                        }
                    }
                    (false, "td") | (false, "th") => {
                        table_cell = Some(String::new());
                        table_cell_span = (
                            attr_value(attributes, "colspan")
                                .and_then(|value| value.parse::<usize>().ok())
                                .unwrap_or(1)
                                .clamp(1, 8),
                            attr_value(attributes, "rowspan")
                                .and_then(|value| value.parse::<usize>().ok())
                                .unwrap_or(1)
                                .clamp(1, 16),
                        );
                    }
                    (true, "td") | (true, "th") => {
                        if let Some(cell) = table_cell.take() {
                            // 先占住上一行 rowspan 留下的列（空单元格），保持列对齐。
                            while let Some(position) = table_active_rowspans
                                .iter()
                                .position(|col| *col == table_row.len())
                            {
                                table_active_rowspans.remove(position);
                                table_row.push(String::new());
                            }
                            let start_column = table_row.len();
                            table_row.push(normalize_block_text(&cell, false));
                            // colspan：其余列用空单元格占位，避免后续单元格左移。
                            for _ in 1..table_cell_span.0 {
                                table_row.push(String::new());
                            }
                            if table_cell_span.1 > 1 {
                                table_rowspans.push((start_column, table_cell_span.1 - 1));
                            }
                            table_cell_span = (1, 1);
                        }
                    }
                    (false, "caption") => {
                        in_caption = true;
                        table_caption.clear();
                    }
                    (true, "caption") => in_caption = false,
                    (true, "table") => {
                        in_table = false;
                        table_cell = None;
                        in_caption = false;
                        table_rowspans.clear();
                        table_active_rowspans.clear();
                        table_cell_span = (1, 1);
                        if !table_row.is_empty() {
                            table_rows.push(std::mem::take(&mut table_row));
                        }
                        if !table_caption.trim().is_empty() {
                            let mut caption_style = table_style();
                            caption_style.align = 1;
                            caption_style.space_before = 0.5;
                            caption_style.space_after = 0.2;
                            blocks.push(Block {
                                text: table_caption.trim().to_string(),
                                style: caption_style,
                                table: None,
                                image: None,
                                preserve: false,
                                keep_blank_lines: false,
                            });
                            table_caption.clear();
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
                table_rowspans.clear();
                table_active_rowspans.clear();
                table_cell_span = (1, 1);
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
                } else if (tag == "p" || tag == "li" || tag == "td" || tag == "th")
                    && !text.ends_with('\n')
                    && !text.is_empty()
                {
                    text.push('\n');
                }
                continue;
            }

            // 开始标签
            match tag.as_str() {
                "br" => {
                    text.push('\n');
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
                if tag == "li"
                    && let Some(counter) = ol_stack.last_mut()
                {
                    *counter += 1;
                    text.push_str(&format!("{}. ", counter));
                }
                tag_stack.push(tag.clone());
                let class_attr = attr_value(attributes, "class");
                let mut style = default_style_for_tag(&tag).unwrap_or_default();
                // <ol> 的 li 已经写入 "1. " 前缀，不能再叠加 "•"。
                if tag == "li" && !ol_stack.is_empty() {
                    style.bullet = false;
                }
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
        // skip_until 之前只拦标签，文本节点仍会漏进来（<rp> 的回退括号、<script> 内容等）。
        if skip_until.is_some() {
            continue;
        }
        if let Some((base, reading, in_rt)) = ruby.as_mut() {
            if *in_rt {
                reading.push_str(&decoded);
            } else {
                base.push_str(&decoded);
            }
            continue;
        }
        if in_table {
            if in_caption {
                table_caption.push_str(&decoded);
            } else if let Some(cell) = table_cell.as_mut() {
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

pub(crate) fn rule_specificity(rule: &CssRule) -> u32 {
    let mut score = 0;
    if rule.tag.is_some() {
        score += 1;
    }
    if rule.class_name.is_some() {
        score += 10;
    }
    score
}

pub(crate) fn apply_stack_css(
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

pub(crate) fn apply_class_css(
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
pub(crate) fn rules_display_none(tag: &str, class_attr: &str, rules: &[CssRule]) -> bool {
    let classes: Vec<&str> = class_attr.split_whitespace().collect();
    for rule in rules {
        if let Some(rule_tag) = &rule.tag
            && rule_tag != tag
        {
            continue;
        }
        if let Some(rule_class) = &rule.class_name
            && !classes.contains(&rule_class.as_str())
        {
            continue;
        }
        for (name, value) in &rule.declarations {
            if name == "display" && value.eq_ignore_ascii_case("none") {
                return true;
            }
        }
    }
    false
}

pub(crate) fn attr_value(attributes: &str, name: &str) -> Option<String> {
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

pub(crate) fn flush_block(blocks: &mut Vec<Block>, text: &mut String, style: Style, preserve: bool, base_after: &mut f32) {
    let normalized = text.trim_matches('\n');
    *base_after = base_after.min(style.space_after);
    if normalized.trim().is_empty() {
        text.clear();
        return;
    }
    let stored = if preserve {
        // <pre>: 保留首行缩进、空行与连续空格；制表符按 WebView 的 tab-size: 8 展开。
        normalized.replace('\t', "        ")
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


/// JNI 入口在排版前写入当前语言的「图片」占位文本。
pub(crate) fn set_image_placeholder(text: String) {
    IMAGE_PLACEHOLDER.with(|value| *value.borrow_mut() = text);
}
