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

- [ ] **4. WebView 阅读器：tunnel 前几页图片页面抽搐**
  阅读 tunnel 开头几张图片页时画面抖动/抽动，疑似图片加载完成或高度回写（`--app-vh` / JS 高度同步）引起的重排抖动。
  进展：
  - 修复 `prependChapters` 的重复补偿：立即补偿与图片就绪补偿都用同一个 `oldSize` 基线，
    同一位移会被加两次；改为按「上次已补偿尺寸」计算增量。
  - 修复 `updateViewportHeight` 在每次 `resize` 都无条件重排 CSS 分栏并重新吸附页码的问题：
    视口尺寸未变时直接返回，避免图片解码/系统栏动画期间连续重排。
  - 仍待真机（ColorOS/Android 17 等）确认；若仍抽搐，下一步检查 `loading="lazy"`
    图片在翻页瞬间解码对合成层的影响。

- [x] **5. Native 阅读器：tunnel 第一章头图被拆成单独一页**
  现状：头图独自占一页，图片之后整页留白，正文从下一页才开始。
  期望：与 WebView 一致——图片在页顶，正文紧接其后继续排版（截图 1 是期望效果，截图 2/3 是实际情况）。
  排查方向：`layout_blocks` 中图片块占用的高度与绘制高度是否一致（`image_box_height`、`image_aspects` 索引），
  以及图片之后是否被误触发分页（`y > height`）。另外 Native 分页数明显多于 WebView（345 vs 205），
  说明版面密度整体偏差，可一并排查。
