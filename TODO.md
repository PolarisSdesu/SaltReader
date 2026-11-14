# TODO

- [x] **1. 继续优化 Native 阅读器两端对齐算法** (`native/`)
  目标：视觉效果与 WebView 阅读器基本一致——行末对齐分配、中文/空格边界、标点处理，行内代码不被拉伸。
  进展：
  - alpha.40 修复「跨行内 run 的边界计数与绘制不一致」（测量把跨 run 的中英文边界算进分母，绘制端没补）。
  - alpha.41 行内代码/代码块改用实测等宽字宽参与断行；需要每字补 >0.2em 才能拉满的行退化为左对齐，
    不再拉出夸张空隙。
  - 已定位残留现象：`std::char::from_digit(num, radix)` 这类长行内代码是不可断开 token，
    整串挪行后上一行只剩 ~60% 宽度，justify 时中文字间被拉开。Rust 单测已复现该折行
    （`layout_blocks` 1000px 宽、36px 字号：第一行只到「如果」）。
    - 已用 WebView 探针确认 Chrome 行为：行内代码**内部有空格时**会在空格处断开
    （`std::char::from_digit(num, ` / `radix)...`）；**没有空格时**不强行断字，而是溢出被裁切。
    因此 Rust 不需要在任意标点处加断点，UAX #14 的机会点已经足够。
  - 本轮改动：Rust 断行回溯窗口 64 → 512 grapheme（避免长行错过更早机会点）；
    `<br>` 结束的行改为参与两端对齐；Kotlin 的可拉伸边界改为 Blink `text-justify: auto`
    规则（空格之后、CJK 两侧、忽略默认忽略字符），并移除 alpha.41 的 0.2em 放弃阈值
    （WebView 没有该规则，短行会被拉满）。新增 `TextJustificationTest` 与 Rust 折行回归测试。
  - 已完成逐屏比对：用 `tools/make_justify_test_epub.py` 生成的 `Justify-Test2.epub` 在模拟器上
    对照 Native / WebView 同一页——长 token 都在 `(num,` 后的空格处断行，中文间隙被拉开到相同程度，
    纯中文段落的右边缘也一致。断行位置的细微差别来自字体度量，不是对齐算法。
  - 无空格超长 token 的差异保留：WebView 溢出裁切，Native 继续断行（见 docs）。
  - 修复折行落在行内代码/粗体中间时后半段掉回正文字体：Rust 现在会在新行行首重新
    注入仍生效的行内标记（`radix)` 已恢复等宽）。

- [x] **2. 关于页菜单的三个 Action 改为垂直排版**
  依次为：复制 / 打开调试模式 / 关闭（文字右对齐）。已完成。

- [x] **3. 更新 AGENTS.md 和 README**
  同步最近的 Native 阅读器、调试模式与设置相关改动。

- [x] **6. rust 书章节标题样式 native 与 WebView 不一致**
  （用户反馈 2026-11-14）native 的章节标题字号/间距/对齐与 WebView 的 `h2.chapter-heading`/`.pius*` 不一致。
  已完成：h3 = 1.25em、h4–h6 = 1.1em 且居中（df11267）；本轮补上自动章节标题的
  `h2.chapter-heading`——Native 注入该 class，Rust 采用与 WebView 相同的 0.35em/1.15em 间距。
  `.pius*` 的字号/行高/间距/对齐已一致，仅 `.pius1` 的 0.7 透明度尚未实现（影响很小）。

- [ ] **4. WebView 阅读器：tunnel 图片页抽搐 / 跳页**
  （2026-11-14）相关 WebView 翻页/重排改动已按用户要求整体回退；以下根因分析保留，后续重做时参考：
  阅读 tunnel 开头几张图片页时画面抖动/抽动、左右翻页会卡顿跳页。
  - 曾定位并修复 `prependChapters` 的重复补偿：立即补偿与图片就绪补偿都用同一个 `oldSize` 基线，
    同一位移会被加两次；改为按「上次已补偿尺寸」计算增量。
  - 曾定位 `updateViewportHeight` 在每次 `resize` 都无条件重排并重新吸附页码（现已随回退移除）。
  - 跳页根因：scroll-snap 标记只有 1px 高，Chrome 会忽略 `scroll-snap-stop: always`，
    快速 fling 直接跨过多个页面。把每个 snap area 改成整页大小（宽=页宽、高=视口高）后，
    原生 fling 每次稳定停在一页；另保留按像素的落点校正和 trim 期间禁用校正作为兜底。
  - 曾实测连续 5 次快滑精确前进 5 页；这些改动现已回退，待重新评估方案。

