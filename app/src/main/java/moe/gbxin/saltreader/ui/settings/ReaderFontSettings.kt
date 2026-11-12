package moe.gbxin.saltreader.ui.settings

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.gbxin.saltreader.R
import moe.gbxin.saltreader.SaltReaderApplication
import moe.gbxin.saltreader.data.AppSettings
import moe.gbxin.saltreader.data.ReaderFontRef
import moe.gbxin.saltreader.data.ReaderFontRole
import moe.gbxin.saltreader.data.ReaderFontSource
import moe.gbxin.saltreader.data.ReaderFonts
import moe.gbxin.saltreader.data.takePersistableReadPermission

/**
 * 字体设置页：管理用户字体目录，并分别为"自定义阅读字体 / 默认黑体 / 默认宋体 / 默认等宽"
 * 指定字体。字体目录是唯一的（一个持久化 SAF 目录树），其直接子文件里的 ttf/otf 等会被列出。
 */
class ReaderFontSettingsActivity : BaseSettingsActivity() {
    @Composable
    override fun Content(onBack: () -> Unit) = ReaderFontSettingsPage(onBack)
}

/** 字体选择页：从系统字体或用户字体目录里挑一款，结果写回对应角色。 */
class ReaderFontPickerActivity : BaseSettingsActivity() {
    @Composable
    override fun Content(onBack: () -> Unit) {
        val role = remember {
            intent.getStringExtra(EXTRA_FONT_ROLE)
                ?.let { raw -> runCatching { ReaderFontRole.valueOf(raw) }.getOrNull() }
                ?: ReaderFontRole.CUSTOM
        }
        ReaderFontPickerPage(role = role, onBack = onBack)
    }

    companion object {
        const val EXTRA_FONT_ROLE = "moe.gbxin.saltreader.extra.FONT_ROLE"
    }
}

@Composable
private fun ReaderFontSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as SaltReaderApplication
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val directoryUri = settings.readerFontDirectoryUri
    val directoryFonts by produceState(initialValue = emptyList<ReaderFontRef>(), directoryUri) {
        value = withContext(Dispatchers.IO) { ReaderFonts.directoryFonts(context, directoryUri) }
    }
    val directoryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        takePersistableReadPermission(context, uri)
        // 目录名要查 provider，不能读在主线程；拿到后再更新设置。
        scope.launch {
            val name = withContext(Dispatchers.IO) { ReaderFonts.directoryName(context, uri.toString()) }
            app.settings.update { it.withFontDirectory(uri.toString(), name ?: uri.lastPathSegment) }
        }
    }
    SettingsScaffold(stringResource(R.string.reader_font_settings_title), onBack) { innerPadding ->
        Column(
            Modifier.fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 32.dp + navigationBarBottomPadding()),
        ) {
            // 与"存储设置"一致：卡片里一个添加入口 + 已选文件夹行（名称/URI/删除），
            // 分隔线使用 outlineVariant，未选择时给出空状态文案。
            SettingsSectionLabel(stringResource(R.string.reader_font_section_directory))
            SettingsCard {
                SettingsActionRow(
                    icon = Icons.Outlined.FolderOpen,
                    title = stringResource(R.string.reader_font_directory_choose),
                    enabled = true,
                ) { directoryLauncher.launch(null) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (directoryUri == null) {
                    Text(
                        stringResource(R.string.reader_font_directory_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp),
                    )
                } else {
                    SettingsFolderRow(
                        displayName = settings.readerFontDirectoryLabel
                            ?: directoryUri.substringAfterLast('/'),
                        treeUri = directoryUri,
                        enabled = true,
                        removeDescription = stringResource(R.string.reader_font_directory_clear),
                    ) { app.settings.update { it.withFontDirectory(null, null) } }
                }
            }
            if (directoryUri != null) {
                Text(
                    stringResource(R.string.reader_font_directory_count, directoryFonts.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 12.dp),
                )
            }
            ReaderFontFormatNotice()
            if (directoryUri != null && directoryFonts.isEmpty()) {
                Text(
                    stringResource(R.string.reader_font_directory_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                )
            }

            SettingsSectionLabel(stringResource(R.string.reader_font_section_custom))
            SettingsCard {
                ReaderFontEntryRow(
                    icon = Icons.Outlined.TextFields,
                    title = stringResource(R.string.reader_font_read_font),
                    value = settings.readerCustomFont?.label,
                ) { context.startPicker(ReaderFontRole.CUSTOM) }
            }

            SettingsSectionLabel(stringResource(R.string.reader_font_section_custom_defaults))
            SettingsCard {
                ReaderFontEntryRow(
                    icon = Icons.Outlined.TextFields,
                    title = stringResource(R.string.reader_font_default_hei),
                    value = settings.readerFontHei?.label,
                ) { context.startPicker(ReaderFontRole.HEI) }
                ReaderFontEntryRow(
                    icon = Icons.Outlined.FormatSize,
                    title = stringResource(R.string.reader_font_default_song),
                    value = settings.readerFontSong?.label,
                ) { context.startPicker(ReaderFontRole.SONG) }
                ReaderFontEntryRow(
                    icon = Icons.Outlined.Code,
                    title = stringResource(R.string.reader_font_default_mono),
                    value = settings.readerFontMono?.label,
                ) { context.startPicker(ReaderFontRole.MONO) }
            }
        }
    }
}

