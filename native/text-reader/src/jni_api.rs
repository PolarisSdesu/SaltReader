//! JNI 入口：Kotlin 传入章节 HTML / 字体度量，返回可直接解码的页面字符串。

use std::collections::HashMap;

use jni::objects::{JFloatArray, JIntArray, JObject, JObjectArray, JString};
use jni::sys::{jboolean, jstring};
use jni::JNIEnv;

use crate::html::{parse_html_blocks, set_image_placeholder, Block};
use crate::layout::{layout_blocks, write_style};
use crate::style::{parse_css, CssRule};

#[unsafe(no_mangle)]
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
    image_widths: JFloatArray,
    image_full_width: jboolean,
    full_page_width: f32,
    image_max_height_ratio: f32,
    image_placeholder: JString,
    mono_advance_em: f32,
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
    if let Ok(length) = env.get_array_length(&image_aspects)
        && length > 0
    {
        let mut values = vec![0f32; length as usize];
        if env.get_float_array_region(&image_aspects, 0, &mut values).is_ok() {
            aspect_list = values;
        }
    }

    // 图片原始宽度（物理 px，0 = 不限）：对应 WebView 的 max-width 语义。
    let mut width_list: Vec<f32> = Vec::new();
    if let Ok(length) = env.get_array_length(&image_widths)
        && length > 0
    {
        let mut values = vec![0f32; length as usize];
        if env.get_float_array_region(&image_widths, 0, &mut values).is_ok() {
            width_list = values;
        }
    }

    if let Ok(text) = env.get_string(&image_placeholder) {
        let placeholder: String = text.into();
        set_image_placeholder(placeholder);
    }
    let width = width.max(32.0);
    let height = height.max(32.0);
    let font_size = font_size.max(1.0);
    let line_height_base = line_height_base.clamp(0.9, 3.5);
    // 实测等宽字宽（em）：行内代码/代码块的断行宽度必须与绘制字体一致，否则会提前折行。
    let mono_adv_em = mono_advance_em.clamp(0.3, 1.5);

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
        &width_list,
        image_base_width,
        image_max_height_ratio,
        mono_adv_em,
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
