//! 轻小说文字排版核心（Rust → libsaltreader_layout.so）。
//!
//! 输入：章节 HTML（含书籍自带 CSS）、版心尺寸、字号/行高、Kotlin Paint 实测字宽表；
//! 处理：轻量 HTML 词法 + CSS 子集（font-size / text-align / text-indent / margin /
//! font-weight / font-style / line-height / display），生成块级样式，再做 UAX #14 断行与分页；
//! 输出：`页面1\u{0}页面2…`，页内行用 `\n` 分隔；每行以 `\u{1}` + 5 位样式编码开头：
//! 字号倍率(0.1em)、行高倍率(0.1)、标志位(粗/斜/等宽)、对齐(0/1/2)、左缩进(0.1em)。



mod style;
mod html;
mod layout;
mod jni_api;

// 测试模块统一从 crate 根拿到 html/layout/style 的公开项。
#[cfg(test)]
pub(crate) use std::collections::HashMap;
#[cfg(test)]
pub(crate) use crate::html::*;
#[cfg(test)]
pub(crate) use crate::layout::*;
#[cfg(test)]
pub(crate) use crate::style::*;

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
        let pages = layout_blocks(&blocks, &[], 900.0, 1634.0, 38.0, 1.8, &advances, &[], &[], 1080.0, 1.0, 0.6);
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
        let pages = layout_blocks(&blocks, &[], 600.0, 1634.0, 38.0, 1.8, &advances, &[], &[], 600.0, 1.0, 0.6);
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
        let pages = layout_blocks(&blocks, &[], 300.0, height, 38.0, 1.8, &advances, &[], &[], 300.0, 1.0, 0.6);
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
        let pages = layout_blocks(&blocks, &[], 150.0, 1634.0, 38.0, 1.8, &advances, &[], &[], 150.0, 1.0, 0.6);
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
                .filter(|ch| !('\u{E000}'..='\u{E016}').contains(ch))
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
        let pages = layout_blocks(&blocks, &[], 300.0, 220.0, 38.0, 1.8, &advances, &[], &[], 300.0, 1.0, 0.6);
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
        let pages = layout_blocks(&blocks, &[], 900.0, 800.0, 38.0, 1.8, &advances, &[], &[], 1080.0, 1.0, 0.6);
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

#[cfg(test)]
mod chapter_break_tests {
    use super::*;

    /// 上一页只有插图时，下一章的标题/正文应接在同一页（与 WebView 插图章一致）。
    #[test]
    fn text_follows_image_on_same_page() {
        let html = "<img data-salt-img=\"0\"><p>我讨厌夏天。酷热的天气，实在不像刚进入夏天的早晨。</p>";
        let mut after = 0.0;
        let blocks = parse_html_blocks(html, &[], &mut after);
        assert!(blocks[0].image.is_some(), "blocks: {}", blocks.len());
        let advances: HashMap<u32, f32> = HashMap::new();
        let pages = layout_blocks(
            &blocks,
            &[1],
            940.0,
            1784.0,
            36.0,
            1.8,
            &advances,
            &[1.25],
            &[1080.0],
            1080.0,
            0.92,
            0.6,
        );
        assert_eq!(pages.len(), 1, "插图与正文应同页");
        assert!(pages[0][0].image.is_some());
        assert!(
            pages[0].iter().skip(1).any(|line| !line.text.trim().is_empty()),
            "正文应排在插图之后"
        );
    }

    /// 正常正文之间仍然每个章节另起一页。
    #[test]
    fn text_chapter_still_starts_new_page() {
        let html = "<p>第一段。</p><p>第二段。</p>";
        let mut after = 0.0;
        let blocks = parse_html_blocks(html, &[], &mut after);
        let advances: HashMap<u32, f32> = HashMap::new();
        let pages = layout_blocks(
            &blocks,
            &[1],
            940.0,
            600.0,
            36.0,
            1.8,
            &advances,
            &[],
            &[],
            1080.0,
            0.92,
            0.6,
        );
        assert_eq!(pages.len(), 2, "章节之间应换页");
        assert!(pages[0].iter().any(|line| line.text.contains("第一段")));
        assert!(pages[1].iter().any(|line| line.text.contains("第二段")));
    }
}

#[cfg(test)]
mod inline_code_break_tests {
    use super::*;

    fn layout(html: &str, width: f32, font: f32) -> Vec<Vec<RenderLine>> {
        let mut after = 0.0;
        let blocks = parse_html_blocks(html, &[], &mut after);
        let advances: HashMap<u32, f32> = HashMap::new();
        layout_blocks(&blocks, &[], width, 4000.0, font, 1.8, &advances, &[], &[], width, 1.0, 0.6)
    }

