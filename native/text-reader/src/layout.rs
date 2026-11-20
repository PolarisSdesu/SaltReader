//! 断行与分页：把块级结构铺进固定版心，输出逐页逐行结果。

use std::collections::HashMap;

use unicode_linebreak::linebreaks;
use unicode_segmentation::UnicodeSegmentation;
use unicode_width::UnicodeWidthChar;

use crate::html::Block;
use crate::style::Style;

#[derive(Clone, Copy)]
pub(crate) struct TableCellMeta {
    pub(crate) col: u8,
    pub(crate) cols: u8,
    pub(crate) row_start: bool,
    pub(crate) last_row: bool,
    pub(crate) row_end: bool,
}

pub(crate) struct RenderLine {
    pub(crate) text: String,
    pub(crate) style: Style,
    pub(crate) line_height_factor: f32,
    pub(crate) gap_em: f32,
    pub(crate) table: Option<TableCellMeta>,
    pub(crate) image: Option<u32>,
}

pub(crate) fn is_no_line_start(ch: char) -> bool {
    matches!(
        ch,
        '，' | '。' | '、' | '；' | '：' | '？' | '！' | '）' | '】' | '》' | '」' | '』'
            | '”' | '’' | '…' | '—' | '·' | ',' | '.' | ';' | ':' | '?' | '!' | ')' | ']' | '}'
    )
}

