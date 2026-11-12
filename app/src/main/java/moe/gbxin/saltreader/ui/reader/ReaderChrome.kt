@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package moe.gbxin.saltreader.ui.reader

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.FormatLineSpacing
import androidx.compose.material.icons.outlined.Margin
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.rounded.Adjust
import androidx.compose.material.icons.automirrored.rounded.FormatIndentIncrease
import androidx.compose.material.icons.rounded.FormatLineSpacing
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.ViewCarousel
import androidx.compose.material.icons.rounded.ViewStream
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import moe.gbxin.saltreader.R
import moe.gbxin.saltreader.data.AppSettings
import moe.gbxin.saltreader.data.ComicBook
import moe.gbxin.saltreader.data.EbookChapter
import moe.gbxin.saltreader.data.READER_FONT_SIZE_MAX_SP
import moe.gbxin.saltreader.data.READER_FONT_SIZE_MIN_SP
import moe.gbxin.saltreader.data.READER_LINE_HEIGHT_MAX
import moe.gbxin.saltreader.data.READER_LINE_HEIGHT_MIN
import moe.gbxin.saltreader.data.READER_PAGE_MARGIN_MAX
import moe.gbxin.saltreader.data.READER_PAGE_MARGIN_MIN
import moe.gbxin.saltreader.data.ReaderBackground
import moe.gbxin.saltreader.data.ReaderFontFamily
import moe.gbxin.saltreader.data.ReaderMode
import moe.gbxin.saltreader.data.ReadingDirection
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Reader chrome shared by the image pager and the reflowable text reader.
 *
 * The visual language follows WeChat Reading: a translucent top bar, a bottom toolbar with a
 * chapter list / progress slider / appearance entry, and bottom sheets for contents and settings.
 * Components are Material 3 (Expressive where available): floating toolbar, state-based sliders,
 * segmented buttons, list items and modal bottom sheets.
 */

/**
 * Top bar with the book title and the current chapter as a small subtitle, WeChat-Reading style:
 * back on the left, text block next to it (the chapter title is the quiet second line).
 */
@Composable
internal fun ReaderTopBar(
    book: ComicBook,
    onBack: () -> Unit,
    subtitle: () -> String? = { null },
    containerColor: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Surface(
        color = containerColor,
        contentColor = contentColor,
    ) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.reader_back))
            }
            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(
                    book.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val chapter = subtitle()
                if (!chapter.isNullOrBlank()) {
                    Text(
                        chapter,
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(48.dp))
        }
    }
}

/** Which inline panel is expanded above the bottom toolbar (WeChat Reading style). */
internal enum class ReaderChromePanel { NONE, TOC, PROGRESS, THEME, TYPOGRAPHY }

/**
 * WeChat-Reading-style bottom toolbar: a flat, page-coloured bar with icon-only actions and no
 * permanent slider. Tapping an action expands the matching inline panel above the bar, exactly like
 * WeChat Reading's 目录 / 进度 / 主题 / Aa toolbar.
 *
 * Text books get 目录 | 进度 | 主题 | Aa; image books get 进度 | 主题 | 设置.
 */