    /// 行内代码内部有空格时，应像 WebView 一样在空格处断开，而不是把整串挪到下一行。
    #[test]
    fn inline_code_breaks_at_internal_space() {
        let html = "<p>前文<code>abcdefghij klmnopqrstuvwxyz0123456789ABCDEFGHIJklmnopqrstuvwxyz</code>收尾。</p>";
        let pages = layout(html, 1000.0, 36.0);
        let lines: Vec<String> = pages.iter().flat_map(|page| page.iter()).map(|l| l.text.clone()).collect();
        assert!(lines.len() >= 2, "lines={lines:?}");
        assert!(lines[0].contains("abcdefghij"), "first line should keep the code prefix: {lines:?}");
        assert!(!lines[0].contains("klmn"), "first line should break at the internal space: {lines:?}");
        // 折行落在 <code> 中间时，新行必须重新开启等宽标记，否则后半段会掉回正文字体。
        assert!(lines[1].starts_with('\u{E008}'), "second line must re-open inline code: {lines:?}");
        assert!(
            lines[1].trim_start_matches('\u{E008}').starts_with("klmn"),
            "second line should resume after the space: {lines:?}",
        );
    }

    /// 粗体跨行时同样要在新行重新开启粗体标记。
    #[test]
    fn split_bold_run_reopens_marker() {
        let bold = "中".repeat(80);
        let html = format!("<p>开头<b>{bold}</b>结尾。</p>");
        let pages = layout(&html, 600.0, 36.0);
        let lines: Vec<String> = pages.iter().flat_map(|page| page.iter()).map(|l| l.text.clone()).collect();
        assert!(lines.len() >= 3, "lines={}", lines.len());
        for line in &lines[1..lines.len() - 1] {
            assert!(line.starts_with('\u{E000}'), "bold line must re-open its marker: {line:?}");
        }
    }

    /// 没有空格的超长 token 不能在行内被留成一段空白：必须继续断行，保证全文可读。
    #[test]
    fn overlong_token_is_broken_instead_of_left_short() {
        let token = "x".repeat(200);
        let html = format!("<p>开头{token}结尾。</p>");
        let pages = layout(&html, 1000.0, 36.0);
        let lines: Vec<String> = pages.iter().flat_map(|page| page.iter()).map(|l| l.text.clone()).collect();
        assert!(lines.len() >= 3, "lines={}", lines.len());
        // 首行在 token 前的换行机会处结束（与 WebView 一致，会被两端对齐拉满）；
        // token 本身必须逐行填满版心，不能整串反复挪行。
        for line in &lines[1..lines.len() - 1] {
            assert!(line.chars().count() >= 20, "unexpected short line: {line:?}");
        }
        let joined: String = lines.join("");
        assert!(joined.contains(&token), "token must not be dropped");
    }
}

#[cfg(test)]
mod heading_style_tests {
    use super::*;

    #[test]
    fn generated_chapter_heading_uses_web_view_margins() {
        let mut after = 0.0;
        let blocks = parse_html_blocks(
            "<h2 class=\"chapter-heading\">第一章</h2><p>正文</p>",
            &[],
            &mut after,
        );
        let heading = blocks.iter().find(|b| b.text.contains("第一章")).expect("heading block");
        assert_eq!(heading.style.align, 1, "heading must be centered");
        assert!(heading.style.bold);
        assert!((heading.style.space_before - 0.35).abs() < 0.001, "space_before={}", heading.style.space_before);
        assert!((heading.style.space_after - 1.15).abs() < 0.001, "space_after={}", heading.style.space_after);
        assert!((heading.style.size - 1.4).abs() < 0.001, "size={}", heading.style.size);
    }
}

#[cfg(test)]
mod html5_structure_tests {
    use super::*;

    fn blocks(html: &str) -> Vec<Block> {
        let mut after = 0.0;
        parse_html_blocks(html, &[], &mut after)
    }

    #[test]
    fn unknown_structural_tags_get_their_own_block_style() {
        let parsed = blocks(
            "<h3>x</h3><address>地址</address><nav>导航</nav><main>主内容</main>",
        );
        let address = parsed.iter().find(|b| b.text.contains("地址")).expect("address block");
        assert!((address.style.size - 1.0).abs() < 0.001, "address must not inherit the heading size");
        assert!(address.style.italic, "address is italic in the UA stylesheet");
        assert_eq!(address.style.align, 0);
        assert!((address.style.first_indent - 0.0).abs() < 0.001);
        let nav = parsed.iter().find(|b| b.text.contains("导航")).expect("nav block");
        assert!((nav.style.first_indent - 0.0).abs() < 0.001);
    }

