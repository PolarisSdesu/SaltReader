package moe.gbxin.saltreader.ui.settings

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FilterBAndW
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Numbers
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.gbxin.saltreader.BuildConfig
import moe.gbxin.saltreader.SaltReaderApplication
import moe.gbxin.saltreader.data.ImportReport
import moe.gbxin.saltreader.data.LibraryRoot
import moe.gbxin.saltreader.data.ReaderBackground
import moe.gbxin.saltreader.data.ReaderMode
import moe.gbxin.saltreader.data.ReadingDirection
import moe.gbxin.saltreader.data.ThemeMode
import moe.gbxin.saltreader.ui.components.CustomColorPickerDialog
import moe.gbxin.saltreader.ui.theme.SaltReaderTheme
import moe.gbxin.saltreader.util.formatBytes

abstract class BaseSettingsActivity : ComponentActivity() {
    @Composable
    protected abstract fun Content(onBack: () -> Unit)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as SaltReaderApplication
        setContent {
            val settings by app.settings.state.collectAsStateWithLifecycle()
            SaltReaderTheme(themeMode = settings.themeMode, dynamicColor = settings.dynamicColor, amoledBlack = settings.amoledBlack) {
                Content { finish() }
            }
        }
    }
}

class SettingsActivity : BaseSettingsActivity() {
    @Composable
    override fun Content(onBack: () -> Unit) = SettingsHomePage(onBack)
}

class AppearanceSettingsActivity : BaseSettingsActivity() {
    @Composable
    override fun Content(onBack: () -> Unit) = AppearanceSettingsPage(onBack)
}

class ReaderSettingsActivity : BaseSettingsActivity() {
    @Composable
    override fun Content(onBack: () -> Unit) = ReaderSettingsPage(onBack)
}

class StorageSettingsActivity : BaseSettingsActivity() {
    @Composable
    override fun Content(onBack: () -> Unit) = StorageSettingsPage(onBack)
}

class AboutSettingsActivity : BaseSettingsActivity() {
    @Composable
    override fun Content(onBack: () -> Unit) = AboutSettingsPage(onBack)
}