/// 字宽表：BMP 用数组直接索引，非 BMP 回退 HashMap（避免逐字 SipHash）。
#[derive(Default)]
pub(crate) struct WidthTable {
    pub(crate) bmp: Vec<f32>,
    pub(crate) other: HashMap<u32, f32>,
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

pub(crate) fn char_width(ch: char, font_size: f32, scale: f32, advances: &WidthTable) -> f32 {
    // 行内样式标记（\u0006..\u000F）不占排版宽度。
    if ('\u{E000}'..='\u{E016}').contains(&ch) {
        return 0.0;
    }
    if let Some(w) = advances.get(ch as u32)
        && w > 0.0
    {
        return w * scale;
    }
    match UnicodeWidthChar::width(ch).unwrap_or(1) {
        2 => font_size,
        _ if ch == ' ' => font_size * 0.28,
        _ => font_size * 0.55,
    }
}

/// 等宽字体的近似字宽：代码块的排版宽度与绘制用的 monospace 字体一致，
/// 否则用正文比例字体估算会偏窄，长代码行会被裁切而不是折行。
pub(crate) fn mono_char_width(ch: char, size_px: f32, mono_adv_em: f32) -> f32 {
    if ('\u{E000}'..='\u{E016}').contains(&ch) {
        return 0.0;
    }
    match UnicodeWidthChar::width(ch).unwrap_or(1) {
        2 => size_px,
        _ => size_px * mono_adv_em,
    }
}

/// 直接写入输出缓冲，避免每行一次 String 分配。
pub(crate) fn write_style(out: &mut String, style: &Style, line_height_factor: f32, gap_em: f32) {
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

pub(crate) fn wrap_simple(
    text: &str,
    width: f32,
    size_px: f32,
    scale: f32,
    advances: &WidthTable,
    mono: bool,
    mono_adv_em: f32,
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
                    mono_char_width(ch, size_px, mono_adv_em)
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

/// 行内样式标记状态：折行可以把一个 run 切成两行，但标记字符只出现在 run 开头。
/// 切片后必须在下一行行首重新写入仍然生效的开启标记，否则后半段会掉回普通字体。
#[derive(Clone, Copy, Default)]
pub(crate) struct MarkerState {
    pub(crate) bold: bool,
    pub(crate) italic: bool,
    pub(crate) underline: bool,
    pub(crate) strike: bool,
    pub(crate) mono: bool,
    pub(crate) sup: bool,
    pub(crate) sub: bool,
    pub(crate) small: bool,
    pub(crate) big: bool,
    pub(crate) mark: bool,
}

impl MarkerState {
    fn observe(&mut self, ch: char) {
        match ch {
            '\u{E000}' => self.bold = true,
            '\u{E001}' => self.bold = false,
            '\u{E002}' => self.italic = true,
            '\u{E003}' => self.italic = false,
            '\u{E004}' => self.underline = true,
            '\u{E005}' => self.underline = false,
            '\u{E006}' => self.strike = true,
            '\u{E007}' => self.strike = false,
            '\u{E008}' => self.mono = true,
            '\u{E009}' => self.mono = false,
            '\u{E00A}' => {
                self.sup = true;
                self.sub = false;
            }
            '\u{E00B}' => self.sup = false,
            '\u{E00C}' => {
                self.sub = true;
                self.sup = false;
            }
            '\u{E00D}' => self.sub = false,
            '\u{E00E}' => self.small = true,
            '\u{E00F}' => self.small = false,
            '\u{E010}' => self.big = true,
            '\u{E011}' => self.big = false,
            '\u{E012}' => self.mark = true,
            '\u{E013}' => self.mark = false,
            _ => {}
        }
    }

    /// 重新开启仍然生效的行内样式；顺序与嵌套无关（绘制端只关心布尔状态）。
    fn reopen(&self) -> String {
        let mut out = String::new();
        if self.bold {
            out.push('\u{E000}');
        }
        if self.italic {
            out.push('\u{E002}');
        }
        if self.underline {
            out.push('\u{E004}');
        }
        if self.strike {
            out.push('\u{E006}');
        }
        if self.mono {
            out.push('\u{E008}');
        }
        if self.sup {
            out.push('\u{E00A}');
        } else if self.sub {
            out.push('\u{E00C}');
        }
        if self.small {
            out.push('\u{E00E}');
        }
        if self.big {
            out.push('\u{E010}');
        }
        if self.mark {
            out.push('\u{E012}');
        }
        out
    }
}

pub(crate) fn image_box_height(width: f32, page_height: f32, aspect: f32, max_height_ratio: f32) -> f32 {
    let aspect = aspect.clamp(0.05, 20.0);
    let ratio = max_height_ratio.clamp(0.5, 20.0);
    let full = width / aspect;
    if full > page_height * ratio {
        page_height * ratio
    } else {
        full
    }
}

#[allow(clippy::too_many_arguments)]
pub(crate) fn layout_blocks(
    blocks: &[Block],
    chapter_starts: &[usize],
    width: f32,
    height: f32,
    font_size: f32,
    line_height_base: f32,
    advances: &HashMap<u32, f32>,
    image_aspects: &[f32],
    image_widths: &[f32],
    image_base_width: f32,
    image_max_height_ratio: f32,
    mono_adv_em: f32,
) -> Vec<Vec<RenderLine>> {
    let widths = WidthTable::from_map(advances);
    let advances = &widths;
    let mut pages: Vec<Vec<RenderLine>> = Vec::new();
    let mut page_lines: Vec<RenderLine> = Vec::new();
    let mut y = 0.0f32;
    let mut pending_after = 0.0f32;

    let new_page = |pages: &mut Vec<Vec<RenderLine>>, page_lines: &mut Vec<RenderLine>| {
        if !page_lines.is_empty() {
            pages.push(std::mem::take(page_lines));
        }
    };

    for (block_index, block) in blocks.iter().enumerate() {
        // 与 WebView 阅读器一致：每个章节都从新的一页开始。唯一的例外是上一页只有插图——
        // WebView 里插图章的图片会与后一章的标题/正文排在同一页，若在这里强制换页，
        // 头图会独占一页、正文从下一页才开始，页尾留下大片空白。
        if chapter_starts.binary_search(&block_index).is_ok() {
            let previous_has_text = page_lines
                .iter()
                .any(|line| line.image.is_none() && !line.text.trim().is_empty());
            if previous_has_text && !page_lines.is_empty() {
                pages.push(std::mem::take(&mut page_lines));
                y = 0.0;
                pending_after = 0.0;
            }
        }
        if let Some(image_id) = block.image {
            let aspect = image_aspects
                .get(image_id as usize)
                .copied()
                .unwrap_or(1.0)
                .max(0.05);
            // WebView: max-width 不超过图片原始宽度，也不超过版面宽度。
            let intrinsic = image_widths
                .get(image_id as usize)
                .copied()
                .unwrap_or(0.0);
            let base_width = if intrinsic > 0.0 {
                image_base_width.min(intrinsic)
            } else {
                image_base_width
            };
            let image_height = image_box_height(base_width, height, aspect, image_max_height_ratio);
            let gap = pending_after.max(block.style.space_before * font_size);
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
                    cell_lines.push(wrap_simple(
                        &text,
                        cell_width,
                        size_px,
                        style.size,
                        advances,
                        style.mono,
                        mono_adv_em,
                    ));
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
        let mut style = block.style;
        if style.mono {
            // WebView #book pre 是绝对 0.86em（覆盖出版社 CSS，而不是再乘书自带字号）。
            style.size = 0.86;
            style.line_height = Some(1.5);
        }
        let size_px = font_size * style.size;
        let line_height_factor = style.line_height.unwrap_or(line_height_base);
        let line_height = size_px * line_height_factor;
        let left_indent_px = style.left_indent * font_size;
        let first_indent_em = style.first_indent;
        let content_width = (width - left_indent_px).max(32.0);

        // WebView/CSS margin 折叠：相邻块间距取两者较大值，而不是相加。
        let gap = pending_after.max(style.space_before * font_size);
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
        // 当前行行首仍然生效的行内样式；换行后据此重新写开启标记。
        let mut line_markers = MarkerState::default();
        let mut markers = MarkerState::default();

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
                }
                line.push_str(&token);
                line_width += inline_width;
                has_content = true;
                index = cursor;
                continue;
            }
            if grapheme == "\u{E014}" {
                // ruby token：E014 + 基字 + (E015 + 读音)? + E016，整体不可断开。
                let mut token = String::from("\u{E014}");
                let mut base = String::new();
                let mut reading = String::new();
                let mut in_reading = false;
                let mut cursor = index + 1;
                while cursor < graphemes.len() {
                    let part = graphemes[cursor].1;
                    token.push_str(part);
                    cursor += 1;
                    if part == "\u{E016}" {
                        break;
                    }
                    if part == "\u{E015}" {
                        in_reading = true;
                    } else if in_reading {
                        reading.push_str(part);
                    } else {
                        base.push_str(part);
                    }
                }
                let base_width: f32 = base
                    .chars()
                    .map(|ch| {
                        if style.mono {
                            mono_char_width(ch, size_px, mono_adv_em)
                        } else {
                            char_width(ch, size_px, style.size, advances)
                        }
                    })
                    .sum();
                let reading_width: f32 = reading
                    .chars()
                    .map(|ch| {
                        if style.mono {
                            mono_char_width(ch, size_px * 0.5, mono_adv_em)
                        } else {
                            char_width(ch, size_px, style.size * 0.5, advances)
                        }
                    })
                    .sum();
                let ruby_width = base_width.max(reading_width) + size_px * 0.05;
                let indent_px = if first_line { start_indent * size_px } else { 0.0 };
                if has_content && line_width + ruby_width > content_width - indent_px {
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
                    first_line = false;
                    start_indent = 0.0;
                }
                line.push_str(&token);
                line_width += ruby_width;
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
                    // 硬换行（<br>）结束的行在 CSS 里仍然两端对齐，只有段落最后一行不拉伸。
                    false,
                    if first_line { line_gap_em } else { 0.0 },
                    block.preserve,
                    block.preserve || block.keep_blank_lines,
                );
                index += 1;
                first_line = false;
                start_indent = 0.0;
                has_content = false;
                line_start_byte = byte_start + 1;
                line_markers = markers;
                line.push_str(&line_markers.reopen());
                continue;
            }
            // 记录行内样式标记，供折行后重新开启；\u000E 之后的字符按 0.92em 等宽估算。
            if let Some(ch) = grapheme.chars().next()
                && grapheme.len() == ch.len_utf8()
                && ('\u{E000}'..='\u{E016}').contains(&ch)
            {
                markers.observe(ch);
            }
            let width = grapheme
                .chars()
                .map(|ch| {
                    if style.mono {
                        mono_char_width(ch, size_px, mono_adv_em)
                    } else if markers.mono {
                        mono_char_width(ch, size_px, mono_adv_em) * 0.92
                    } else {
                        char_width(ch, size_px, style.size, advances)
                    }
                })
                .sum::<f32>();
            let indent_px = if first_line { start_indent * size_px } else { 0.0 };
            if has_content && line_width + width > content_width - indent_px {
                // 回溯本行内最近的断行机会。之前的 64 grapheme 上限会让长行
                // （例如超长行内代码 token 之后）错过更早的可断点，退化成把整个 token
                // 挪到下一行、上一行只剩一半宽度。512 仍能兜住病态输入的开销。
                let mut break_at: Option<usize> = None;
                for (position, _) in graphemes[..index].iter().rev().take(512) {
                    if *position <= line_start_byte {
                        break;
                    }
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
                // 本行行首仍然生效的行内样式（例如折行落在 <code> 中间）。
                let reopen = line_markers.reopen();
                let mut next_markers = line_markers;
                for ch in take.chars() {
                    next_markers.observe(ch);
                }
                line.clear();
                line.push_str(&reopen);
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
                // 下一行从切点继续：把切点处仍然生效的样式重新写进新行。
                line_markers = next_markers;
                line.push_str(&line_markers.reopen());
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
pub(crate) fn push_line(
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