    #[test]
    fn pre_expands_tabs_like_web_view() {
        let parsed = blocks("<pre>a\tb</pre>");
        assert!(parsed[0].text.contains("a        b"), "text={:?}", parsed[0].text);
    }

    #[test]
    fn hr_becomes_a_rule_marker_block() {
        let parsed = blocks("<p>上</p><hr/><p>下</p>");
        assert!(parsed.iter().any(|b| b.text == "\u{0014}"), "blocks={:?}", parsed.iter().map(|b| &b.text).collect::<Vec<_>>());
    }

    #[test]
    fn q_quotes_and_ruby_reading_are_readable() {
        let parsed = blocks("<p><q>引用</q><ruby>漢字<rp>(</rp><rt>かんじ</rt><rp>)</rp></ruby></p>");
        let text = &parsed[0].text;
        assert!(text.contains("“引用”"), "text={text:?}");
        assert!(
            text.contains("\u{E014}漢字\u{E015}かんじ\u{E016}"),
            "ruby token missing: {text:?}",
        );
    }

    #[test]
    fn table_spans_preserve_column_alignment() {
        let parsed = blocks(
            "<table><tr><td colspan=\"2\">宽</td></tr>\
<tr><td rowspan=\"2\">高</td><td>b</td></tr><tr><td>c</td></tr></table>",
        );
        let table = parsed.iter().find(|b| b.table.is_some()).expect("table");
        let rows = table.table.as_ref().unwrap();
        assert_eq!(rows[0], vec!["宽".to_string(), "".to_string()]);
        assert_eq!(rows[1], vec!["高".to_string(), "b".to_string()]);
        assert_eq!(rows[2], vec!["".to_string(), "c".to_string()]);
    }

    #[test]
    fn table_caption_becomes_a_centered_block_before_the_table() {
        let parsed = blocks("<table><caption>表格标题</caption><tr><td>a</td><td>b</td></tr></table>");
        let caption = parsed.iter().find(|b| b.text == "表格标题").expect("caption block");
        assert_eq!(caption.style.align, 1);
        assert!(parsed.iter().any(|b| b.table.is_some()));
    }

    #[test]
    fn inline_semantic_tags_emit_style_markers() {
        let parsed = blocks(
            "<p><del>a</del><ins>b</ins><kbd>c</kbd><var>d</var><small>e</small><big>f</big>\
<mark>g</mark><wbr/>h</p>",
        );
        let text = &parsed[0].text;
        for marker in [
            '\u{E006}', '\u{E004}', '\u{E008}', '\u{E002}', '\u{E00E}', '\u{E010}', '\u{E012}',
            '\u{200B}',
        ] {
            assert!(text.contains(marker), "missing {:?} in {:?}", marker, text);
        }
    }

    #[test]
    fn ordered_list_items_do_not_add_a_bullet() {
        let parsed = blocks("<ol><li>一</li><li>二</li></ol>");
        for block in &parsed {
            assert!(!block.style.bullet, "ol li must not render a bullet: {:?}", block.text);
            assert!(block.text.starts_with("1. ") || block.text.starts_with("2. "), "text={:?}", block.text);
        }
    }
}

#[cfg(test)]
mod perf_bench {
    use super::*;
    use std::time::Instant;

    /// 手动基准：`cargo test --release bench_large_book -- --ignored --nocapture`
    #[test]
    #[ignore]
    fn bench_large_book() {
        let para = "回".repeat(100);
        let chapter = format!(
            "<h2>章节</h2>{}",
            (0..30).map(|_| format!("<p>{para}</p>")).collect::<String>()
        );
        let html: String = (0..198).map(|_| chapter.clone()).collect();
        let mut best = u128::MAX;
        for _ in 0..5 {
            let start = Instant::now();
            let mut after = 1.0f32;
            let blocks = parse_html_blocks(&html, &[], &mut after);
            let advances: HashMap<u32, f32> = HashMap::new();
            let pages = layout_blocks(&blocks, &[], 1000.0, 1600.0, 36.0, 1.8, &advances, &[], &[], 1000.0, 1.0, 0.6);
            best = best.min(start.elapsed().as_millis());
            std::hint::black_box(pages);
        }
        println!("BENCH parse+layout best={best}ms blocks_len={}", html.len());
    }
}