/**
 * 字体选择页。用 Material 3 的 ListItem 组织成"分组列表"：字体样张放 leading，
 * 选中行整只胶囊换 secondaryContainer，分隔靠间距而不是横线。
 */
@Composable
private fun ReaderFontPickerPage(role: ReaderFontRole, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as SaltReaderApplication
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    val systemFonts by produceState(initialValue = emptyList<ReaderFontRef>()) {
        value = withContext(Dispatchers.IO) { ReaderFonts.systemFonts() }
    }
    val directoryUri = settings.readerFontDirectoryUri
    val directoryFonts by produceState(initialValue = emptyList<ReaderFontRef>(), directoryUri) {
        value = withContext(Dispatchers.IO) { ReaderFonts.directoryFonts(context, directoryUri) }
    }
    val current = role.currentFont(settings)
    val filter = { ref: ReaderFontRef ->
        query.isBlank() || ref.label.contains(query.trim(), ignoreCase = true)
    }
    val visibleSystemFonts = systemFonts.filter(filter)
    val visibleDirectoryFonts = directoryFonts.filter(filter)
    val pick: (ReaderFontRef?) -> Unit = { ref ->
        app.settings.update { it.withRoleFont(role, ref) }
        onBack()
    }
    val directorySources = stringResource(R.string.reader_font_picker_added)

    SettingsScaffold(stringResource(role.pickerTitleRes()), onBack) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 4.dp,
                bottom = 32.dp + navigationBarBottomPadding(),
            ),
        ) {
            item {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    trailingIcon = if (query.isEmpty()) {
                        null
                    } else {
                        {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Filled.Close, contentDescription = null)
                            }
                        }
                    },
                    placeholder = { Text(stringResource(R.string.reader_font_picker_search)) },
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                SettingsSectionLabel(stringResource(R.string.reader_font_section_defaults))
                ReaderFontListItem(
                    label = stringResource(
                        if (role == ReaderFontRole.CUSTOM) {
                            R.string.reader_font_follow_hei
                        } else {
                            R.string.reader_font_use_builtin
                        },
                    ),
                    supporting = if (role == ReaderFontRole.CUSTOM) {
                        stringResource(R.string.reader_font_use_builtin)
                    } else {
                        null
                    },
                    selected = current == null,
                    typeface = null,
                    leading = Icons.Outlined.TextFields,
                    onClick = { pick(null) },
                )
            }

            // 字体目录统一在"字体设置"里管理，选择页只列出目录里的字体，不再提供选目录入口。
            item { SettingsSectionLabel(stringResource(R.string.reader_font_section_directory)) }
            if (directoryUri == null) {
                item {
                    ReaderFontNotice(
                        icon = Icons.Outlined.FolderOpen,
                        text = stringResource(R.string.reader_font_picker_no_directory),
                    )
                }
            } else if (visibleDirectoryFonts.isEmpty()) {
                item {
                    Text(
                        stringResource(
                            if (directoryFonts.isEmpty()) {
                                R.string.reader_font_directory_empty
                            } else {
                                R.string.reader_font_picker_empty
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            } else {
                items(visibleDirectoryFonts, key = { "dir:" + it.id }) { ref ->
                    val typeface = rememberFontTypeface(ref)
                    ReaderFontListItem(
                        label = ref.label,
                        supporting = directorySources,
                        selected = current?.id == ref.id,
                        typeface = typeface,
                        onClick = { pick(ref) },
                    )
                }
            }

            item { SettingsSectionLabel(stringResource(R.string.reader_font_picker_system)) }
            if (visibleSystemFonts.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.reader_font_picker_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            } else {
                items(visibleSystemFonts, key = { "sys:" + it.id }) { ref ->
                    val typeface = rememberFontTypeface(ref)
                    ReaderFontListItem(
                        label = ref.label,
                        supporting = null,
                        selected = current?.id == ref.id,
                        typeface = typeface,
                        onClick = { pick(ref) },
                    )
                }
            }

            item { Spacer(Modifier.size(8.dp)) }
        }
    }
}

/**
 * 字体格式说明：WebView 的字体消毒器（OTS）会拒绝字体集合，因此 .ttc 无法通过 @font-face
 * 使用。这里用 M3 的"图标 + 说明文字"排版把限制讲清楚，而不是藏在提示行里。
 */
@Composable
private fun ReaderFontFormatNotice() {
    ReaderFontNotice(
        icon = Icons.Outlined.Info,
        text = stringResource(R.string.reader_font_ttc_notice),
    )
}

/**
 * 设置类提示：40dp 的 Material 圆形图标 + 说明文字。左边缘与卡片内的图标对齐
 * （16dp 页面边距 + 16dp 卡片内边距），文字与行标题同一列。
 */
@Composable
private fun ReaderFontNotice(icon: ImageVector, text: String) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        SettingsIcon(icon)
        Spacer(Modifier.width(16.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

/** 设置卡片里的"标题 + 当前值 + 箭头"行，点进字体选择页。 */
@Composable
internal fun ReaderFontEntryRow(
    icon: ImageVector,
    title: String,
    value: String?,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsIcon(icon)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            // 只有真正选过字体才显示字体名；未选择时不显示"跟随黑体/内置系统字体"这类副标题。
            if (value != null) {
                Text(
                    value,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
    }
}

/**
 * 字体列表行（M3 ListItem）：leading 用真实字形样张，选中整行填 secondaryContainer，
 * trailing 只给选中项一个勾。点击即选，返回上一页。
 */
@Composable
private fun ReaderFontListItem(
    label: String,
    supporting: String?,
    selected: Boolean,
    typeface: Typeface?,
    leading: ImageVector? = null,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = supporting?.let {
            { Text(it, style = MaterialTheme.typography.bodySmall) }
        },
        leadingContent = {
            ReaderFontSample(typeface = typeface, icon = leading)
        },
        trailingContent = if (selected) {
            {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            null
        },
        colors = ListItemDefaults.colors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                Color.Transparent
            },
            headlineColor = if (selected) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            supportingColor = if (selected) {
                MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f)
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .clickable(role = Role.RadioButton, onClick = onClick),
    )
}

/** 字体样张：有 Typeface 时直接用该字体的字形渲染 "Aa"，否则显示图标。 */
@Composable
private fun ReaderFontSample(typeface: Typeface?, icon: ImageVector?) {
    Box(
        modifier = Modifier.size(44.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (typeface != null) {
            Text(
                "Aa",
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily(typeface),
                maxLines = 1,
                softWrap = false,
            )
        } else if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * 用真实字体渲染样张。系统字体直接 Typeface.create；目录字体通过 SAF 文件描述符
 * 映射（Typeface.Builder），不复制文件、也不阻塞主线程。
 */
@Composable
private fun rememberFontTypeface(ref: ReaderFontRef): Typeface? {
    val context = LocalContext.current
    val typeface by produceState<Typeface?>(initialValue = null, ref) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                when (ref.source) {
                    ReaderFontSource.SYSTEM -> Typeface.create(ref.key, Typeface.NORMAL)
                    ReaderFontSource.DOCUMENT -> {
                        context.contentResolver.openFileDescriptor(Uri.parse(ref.key), "r")
                            ?.use { descriptor -> Typeface.Builder(descriptor.fileDescriptor).build() }
                    }
                }
            }.getOrNull()
        }
    }
    return typeface
}

private fun ReaderFontRole.currentFont(settings: AppSettings): ReaderFontRef? = when (this) {
    ReaderFontRole.CUSTOM -> settings.readerCustomFont
    ReaderFontRole.HEI -> settings.readerFontHei
    ReaderFontRole.SONG -> settings.readerFontSong
    ReaderFontRole.MONO -> settings.readerFontMono
}

private fun AppSettings.withRoleFont(role: ReaderFontRole, ref: ReaderFontRef?): AppSettings = when (role) {
    ReaderFontRole.CUSTOM -> copy(readerCustomFont = ref)
    ReaderFontRole.HEI -> copy(readerFontHei = ref)
    ReaderFontRole.SONG -> copy(readerFontSong = ref)
    ReaderFontRole.MONO -> copy(readerFontMono = ref)
}

/**
 * 更换/清除字体目录。目录里的字体用 Document URI 引用，目录一旦变化旧引用就失效，
 * 所以指向旧目录的字体选择要一并清空（系统字体引用不受影响）。
 */
private fun AppSettings.withFontDirectory(uri: String?, label: String?): AppSettings {
    if (uri == readerFontDirectoryUri && label == readerFontDirectoryLabel) {
        return copy(readerFontDirectoryUri = uri, readerFontDirectoryLabel = label)
    }
    return copy(
        readerFontDirectoryUri = uri,
        readerFontDirectoryLabel = label,
        readerCustomFont = readerCustomFont.keepSystemOnly(),
        readerFontHei = readerFontHei.keepSystemOnly(),
        readerFontSong = readerFontSong.keepSystemOnly(),
        readerFontMono = readerFontMono.keepSystemOnly(),
    )
}

private fun ReaderFontRef?.keepSystemOnly(): ReaderFontRef? =
    if (this?.source == ReaderFontSource.DOCUMENT) null else this

private fun ReaderFontRole.pickerTitleRes(): Int = when (this) {
    ReaderFontRole.CUSTOM -> R.string.reader_font_pick_custom
    ReaderFontRole.HEI -> R.string.reader_font_pick_hei
    ReaderFontRole.SONG -> R.string.reader_font_pick_song
    ReaderFontRole.MONO -> R.string.reader_font_pick_mono
}

private fun Context.startPicker(role: ReaderFontRole) {
    startActivity(
        Intent(this, ReaderFontPickerActivity::class.java)
            .putExtra(ReaderFontPickerActivity.EXTRA_FONT_ROLE, role.name),
    )
}