- [x] **5. Native 阅读器：tunnel 第一章头图被拆成单独一页**
  现状：头图独自占一页，图片之后整页留白，正文从下一页才开始。
  期望：与 WebView 一致——图片在页顶，正文紧接其后继续排版（截图 1 是期望效果，截图 2/3 是实际情况）。
  排查方向：`layout_blocks` 中图片块占用的高度与绘制高度是否一致（`image_box_height`、`image_aspects` 索引），
  以及图片之后是否被误触发分页（`y > height`）。另外 Native 分页数明显多于 WebView（345 vs 205），
  说明版面密度整体偏差，可一并排查。

- [ ] **7. 纵向模式长按选择段落错位**
  纵向滚动阅读时（LazyColumn 每个 item 是一页），长按选区高亮/复制会落在错误的段落或行。
  进展：已定位为 `lineTops()` 与真实绘制不一致——图片行只按一行字高推进、表格每个单元格
  都推进一行。现在 `lineTops` 复用与 `pageContentHeightPx()` 相同的推进逻辑，待真机长按复核。

- [x] **8. native 调试虚线框与内容包裹错位**
  虚线框原先自己用 `debugY += 一行字高` 估算，图片/表格/分页顶部偏移后整体漂移。
  现已改为直接复用 `lineTops()` / 绘制结束时的 `yTop`，与真实内容一致。

- [ ] **9. HTML5 标签预览与 native 优化**
  已新增 `tools/make_html5_tags_epub.py`（生成 Html5-Tags.epub）并逐屏对照 WebView。
  已修：
  - 未知结构标签（address/nav/main/aside/header/footer/details/summary/form/fieldset/
    legend/dialog）各自成块，不再继承上一个标题的字号/对齐；address 按 UA 样式斜体。
  - figure/figcaption、dl/dt/dd 的默认样式；pre 制表符按 tab-size 8 展开。
  - hr 输出为规则行，Kotlin 画 1px 分隔线；修复 `<hr/>`/`<br/>` 自闭合标签解析。
  - 有序列表不再同时出现「• 1.」；ol 的 li 关闭 bullet。
  - 行内语义：补齐 s/strike/del/ins/kbd/samp/tt/var/cite/time/abbr/bdi/bdo/ruby/wbr 等标签；
    `wbr` 映射为零宽断行点，`ins` 下划线、`var/cite` 斜体、`kbd/samp` 等宽。
  - **行内标记迁移到私用区 U+E000..U+E00D**：原先 `\u0006..\u0013` 里的 TAB/LF/VT/FF/CR
    与文本换行、分页分隔符冲突（下划线开启标记 `\u000A` 会被当成换行拆页）。Rust 与 Kotlin
    的测量、绘制、复制过滤全部同步更新。
  - `table caption` 已作为居中块输出（此前被直接丢弃）；blockquote 按 left_indent + 两端对齐
    特征补了左侧竖线。
  - 图片提取为 0 的原因已确认：EpubIndexer 会丢弃源码里直接写死的 `data:` 图片，只内联相对路径
    图片；已修正 `tools/make_html5_tags_epub.py` 改为打包 `images/sample.png` 并相对引用，
    现在 native 日志为 `images extracted=2`。
  - `q` 自动加弯引号；`ruby` 注音回退为「基字（读音）」；`<rp>` 回退括号与 `skip_until`
    文本泄漏一并修掉。
  - `small/big` 行内字号（0.85em/1.15em）与 `mark` 高亮背景已支持（新增
    U+E00E..U+E013 标记，测量/绘制同步）。
  - 真正的 ruby 注音排版：`<ruby>` 收成结构化 token（E014/E015/E016），断行按整体宽度，
    绘制时基字在基线、读音以 0.5 倍字号居中画在上方；复制仍输出「基字(读音)」。
  - 表格 `colspan/rowspan`：解析并占位，保证后续单元格不被挤错列；合并边框尚未绘制。
  待办：表格合并边框（真正的 colspan/rowspan 视觉合并）；forms/media 按设计不渲染交互控件。