/**
 * Expressive settings scaffold: a large flexible top app bar (the title collapses into a single
 * row while scrolling) over a token-colored background.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SettingsScaffold(
    title: String,
    onBack: () -> Unit,
    snackbarHost: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val topBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(topBarState)
    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = snackbarHost,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        content(innerPadding)
    }
}

@Composable
private fun SettingsHomePage(onBack: () -> Unit) {
    val context = LocalContext.current
    SettingsScaffold("设置", onBack) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
        ) {
            item { SettingsSectionLabel("个性化") }
            item {
                SettingsCard {
                    SettingsNavigateRow(
                        icon = Icons.Outlined.Palette,
                        title = "外观",
                    ) { context.startActivity(Intent(context, AppearanceSettingsActivity::class.java)) }
                    SettingsNavigateRow(
                        icon = Icons.AutoMirrored.Outlined.MenuBook,
                        title = "阅读器",
                    ) { context.startActivity(Intent(context, ReaderSettingsActivity::class.java)) }
                }
            }
            item { SettingsSectionLabel("数据") }
            item {
                SettingsCard {
                    SettingsNavigateRow(
                        icon = Icons.Outlined.Storage,
                        title = "存储",
                    ) { context.startActivity(Intent(context, StorageSettingsActivity::class.java)) }
                }
            }
            item { SettingsSectionLabel("关于") }
            item {
                SettingsCard {
                    SettingsNavigateRow(
                        icon = Icons.Outlined.Info,
                        title = "关于 Salt Reader",
                    ) { context.startActivity(Intent(context, AboutSettingsActivity::class.java)) }
                }
            }
        }
    }
}

@Composable
private fun AppearanceSettingsPage(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as SaltReaderApplication
    val settings by app.settings.state.collectAsStateWithLifecycle()
    SettingsScaffold("外观", onBack) { innerPadding ->
        Column(
            Modifier.fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
        ) {
            SettingsSectionLabel("主题")
            SettingsCard {
                SettingsChoiceGroup(
                    title = "主题模式",
                    options = ThemeMode.entries.toList(),
                    selected = settings.themeMode,
                    label = { it.label() },
                ) { mode -> app.settings.update { it.copy(themeMode = mode) } }
            }
            SettingsSectionLabel("颜色")
            SettingsCard {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    SettingsSwitchRow(
                        icon = Icons.Outlined.Palette,
                        title = "动态取色",
                        supporting = "仅 Android 12+",
                        checked = settings.dynamicColor,
                    ) { app.settings.update { it.copy(dynamicColor = it.dynamicColor.not()) } }
                }
                SettingsSwitchRow(
                    icon = Icons.Outlined.DarkMode,
                    title = "纯黑模式",
                    supporting = "深色下使用 AMOLED 纯黑",
                    checked = settings.amoledBlack,
                ) { app.settings.update { it.copy(amoledBlack = it.amoledBlack.not()) } }
            }
        }
    }
}

@Composable
private fun ReaderSettingsPage(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as SaltReaderApplication
    val settings by app.settings.state.collectAsStateWithLifecycle()
    var showCustomBackground by remember { mutableStateOf(false) }
    SettingsScaffold("阅读器", onBack) { innerPadding ->
        Column(
            Modifier.fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
        ) {
            SettingsSectionLabel("布局")
            SettingsCard {
                SettingsChoiceGroup(
                    title = "阅读模式",
                    options = ReaderMode.entries.toList(),
                    selected = settings.readerMode,
                    label = { it.label() },
                ) { mode -> app.settings.update { it.copy(readerMode = mode) } }
                SettingsChoiceGroup(
                    title = "翻页方向",
                    options = ReadingDirection.entries.toList(),
                    selected = settings.readingDirection,
                    label = { it.label() },
                ) { direction -> app.settings.update { it.copy(readingDirection = direction) } }
            }
            SettingsSectionLabel("显示")
            SettingsCard {
                SettingsChoiceGroup(
                    title = "纸张颜色",
                    options = listOf(
                        ReaderBackground.WHITE,
                        ReaderBackground.INK,
                        ReaderBackground.CUSTOM,
                    ),
                    selected = settings.readerBackground,
                    label = { it.label() },
                ) { background ->
                    if (background == ReaderBackground.CUSTOM) {
                        showCustomBackground = true
                    } else {
                        app.settings.update { it.copy(readerBackground = background) }
                    }
                }
                SettingsSwitchRow(
                    icon = Icons.Outlined.Numbers,
                    title = "显示页码",
                        supporting = "在页面底部显示当前页码",
                    checked = settings.showPageNumber,
                ) { app.settings.update { it.copy(showPageNumber = it.showPageNumber.not()) } }
                SettingsSwitchRow(
                    icon = Icons.Outlined.LightMode,
                    title = "保持屏幕常亮",
                        supporting = "阅读时屏幕不会自动熄灭",
                    checked = settings.keepScreenOn,
                ) { app.settings.update { it.copy(keepScreenOn = it.keepScreenOn.not()) } }
                SettingsSwitchRow(
                    icon = Icons.AutoMirrored.Outlined.VolumeUp,
                    title = "音量键翻页",
                        supporting = "音量键切换上一页 / 下一页",
                    checked = settings.volumeKeyPaging,
                ) { app.settings.update { it.copy(volumeKeyPaging = it.volumeKeyPaging.not()) } }
            }
            SettingsSectionLabel("滤镜")
            SettingsCard {
                SettingsSwitchRow(
                    icon = Icons.Outlined.FilterBAndW,
                    title = "灰度模式",
                        supporting = "去除彩色",
                    checked = settings.grayscale,
                ) { app.settings.update { it.copy(grayscale = it.grayscale.not()) } }
                SettingsSwitchRow(
                    icon = Icons.Outlined.Contrast,
                    title = "反色",
                        supporting = "适合夜间阅读",
                    checked = settings.invertColors,
                ) { app.settings.update { it.copy(invertColors = it.invertColors.not()) } }
            }
            if (showCustomBackground) {
                CustomColorPickerDialog(
                    initialColor = settings.customBackgroundColor,
                    onDismiss = { showCustomBackground = false },
                    onConfirm = { color ->
                        showCustomBackground = false
                        app.settings.update {
                            it.copy(readerBackground = ReaderBackground.CUSTOM, customBackgroundColor = color)
                        }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StorageSettingsPage(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as SaltReaderApplication
    val scope = rememberCoroutineScope()
    val books by app.library.books.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    // Root list is loaded off the composition thread; the repository keeps it as a small cache.
    var roots by remember { mutableStateOf<List<LibraryRoot>>(emptyList()) }
    var appDataBytes by remember { mutableStateOf(0L) }
    var scanning by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var scanProgress by remember { mutableFloatStateOf(0f) }
    var scanLabel by remember { mutableStateOf("") }
    var scanMessage by remember { mutableStateOf<String?>(null) }
    var scanMessageIsError by remember { mutableStateOf(false) }

    suspend fun reloadStorage() {
        // roots() is a cheap cache read, but appDataBytes() walks the cache tree; both stay off
        // the main thread so opening the page never janks.
        val snapshot = withContext(Dispatchers.IO) {
            app.library.roots() to app.library.appDataBytes()
        }
        roots = snapshot.first
        appDataBytes = snapshot.second
    }

    LaunchedEffect(Unit) { reloadStorage() }

    fun launchScan(scan: suspend ((Float, String) -> Unit) -> ImportReport) {
        if (scanning) return
        scanning = true
        scanProgress = 0f
        scanLabel = "正在扫描…"
        scanMessage = null
        scanMessageIsError = false
        scope.launch {
            try {
                val report = scan { progress, label ->
                    scanProgress = progress
                    scanLabel = label
                }
                reloadStorage()
                scanMessage = when {
                    report.count > 0 -> "已索引 ${report.count} 本"
                    report.hasFailures -> report.failures.first().reason
                    else -> "没有找到漫画"
                }
                scanMessageIsError = report.count == 0 && report.hasFailures
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                scanMessage = "扫描失败：${e.message ?: "未知错误"}"
                scanMessageIsError = true
            } finally {
                scanning = false
            }
        }
    }

    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) launchScan { progress -> app.library.importLibraryTree(uri, progress) }
    }

    fun removeRoot(root: LibraryRoot) {
        if (scanning) return
        scope.launch {
            try {
                app.library.removeRoot(root.treeUri)
                reloadStorage()
                scanMessage = "已移除「${root.displayName}」及其缓存"
                scanMessageIsError = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                scanMessage = "移除失败：${e.message ?: "未知错误"}"
                scanMessageIsError = true
            }
        }
    }

    fun clearCaches() {
        if (clearing || scanning) return
        scope.launch {
            clearing = true
            try {
                // clearCaches walks the cache tree and deletes files, so it runs off the main thread.
                val freed = withContext(Dispatchers.IO) { app.library.clearCaches() }
                reloadStorage()
                snackbar.showSnackbar(
                    if (freed > 0) {
                        "缓存已清理，释放 ${formatBytes(freed)}；电子书会重新转换"
                    } else {
                        "没有可清理的缓存"
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                snackbar.showSnackbar("清理失败：${e.message ?: "未知错误"}")
            } finally {
                clearing = false
            }
        }
    }

    SettingsScaffold("存储", onBack, snackbarHost = { SnackbarHost(snackbar) }) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
        ) {
            item { SettingsSectionLabel("书库索引") }
            item {
                SettingsCard {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${books.size} 本书", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "${books.sumOf { it.pageCount }} 页已索引 · 应用数据 ${formatBytes(appDataBytes)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            FilledTonalButton(
                                enabled = !scanning && roots.isNotEmpty(),
                                onClick = { launchScan { p -> app.library.refreshLibrary(p) } },
                            ) { Text(if (scanning) "刷新中…" else "刷新索引") }
                        }
                        if (roots.isEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "还没有添加文件夹，先添加一个漫画目录。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (scanning) {
                            Spacer(Modifier.height(16.dp))
                            LinearWavyProgressIndicator(
                                progress = { scanProgress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                scanLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        scanMessage?.let { message ->
                            Spacer(Modifier.height(10.dp))
                            Text(
                                message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (scanMessageIsError) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                            )
                        }
                    }
                }
            }
            item { SettingsSectionLabel("目录") }
            item {
                SettingsCard {
                    SettingsActionRow(
                        icon = Icons.Outlined.FolderOpen,
                        title = "添加文件夹",
                        supporting = "通过系统文件选择器授权目录，只建立索引，不复制或移动原文件",
                        enabled = !scanning,
                    ) { folderLauncher.launch(null) }
                }
            }
            item { SettingsSectionLabel("已添加的文件夹") }
            if (roots.isEmpty()) {
                item {
                    SettingsCard {
                        Text(
                            "还没有添加文件夹",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                        )
                    }
                }
            } else {
                item {
                    SettingsCard {
                        roots.forEach { root ->
                            SettingsFolderRow(
                                displayName = root.displayName,
                                treeUri = root.treeUri,
                                enabled = !scanning,
                                onRemove = { removeRoot(root) },
                            )
                        }
                    }
                }
                item {
                    Text(
                        "移除目录只会删除书架索引与封面缓存，不会删除你的原文件。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
                    )
                }
            }
            item { SettingsSectionLabel("缓存") }
            item {
                SettingsCard {
                    SettingsActionRow(
                        icon = Icons.Outlined.Delete,
                        title = "清理缓存",
                        supporting = if (clearing) {
                            "正在清理…"
                        } else {
                            "当前占用 ${formatBytes(appDataBytes)}；保留封面，不影响原文件"
                        },
                        enabled = !clearing && !scanning,
                    ) { clearCaches() }
                }
            }
        }
    }
}

@Composable
private fun AboutSettingsPage(onBack: () -> Unit) {
    SettingsScaffold("关于", onBack) { innerPadding ->
        Column(
            Modifier.fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
        ) {
            SettingsSectionLabel("应用")
            SettingsCard {
                SettingsInfoRow("版本", BuildConfig.VERSION_NAME)
                SettingsInfoRow("包名", BuildConfig.APPLICATION_ID)
            }
        }
    }
}

@Composable
private fun SettingsSectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp).semantics { heading() },
    )
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(content = content)
    }
}

@Composable
private fun SettingsIcon(icon: ImageVector) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun SettingsNavigateRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsIcon(icon)
        Spacer(Modifier.width(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun SettingsSwitchRow(
    icon: ImageVector,
    title: String,
    checked: Boolean,
    supporting: String? = null,
    onToggle: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = { onToggle() })
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsIcon(icon)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (supporting != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun SettingsActionRow(
    icon: ImageVector,
    title: String,
    enabled: Boolean,
    supporting: String? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .alpha(if (enabled) 1f else 0.38f)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsIcon(icon)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (supporting != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SettingsFolderRow(
    displayName: String,
    treeUri: String,
    enabled: Boolean,
    onRemove: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsIcon(Icons.Outlined.FolderOpen)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                treeUri,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(enabled = enabled, onClick = onRemove) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = "移除目录 $displayName",
            )
        }
    }
}

@Composable
private fun SettingsInfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.width(16.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

@Composable
private fun <T> SettingsChoiceGroup(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))
        // Equal-width segments with explicitly centered labels; ButtonGroup's checked item
        // reserves asymmetric inner space, which pushes the label off center.
        Row(
            modifier = Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { option ->
                val checked = option == selected
                Surface(
                    shape = CircleShape,
                    color = if (checked) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Box(
                        Modifier
                            .selectable(
                                selected = checked,
                                role = Role.RadioButton,
                                onClick = { onSelect(option) },
                            )
                            .padding(horizontal = 10.dp, vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label(option),
                            style = MaterialTheme.typography.labelLarge,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

private fun ThemeMode.label(): String = when (this) {
    ThemeMode.SYSTEM -> "跟随系统"
    ThemeMode.LIGHT -> "浅色"
    ThemeMode.DARK -> "深色"
}

private fun ReaderMode.label(): String = when (this) {
    ReaderMode.PAGED -> "横向翻页"
    ReaderMode.VERTICAL -> "纵向滚动"
}

private fun ReadingDirection.label(): String = when (this) {
    ReadingDirection.LTR -> "从左到右"
    ReadingDirection.RTL -> "从右到左"
}

private fun ReaderBackground.label(): String = when (this) {
    ReaderBackground.WHITE -> "纯白"
    ReaderBackground.INK -> "纯黑"
    ReaderBackground.CUSTOM -> "自定义"
}