@Composable
internal fun ReaderBottomBar(
    progress: () -> Float,
    label: @Composable () -> String,
    onJump: (Float) -> Unit,
    progressLabelSizeSp: Int = 12,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    activePanel: ReaderChromePanel,
    onThemeToggle: () -> Unit,
    onOpenToc: (() -> Unit)?,
    onOpenSettings: () -> Unit,
    showTypography: Boolean = false,
    onTypographyToggle: () -> Unit = {},
    readerMode: ReaderMode,
    onReaderModeToggle: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val safeProgress = progress().coerceIn(0f, 1f)
    val labelText = label()
    val haptics = LocalHapticFeedback.current
    val sliderState = rememberSliderState(value = safeProgress, trackRange = 0f..1f)
    var lastHapticTick by remember { mutableFloatStateOf(safeProgress) }
    LaunchedEffect(safeProgress) {
        if (!sliderState.isDragging) {
            sliderState.value = safeProgress
            lastHapticTick = safeProgress
        }
    }
    val tocLabel = stringResource(R.string.reader_toc_title)
    val themeLabel = stringResource(R.string.reader_bar_theme)
    val settingsLabel = stringResource(R.string.reader_bar_settings)
    val typeLabel = stringResource(R.string.reader_section_typography)
    val modeLabel = stringResource(
        if (readerMode == ReaderMode.PAGED) R.string.reader_switch_to_vertical
        else R.string.reader_switch_to_paged,
    )
    val sliderLabel = stringResource(R.string.reader_progress_slider)
    val dividerColor = contentColor.copy(alpha = 0.14f)
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Column(Modifier.fillMaxWidth()) {
            HorizontalDivider(color = dividerColor)
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 2.dp)) {
                // The progress slider stays resident so the reading position is always visible and
                // scrubbable without opening a panel.
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Slider on top, "xx% · xx/xx" centred underneath it.
                    Slider(
                        state = sliderState,
                        onValueChange = { value ->
                            sliderState.value = value
                            // Light tick every ~2% while scrubbing, like a physical page dial.
                            if (abs(value - lastHapticTick) >= 0.02f) {
                                lastHapticTick = value
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        },
                        onValueChangeFinished = { onJump(sliderState.value) },
                        colors = SliderDefaults.colors(
                            thumbColor = accentColor,
                            activeTrackColor = accentColor,
                            activeTickColor = accentColor.copy(alpha = 0.55f),
                            inactiveTrackColor = contentColor.copy(alpha = 0.20f),
                            inactiveTickColor = contentColor.copy(alpha = 0.16f),
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription = sliderLabel
                                stateDescription = labelText
                            },
                    )
                    Text(
                        labelText,
                        modifier = Modifier.padding(top = 2.dp, bottom = 2.dp),
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontSize = progressLabelSizeSp.sp,
                            fontFeatureSettings = "tnum",
                        ),
                        color = contentColor.copy(alpha = 0.78f),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
                Row(
                    Modifier.fillMaxWidth().height(52.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onOpenToc != null) {
                        ReaderBarIcon(
                            icon = Icons.AutoMirrored.Rounded.FormatListBulleted,
                            label = tocLabel,
                            active = activePanel == ReaderChromePanel.TOC,
                            onClick = onOpenToc,
                            contentColor = contentColor,
                            activeColor = accentColor,
                            showIndicator = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    ReaderBarIcon(
                        icon = if (readerMode == ReaderMode.PAGED) Icons.Rounded.ViewStream
                        else Icons.Rounded.ViewCarousel,
                        label = modeLabel,
                        active = false,
                        onClick = onReaderModeToggle,
                        contentColor = contentColor,
                        modifier = Modifier.weight(1f),
                    )
                    ReaderBarIcon(
                        icon = Icons.Rounded.WbSunny,
                        label = themeLabel,
                        active = activePanel == ReaderChromePanel.THEME,
                        onClick = onThemeToggle,
                        contentColor = contentColor,
                        activeColor = accentColor,
                        showIndicator = true,
                        modifier = Modifier.weight(1f),
                    )
                    if (showTypography) {
                        ReaderBarIcon(
                            icon = Icons.Rounded.FormatSize,
                            label = typeLabel,
                            active = activePanel == ReaderChromePanel.TYPOGRAPHY,
                            onClick = onTypographyToggle,
                            contentColor = contentColor,
                            activeColor = accentColor,
                            showIndicator = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    ReaderBarIcon(
                        icon = Icons.Rounded.Tune,
                        label = settingsLabel,
                        active = false,
                        onClick = onOpenSettings,
                        contentColor = contentColor,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ReaderBarIcon(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    contentColor: Color,
    modifier: Modifier = Modifier,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    /** 面板类按钮（目录/主题/字号）激活时在图标下方画圆角矩形指示器；一次性动作不需要。 */
    showIndicator: Boolean = false,
) {
    // 复刻按压时的 ripple：外层是按钮 bounds 上的浅色胶囊，内层是 48dp 的深色圆。
    val outerIndicatorColor by animateColorAsState(
        targetValue = if (active && showIndicator) {
            contentColor.copy(alpha = 0.08f)
        } else {
            Color.Transparent
        },
        animationSpec = tween(durationMillis = 140, easing = FastOutSlowInEasing),
        label = "readerBarOuterIndicator",
    )

    IconButton(
        onClick = onClick,
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(CircleShape)
                .background(outerIndicatorColor),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = if (active) activeColor else contentColor,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun ReaderPanelSurface(containerColor: Color, content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = containerColor,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        content()
    }
}

/**
 * Progress panel: big percentage, page label, a slider with previous/next-chapter arrows and the
 * "last read" caption, mirroring WeChat Reading's progress popup.
 */
@Composable
internal fun ReaderProgressPanel(
    progress: Float,
    percentLabel: String,
    pageLabel: String?,
    onJump: (Float) -> Unit,
    onPrevChapter: (() -> Unit)? = null,
    onNextChapter: (() -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    accentColor: Color = MaterialTheme.colorScheme.primary,
) {
    val safeProgress = progress.coerceIn(0f, 1f)
    val sliderState = rememberSliderState(value = safeProgress, trackRange = 0f..1f)
    LaunchedEffect(safeProgress) {
        if (!sliderState.isDragging) sliderState.value = safeProgress
    }
    val lastReadLabel = stringResource(R.string.reader_progress_last_read)
    val progressSliderTitle = stringResource(R.string.reader_progress_slider)
    ReaderPanelSurface(containerColor) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    percentLabel,
                    style = MaterialTheme.typography.headlineMedium,
                    color = contentColor,
                )
                Spacer(Modifier.weight(1f))
                if (pageLabel != null) {
                    Text(
                        pageLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = contentColor.copy(alpha = 0.65f),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onPrevChapter != null) {
                    IconButton(onClick = onPrevChapter) {
                        Icon(
                            Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                            contentDescription = stringResource(R.string.reader_action_previous_page),
                            tint = contentColor,
                        )
                    }
                }
                Slider(
                    state = sliderState,
                    onValueChange = { sliderState.value = it },
                    onValueChangeFinished = { onJump(sliderState.value) },
                    colors = SliderDefaults.colors(
                        thumbColor = contentColor,
                        activeTrackColor = contentColor,
                        activeTickColor = contentColor.copy(alpha = 0.55f),
                        inactiveTrackColor = contentColor.copy(alpha = 0.20f),
                        inactiveTickColor = contentColor.copy(alpha = 0.16f),
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .semantics {
                            contentDescription = progressSliderTitle
                            stateDescription = percentLabel
                        },
                )
                if (onNextChapter != null) {
                    IconButton(onClick = onNextChapter) {
                        Icon(
                            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = stringResource(R.string.reader_action_next_page),
                            tint = contentColor,
                        )
                    }
                }
            }
            Text(
                lastReadLabel,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor.copy(alpha = 0.5f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Theme panel: font size (text books) and the paper-colour swatches, below the toolbar. */
@Composable
internal fun ReaderThemePanel(
    settings: AppSettings,
    onSettingsChange: ((AppSettings) -> AppSettings) -> Unit,
    onCustomBackground: () -> Unit,
    showTextSize: Boolean = false,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    accentColor: Color = MaterialTheme.colorScheme.primary,
) {
    ReaderPanelSurface(containerColor) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 10.dp),
        ) {
            Text(
                stringResource(
                    if (showTextSize) R.string.reader_theme_and_text_size else R.string.reader_bar_theme,
                ),
                style = MaterialTheme.typography.titleSmall,
                color = contentColor,
            )
            if (showTextSize) {
                Spacer(Modifier.height(4.dp))
                val fontMin = READER_FONT_SIZE_MIN_SP.toFloat()
                val fontMax = READER_FONT_SIZE_MAX_SP.toFloat()
                val fontSliderState = rememberSliderState(
                    value = settings.readerFontSizeSp.toFloat().coerceIn(fontMin, fontMax),
                    steps = (READER_FONT_SIZE_MAX_SP - READER_FONT_SIZE_MIN_SP - 1).coerceAtLeast(0),
                    trackRange = fontMin..fontMax,
                )
                LaunchedEffect(settings.readerFontSizeSp) {
                    if (!fontSliderState.isDragging) {
                        fontSliderState.value = settings.readerFontSizeSp.toFloat().coerceIn(fontMin, fontMax)
                    }
                }
                val fontValueText = stringResource(R.string.reader_font_size_value, settings.readerFontSizeSp)
                val fontSizeTitle = stringResource(R.string.reader_font_size)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("A", style = MaterialTheme.typography.labelLarge, color = contentColor.copy(alpha = 0.6f))
                    Slider(
                        state = fontSliderState,
                        onValueChange = { fontSliderState.value = it },
                        onValueChangeFinished = {
                            val sp = fontSliderState.value.roundToInt()
                                .coerceIn(READER_FONT_SIZE_MIN_SP, READER_FONT_SIZE_MAX_SP)
                            onSettingsChange { it.copy(readerFontSizeSp = sp) }
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = accentColor,
                            activeTrackColor = accentColor,
                            activeTickColor = accentColor.copy(alpha = 0.55f),
                            inactiveTrackColor = contentColor.copy(alpha = 0.20f),
                            inactiveTickColor = contentColor.copy(alpha = 0.16f),
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp)
                            .semantics {
                                contentDescription = fontSizeTitle
                                stateDescription = fontValueText
                            },
                    )
                    Text("A", style = MaterialTheme.typography.titleLarge, color = contentColor)
                }
            }
            Spacer(Modifier.height(12.dp))
            ReaderThemeRow(
                settings = settings,
                onSettingsChange = onSettingsChange,
                onCustomBackground = onCustomBackground,
                contentColor = contentColor,
                accentColor = accentColor,
            )
        }
    }
}

/** Typography panel: font family, page-margin and line-spacing pills plus an advanced-settings entry. */
@Composable
internal fun ReaderTypographyPanel(
    settings: AppSettings,
    onSettingsChange: ((AppSettings) -> AppSettings) -> Unit,
    onOpenAdvanced: () -> Unit,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    accentColor: Color = MaterialTheme.colorScheme.primary,
) {
    val marginOptions = listOf(
        stringResource(R.string.reader_level_small),
        stringResource(R.string.reader_level_medium),
        stringResource(R.string.reader_level_large),
    )
    val spacingOptions = listOf(
        stringResource(R.string.reader_spacing_tight),
        stringResource(R.string.reader_level_medium),
        stringResource(R.string.reader_spacing_loose),
    )
    val marginIndex = when {
        settings.readerPageMarginDp <= 24 -> 0
        settings.readerPageMarginDp <= 34 -> 1
        else -> 2
    }
    val spacingIndex = when {
        settings.readerLineHeight <= 1.65f -> 0
        settings.readerLineHeight <= 1.95f -> 1
        else -> 2
    }
    val marginValues = intArrayOf(20, 30, 40)
    val spacingValues = floatArrayOf(1.5f, 1.8f, 2.1f)
    ReaderPanelSurface(containerColor) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 6.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.reader_section_typography),
                    style = MaterialTheme.typography.titleSmall,
                    color = contentColor,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onOpenAdvanced) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.reader_more_settings),
                            style = MaterialTheme.typography.titleSmall,
                            color = contentColor,
                        )
                        Spacer(Modifier.width(2.dp))
                        Icon(
                            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = null,
                            tint = contentColor,
                            // 与 titleSmall 的文字行高一致，不再用字符“›”。
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            ReaderTypographySegmentedRow(
                icon = Icons.Outlined.TextFields,
                title = stringResource(R.string.reader_font_family),
                options = listOf(
                    stringResource(R.string.reader_font_family_system),
                    stringResource(R.string.reader_font_family_song),
                    stringResource(R.string.reader_font_family_hei),
                ),
                selectedIndex = settings.readerFontFamily.ordinal,
                contentColor = contentColor,
                accentColor = accentColor,
                onSelect = { index ->
                    onSettingsChange { it.copy(readerFontFamily = ReaderFontFamily.entries[index]) }
                },
            )
            Spacer(Modifier.height(10.dp))
            ReaderTypographySegmentedRow(
                icon = Icons.Outlined.Margin,
                title = stringResource(R.string.reader_page_margin),
                options = marginOptions,
                selectedIndex = marginIndex,
                contentColor = contentColor,
                accentColor = accentColor,
                onSelect = { index ->
                    onSettingsChange { it.copy(readerPageMarginDp = marginValues[index]) }
                },
            )
            Spacer(Modifier.height(10.dp))
            ReaderTypographySegmentedRow(
                icon = Icons.Outlined.FormatLineSpacing,
                title = stringResource(R.string.reader_line_height),
                options = spacingOptions,
                selectedIndex = spacingIndex,
                contentColor = contentColor,
                accentColor = accentColor,
                onSelect = { index ->
                    onSettingsChange { it.copy(readerLineHeight = spacingValues[index]) }
                },
            )
        }
    }
}

/** Material 3 segmented row: icon + label on the left, three-option segmented buttons on the right. */
@Composable
private fun ReaderTypographySegmentedRow(
    icon: ImageVector,
    title: String,
    options: List<String>,
    selectedIndex: Int,
    contentColor: Color,
    accentColor: Color,
    onSelect: (Int) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.width(96.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = contentColor.copy(alpha = 0.65f),
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Material 3 Expressive connected button group (the official single-select pattern):
        // connected ToggleButtons sharing one track, spacing from ButtonGroupDefaults.
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        ) {
            options.forEachIndexed { index, label ->
                ToggleButton(
                    checked = index == selectedIndex,
                    onCheckedChange = { checked -> if (checked) onSelect(index) },
                    shapes = when {
                        options.size == 1 -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                        index == 0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        index == options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                    modifier = Modifier.weight(1f),
                    // 以当前纸张的墨色为主：未选中是 10% 前景色的浅底，选中是实心墨色胶囊，
                    // 这样在纯白/纯黑/米色纸张下都不会出现突兀的撞色块。
                    colors = ToggleButtonDefaults.colors(
                        containerColor = contentColor.copy(alpha = 0.10f),
                        contentColor = contentColor.copy(alpha = 0.78f),
                        checkedContainerColor = contentColor,
                        checkedContentColor = if (contentColor.luminance() > 0.5f) {
                            Color(0xFF1C1A18)
                        } else {
                            Color.White
                        },
                    ),
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** One entry of the chapter list; multiple spine chapters can collapse into one entry. */
internal data class ReaderTocEntry(val chapterIndex: Int, val title: String)

/**
 * Builds the contents list from sanitized chapters.
 *
 * Rules:
 * - chapters without a title fall back to "chapter N" so every spine item stays reachable;
 * - adjacent entries with the same normalized label are merged (many EPUBs point both the
 *   illustration page and the text page at the same chapter heading). The first chapter wins, so
 *   jumping to a chapter starts at its illustration page.
 */
internal fun buildReaderToc(
    chapters: List<EbookChapter>,
    fallbackLabel: (Int) -> String,
): List<ReaderTocEntry> {
    val entries = ArrayList<ReaderTocEntry>(chapters.size)
    fun append(index: Int) {
        val chapter = chapters[index]
        val title = chapter.title?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackLabel(index)
        val normalized = title.filterNot { it.isWhitespace() }
        val lastNormalized = entries.lastOrNull()?.title?.filterNot { it.isWhitespace() }
        if (entries.isNotEmpty() && lastNormalized == normalized) return
        entries += ReaderTocEntry(index, title)
    }
    for (index in chapters.indices) {
        val chapter = chapters[index]
        // Unnamed illustration/colour pages are part of a chapter, not chapters of their own:
        // showing "chapter N" for them makes the contents list misleading (WeChat Reading hides them).
        if (chapter.title.isNullOrBlank() && chapter.imageOnly) continue
        append(index)
    }
    // A purely visual book (or a legacy cache without titles) must still have a usable contents list.
    if (entries.isEmpty()) {
        for (index in chapters.indices) append(index)
    }
    return entries
}

/** Highlight target for [currentChapter]: the closest contents entry at or before it. */
private fun List<ReaderTocEntry>.highlightedEntry(currentChapter: Int): ReaderTocEntry? =
    lastOrNull { it.chapterIndex <= currentChapter } ?: firstOrNull()

/**
 * Chapter list sheet (WeChat Reading "目录"). Selecting an entry jumps to that chapter; the entry
 * covering the current reading position is highlighted.
 */
@Composable
internal fun ReaderTocPanel(
    chapters: List<EbookChapter>,
    currentChapter: Int,
    onSelect: (Int) -> Unit,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val fallback = stringResource(R.string.reader_toc_chapter_fallback)
    val entries = remember(chapters, fallback) {
        buildReaderToc(chapters) { index -> String.format(Locale.US, fallback, index + 1) }
    }
    val highlighted = entries.highlightedEntry(currentChapter)
    val highlightedIndex = entries.indexOf(highlighted).coerceAtLeast(0)
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = (highlightedIndex - 2).coerceAtLeast(0),
    )
    val density = LocalDensity.current
    val rowHeightPx = with(density) { 42.dp.toPx() }
    val viewportHeight = 160.dp
    val viewportPx = with(density) { viewportHeight.toPx() }
    // Part of the same flat surface as the toolbar: no sheet, no separate entrance animation.
    ReaderPanelSurface(containerColor) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.reader_toc_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = contentColor,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(R.string.reader_toc_count, entries.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = 0.6f),
                )
            }
            Box(Modifier.fillMaxWidth().height(viewportHeight)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(entries, key = { it.chapterIndex }) { entry ->
                        val isCurrent = entry == highlighted
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 42.dp)
                                .background(contentColor.copy(alpha = if (isCurrent) 0.08f else 0f))
                                .clickable { onSelect(entry.chapterIndex) }
                                .padding(horizontal = 20.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                entry.title,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isCurrent) contentColor else contentColor.copy(alpha = 0.78f),
                                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (isCurrent) {
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    stringResource(R.string.reader_toc_current),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = contentColor.copy(alpha = 0.6f),
                                )
                            }
                        }
                    }
                }
                // Slim always-visible scrollbar: makes it obvious that the chapter list scrolls.
                val contentPx = entries.size.toFloat() * rowHeightPx
                if (entries.size > 4 && contentPx > viewportPx) {
                    val thumbFraction = (viewportPx / contentPx).coerceIn(0.12f, 1f)
                    val thumbPx = viewportPx * thumbFraction
                    val maxScrollPx = (contentPx - viewportPx).coerceAtLeast(1f)
                    val scrolledPx = (
                        listState.firstVisibleItemIndex * rowHeightPx +
                            listState.firstVisibleItemScrollOffset
                        ).coerceIn(0f, maxScrollPx)
                    val thumbOffsetPx = (scrolledPx / maxScrollPx) * (viewportPx - thumbPx)
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(end = 4.dp)
                            .width(4.dp)
                            .height(with(density) { thumbPx.toDp() })
                            .offset(y = with(density) { thumbOffsetPx.toDp() })
                            .background(contentColor.copy(alpha = 0.38f), RoundedCornerShape(2.dp)),
                    )
                }
            }
        }
    }
}

/** Row of round paper-colour swatches (auto / white / beige / green / black / custom). */
@Composable
private fun ReaderThemeRow(
    settings: AppSettings,
    onSettingsChange: ((AppSettings) -> AppSettings) -> Unit,
    onCustomBackground: () -> Unit,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    accentColor: Color = MaterialTheme.colorScheme.primary,
) {
    val options = listOf(
        ReaderBackground.AUTO to stringResource(R.string.reader_background_auto),
        ReaderBackground.SEPIA to stringResource(R.string.reader_background_sepia),
        ReaderBackground.WHITE to stringResource(R.string.reader_background_white),
        ReaderBackground.INK to stringResource(R.string.reader_background_ink),
        ReaderBackground.CUSTOM to stringResource(R.string.reader_background_custom),
    )
    // 大屏（平板/折叠屏）上不要让色板铺满整个菜单栏：限制最大宽度并居中，两侧留出 margin。
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
    Row(
        Modifier
            .widthIn(max = 470.dp)
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        options.forEach { (background, label) ->
            val swatchColor = when (background) {
                ReaderBackground.AUTO -> MaterialTheme.colorScheme.surface
                ReaderBackground.WHITE -> Color.White
                ReaderBackground.SEPIA -> Color(0xFFF5EFE2)
                ReaderBackground.GREEN -> Color(0xFFE7EFE4)
                ReaderBackground.INK -> Color.Black
                ReaderBackground.CUSTOM -> Color(settings.customBackgroundColor)
            }
            val selected = settings.readerBackground == background
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 100% 圆角矩形（胶囊形）色块：四个纸张选项等宽排列。
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(CircleShape)
                        .background(swatchColor)
                        .border(
                            width = if (selected) 2.dp else 1.dp,
                            color = if (selected) {
                                accentColor
                            } else {
                                contentColor.copy(alpha = 0.25f)
                            },
                            shape = CircleShape,
                        )
                        .clickable {
                            if (background == ReaderBackground.CUSTOM) onCustomBackground()
                            else onSettingsChange { it.copy(readerBackground = background) }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = if (swatchColor.luminance() > 0.5f) Color.Black else Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) accentColor else contentColor.copy(alpha = 0.65f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
    }
}
