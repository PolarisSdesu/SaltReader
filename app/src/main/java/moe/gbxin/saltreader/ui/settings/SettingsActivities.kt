package moe.gbxin.saltreader.ui.settings


import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FilterBAndW
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Numbers
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpOffset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.gbxin.saltreader.BuildConfig
import moe.gbxin.saltreader.R
import moe.gbxin.saltreader.SaltReaderApplication
import moe.gbxin.saltreader.data.ImportReport
import moe.gbxin.saltreader.data.LibraryRoot
import moe.gbxin.saltreader.data.ReaderBackground
import moe.gbxin.saltreader.data.ReaderMode
import moe.gbxin.saltreader.data.ReadingDirection
import moe.gbxin.saltreader.data.ThemeMode
import moe.gbxin.saltreader.ui.LocalizedActivity
import moe.gbxin.saltreader.ui.components.CustomColorPickerDialog
import moe.gbxin.saltreader.ui.theme.SaltReaderTheme
import moe.gbxin.saltreader.util.LANGUAGE_EN
import moe.gbxin.saltreader.util.LANGUAGE_JA
import moe.gbxin.saltreader.util.LANGUAGE_SYSTEM
import moe.gbxin.saltreader.util.LANGUAGE_ZH_CN
import moe.gbxin.saltreader.util.LANGUAGE_ZH_TW
import moe.gbxin.saltreader.util.SUPPORTED_APP_LANGUAGES
import moe.gbxin.saltreader.util.currentAppLanguage
import moe.gbxin.saltreader.util.formatBytes
import moe.gbxin.saltreader.util.setAppLanguage

abstract class BaseSettingsActivity : LocalizedActivity() {
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

class LicensesSettingsActivity : BaseSettingsActivity() {
    @Composable
    override fun Content(onBack: () -> Unit) = LicensesSettingsPage(onBack)
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
        // The top app bar consumes the status bar inset itself; the body only needs to stay above
        // the gesture navigation bar, applied explicitly below.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = snackbarHost,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
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
    SettingsScaffold(stringResource(R.string.settings_title), onBack) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp + navigationBarBottomPadding()),
        ) {
            item { SettingsSectionLabel(stringResource(R.string.settings_home_section_personalization)) }
            item {
                SettingsCard {
                    SettingsNavigateRow(
                        icon = Icons.Outlined.Palette,
                        title = stringResource(R.string.settings_appearance_title),
                    ) { context.startActivity(Intent(context, AppearanceSettingsActivity::class.java)) }
                    SettingsNavigateRow(
                        icon = Icons.AutoMirrored.Outlined.MenuBook,
                        title = stringResource(R.string.settings_reader_title),
                    ) { context.startActivity(Intent(context, ReaderSettingsActivity::class.java)) }
                }
            }
            item { SettingsSectionLabel(stringResource(R.string.settings_home_section_data)) }
            item {
                SettingsCard {
                    SettingsNavigateRow(
                        icon = Icons.Outlined.Storage,
                        title = stringResource(R.string.settings_storage_title),
                    ) { context.startActivity(Intent(context, StorageSettingsActivity::class.java)) }
                }
            }
            item { SettingsSectionLabel(stringResource(R.string.settings_home_section_about)) }
            item {
                SettingsCard {
                    SettingsNavigateRow(
                        icon = Icons.Outlined.Description,
                        title = stringResource(R.string.settings_licenses_title),
                    ) { context.startActivity(Intent(context, LicensesSettingsActivity::class.java)) }
                    SettingsNavigateRow(
                        icon = Icons.Outlined.Info,
                        title = stringResource(R.string.settings_home_about),
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
    SettingsScaffold(stringResource(R.string.settings_appearance_title), onBack) { innerPadding ->
        Column(
            Modifier.fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 32.dp + navigationBarBottomPadding()),
        ) {
            SettingsSectionLabel(stringResource(R.string.settings_appearance_section_language))
            SettingsCard {
                val context = LocalContext.current
                // Recomputed on every composition so a locale configuration change updates the
                // summary without recreating the activity.
                val language = currentAppLanguage(context)
                var menuOpen by remember { mutableStateOf(false) }
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { menuOpen = true }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SettingsIcon(Icons.Outlined.Language)
                        Spacer(Modifier.width(16.dp))
                        Text(
                            stringResource(R.string.settings_appearance_display_language),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            stringResource(languageLabelRes(language)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Icon(
                            Icons.Filled.ArrowDropDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val menuWidth = 200.dp
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        // Align the popup with the row's trailing edge instead of its leading edge.
                        offset = DpOffset(x = (maxWidth - menuWidth).coerceAtLeast(0.dp), y = 0.dp),
                        modifier = Modifier.width(menuWidth),
                    ) {
                        SUPPORTED_APP_LANGUAGES.forEach { tag ->
                            DropdownMenuItem(
                                text = { Text(stringResource(languageLabelRes(tag))) },
                                leadingIcon = if (tag == language) {
                                    { Icon(Icons.Filled.Check, contentDescription = null) }
                                } else {
                                    null
                                },
                                onClick = {
                                    menuOpen = false
                                    setAppLanguage(context, tag)
                                },
                            )
                        }
                    }
                }
            }
            SettingsSectionLabel(stringResource(R.string.settings_appearance_section_theme))
            SettingsCard {
                var themeMenuOpen by remember { mutableStateOf(false) }
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { themeMenuOpen = true }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SettingsIcon(Icons.Outlined.LightMode)
                        Spacer(Modifier.width(16.dp))
                        Text(
                            stringResource(R.string.settings_appearance_theme_mode),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            stringResource(settings.themeMode.labelRes()),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Icon(
                            Icons.Filled.ArrowDropDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val menuWidth = 200.dp
                    DropdownMenu(
                        expanded = themeMenuOpen,
                        onDismissRequest = { themeMenuOpen = false },
                        offset = DpOffset(x = (maxWidth - menuWidth).coerceAtLeast(0.dp), y = 0.dp),
                        modifier = Modifier.width(menuWidth),
                    ) {
                        ThemeMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(stringResource(mode.labelRes())) },
                                leadingIcon = if (mode == settings.themeMode) {
                                    { Icon(Icons.Filled.Check, contentDescription = null) }
                                } else {
                                    null
                                },
                                onClick = {
                                    themeMenuOpen = false
                                    app.settings.update { it.copy(themeMode = mode) }
                                },
                            )
                        }
                    }
                }
            }
            SettingsSectionLabel(stringResource(R.string.settings_appearance_section_color))
            SettingsCard {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    SettingsSwitchRow(
                        icon = Icons.Outlined.Palette,
                        title = stringResource(R.string.settings_appearance_dynamic_color),
                        supporting = stringResource(R.string.settings_appearance_dynamic_color_supporting),
                        checked = settings.dynamicColor,
                    ) { app.settings.update { it.copy(dynamicColor = it.dynamicColor.not()) } }
                }
                SettingsSwitchRow(
                    icon = Icons.Outlined.DarkMode,
                    title = stringResource(R.string.settings_appearance_amoled),
                    supporting = stringResource(R.string.settings_appearance_amoled_supporting),
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
    SettingsScaffold(stringResource(R.string.settings_reader_title), onBack) { innerPadding ->
        Column(
            Modifier.fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 32.dp + navigationBarBottomPadding()),
        ) {
            SettingsSectionLabel(stringResource(R.string.settings_reader_section_layout))
            SettingsCard {
                SettingsMenuRow(
                    icon = Icons.AutoMirrored.Outlined.MenuBook,
                    title = stringResource(R.string.settings_reader_reading_mode),
                    selected = settings.readerMode,
                    options = ReaderMode.entries.toList(),
                    labelRes = { it.labelRes() },
                ) { mode -> app.settings.update { it.copy(readerMode = mode) } }
                SettingsMenuRow(
                    icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    title = stringResource(R.string.settings_reader_paging_direction),
                    selected = settings.readingDirection,
                    options = ReadingDirection.entries.toList(),
                    labelRes = { it.labelRes() },
                ) { direction -> app.settings.update { it.copy(readingDirection = direction) } }
            }
            SettingsSectionLabel(stringResource(R.string.settings_reader_section_display))
            SettingsCard {
                SettingsMenuRow(
                    icon = Icons.Outlined.Palette,
                    title = stringResource(R.string.settings_reader_paper_color),
                    selected = settings.readerBackground,
                    options = listOf(
                        ReaderBackground.AUTO,
                        ReaderBackground.WHITE,
                        ReaderBackground.INK,
                        ReaderBackground.CUSTOM,
                    ),
                    labelRes = { it.labelRes() },
                ) { background ->
                    if (background == ReaderBackground.CUSTOM) {
                        showCustomBackground = true
                    } else {
                        app.settings.update { it.copy(readerBackground = background) }
                    }
                }
                SettingsSwitchRow(
                    icon = Icons.Outlined.Numbers,
                    title = stringResource(R.string.settings_reader_show_page_number),
                    supporting = stringResource(R.string.settings_reader_show_page_number_supporting),
                    checked = settings.showPageNumber,
                ) { app.settings.update { it.copy(showPageNumber = it.showPageNumber.not()) } }
                SettingsSwitchRow(
                    icon = Icons.Outlined.LightMode,
                    title = stringResource(R.string.settings_reader_keep_screen_on),
                    supporting = stringResource(R.string.settings_reader_keep_screen_on_supporting),
                    checked = settings.keepScreenOn,
                ) { app.settings.update { it.copy(keepScreenOn = it.keepScreenOn.not()) } }
                SettingsSwitchRow(
                    icon = Icons.AutoMirrored.Outlined.VolumeUp,
                    title = stringResource(R.string.settings_reader_volume_key_paging),
                    supporting = stringResource(R.string.settings_reader_volume_key_paging_supporting),
                    checked = settings.volumeKeyPaging,
                ) { app.settings.update { it.copy(volumeKeyPaging = it.volumeKeyPaging.not()) } }
            }
            SettingsSectionLabel(stringResource(R.string.settings_reader_section_filter))
            SettingsCard {
                SettingsSwitchRow(
                    icon = Icons.Outlined.FilterBAndW,
                    title = stringResource(R.string.settings_reader_grayscale),
                    supporting = stringResource(R.string.settings_reader_grayscale_supporting),
                    checked = settings.grayscale,
                ) { app.settings.update { it.copy(grayscale = it.grayscale.not()) } }
                SettingsSwitchRow(
                    icon = Icons.Outlined.Contrast,
                    title = stringResource(R.string.settings_reader_invert),
                    supporting = stringResource(R.string.settings_reader_invert_supporting),
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
    val context = LocalContext.current
    val app = context.applicationContext as SaltReaderApplication
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
        val startedAt = System.currentTimeMillis()
        scanning = true
        scanProgress = 0f
        scanLabel = context.getString(R.string.settings_storage_scanning)
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
                    report.count > 0 -> context.resources.getQuantityString(
                        R.plurals.settings_storage_scan_indexed,
                        report.count,
                        report.count,
                    )
                    report.hasFailures -> report.failures.first().reason
                    else -> context.getString(R.string.settings_storage_scan_no_comics)
                }
                scanMessageIsError = report.count == 0 && report.hasFailures
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                scanMessage = context.getString(
                    R.string.settings_storage_scan_failed,
                    e.message ?: context.getString(R.string.settings_unknown_error),
                )
                scanMessageIsError = true
            } finally {
                // Keep the progress slot on screen for a moment so a fast refresh does not flicker.
                val elapsed = System.currentTimeMillis() - startedAt
                if (elapsed < 420) delay(420 - elapsed)
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
                scanMessage = context.getString(R.string.settings_storage_root_removed, root.displayName)
                scanMessageIsError = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                scanMessage = context.getString(
                    R.string.settings_storage_root_remove_failed,
                    e.message ?: context.getString(R.string.settings_unknown_error),
                )
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
                        context.getString(R.string.settings_storage_cache_cleared, formatBytes(freed))
                    } else {
                        context.getString(R.string.settings_storage_cache_empty)
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                snackbar.showSnackbar(
                    context.getString(
                        R.string.settings_storage_cache_clear_failed,
                        e.message ?: context.getString(R.string.settings_unknown_error),
                    ),
                )
            } finally {
                clearing = false
            }
        }
    }

    SettingsScaffold(stringResource(R.string.settings_storage_title), onBack, snackbarHost = { SnackbarHost(snackbar) }) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp + navigationBarBottomPadding()),
        ) {
            item { SettingsSectionLabel(stringResource(R.string.settings_storage_section_library)) }
            item {
                SettingsCard {
                    // Height changes are animated, so scan progress/messages no longer shift the
                    // content below the card abruptly.
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .animateContentSize(animationSpec = tween(durationMillis = 180)),
                    ) {
                        Text(
                            pluralStringResource(
                                R.plurals.settings_storage_indexed_books,
                                books.size,
                                books.size,
                            ),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(2.dp))
                        // Reflowable text books only carry an estimated pageCount, so they are
                        // counted as books instead of inflating the page total.
                        val reflowableCount = books.count { it.reflowable }
                        val indexedPageCount = books.filter { !it.reflowable }.sumOf { it.pageCount }
                        val stats = mutableListOf<String>()
                        if (reflowableCount > 0) {
                            stats += pluralStringResource(
                                R.plurals.settings_storage_reflowable_books,
                                reflowableCount,
                                reflowableCount,
                            )
                        }
                        if (indexedPageCount > 0) {
                            stats += pluralStringResource(
                                R.plurals.settings_storage_indexed_pages,
                                indexedPageCount,
                                indexedPageCount,
                            )
                        }
                        stats += formatBytes(appDataBytes)
                        Text(
                            stats.joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        FilledTonalButton(
                            modifier = Modifier.align(Alignment.End),
                            enabled = !scanning && roots.isNotEmpty(),
                            onClick = { launchScan { p -> app.library.refreshLibrary(p) } },
                        ) {
                            Text(
                                if (scanning) {
                                    stringResource(R.string.settings_storage_refreshing)
                                } else {
                                    stringResource(R.string.settings_storage_refresh_index)
                                },
                            )
                        }
                        if (scanning) {
                            Spacer(Modifier.height(12.dp))
                            LinearWavyProgressIndicator(
                                progress = { scanProgress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                scanLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        } else if (scanMessage != null) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                scanMessage.orEmpty(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (scanMessageIsError) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            item { SettingsSectionLabel(stringResource(R.string.settings_storage_section_folders)) }
            item {
                SettingsCard {
                    SettingsActionRow(
                        icon = Icons.Outlined.FolderOpen,
                        title = stringResource(R.string.settings_storage_add_folder),
                        enabled = !scanning,
                    ) { folderLauncher.launch(null) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    if (roots.isEmpty()) {
                        Text(
                            stringResource(R.string.settings_storage_no_folders),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp),
                        )
                    } else {
                        roots.forEachIndexed { index, root ->
                            if (index > 0) {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                    modifier = Modifier.padding(start = 72.dp),
                                )
                            }
                            SettingsFolderRow(
                                displayName = root.displayName,
                                treeUri = root.treeUri,
                                enabled = !scanning,
                                onRemove = { removeRoot(root) },
                            )
                        }
                    }
                }
            }
            if (roots.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.settings_storage_remove_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
                    )
                }
            }
            item { SettingsSectionLabel(stringResource(R.string.settings_storage_section_cache)) }
            item {
                SettingsCard {
                    SettingsActionRow(
                        icon = Icons.Outlined.Delete,
                        title = stringResource(R.string.settings_storage_clear_cache),
                        supporting = if (clearing) {
                            stringResource(R.string.settings_storage_clearing)
                        } else {
                            stringResource(R.string.settings_storage_cache_usage, formatBytes(appDataBytes))
                        },
                        enabled = !clearing && !scanning,
                    ) { clearCaches() }
                }
            }
        }
    }
}

private data class OpenSourceLibrary(val name: String, val license: String, val note: String? = null)

private val runtimeLibraries = listOf(
    OpenSourceLibrary("Kotlin", "Apache License 2.0"),
    OpenSourceLibrary("Kotlin Coroutines", "Apache License 2.0"),
    OpenSourceLibrary("Kotlin Serialization", "Apache License 2.0"),
    OpenSourceLibrary("AndroidX Core", "Apache License 2.0"),
    OpenSourceLibrary("AndroidX Activity", "Apache License 2.0"),
    OpenSourceLibrary("AndroidX Lifecycle", "Apache License 2.0"),
    OpenSourceLibrary("Jetpack Compose", "Apache License 2.0"),
    OpenSourceLibrary("Material Components for Android / Material 3", "Apache License 2.0"),
    OpenSourceLibrary("Material Icons", "Apache License 2.0"),
    OpenSourceLibrary("AndroidX SplashScreen", "Apache License 2.0"),
    OpenSourceLibrary("AndroidX DocumentFile", "Apache License 2.0"),
    OpenSourceLibrary("AndroidX ExifInterface", "Apache License 2.0"),
    OpenSourceLibrary(
        "mobi-api4java",
        "Apache License 2.0",
        "MOBI/AZW3 text parsing, vendored and adapted (commons dependencies removed).",
    ),
    OpenSourceLibrary(
        "Android platform components (WebView, PdfRenderer, ZIP)",
        "Android Open Source Project licenses",
        "System components shipped with the device.",
    ),
)

private val testLibraries = listOf(
    OpenSourceLibrary("JUnit 4", "Eclipse Public License 1.0"),
    OpenSourceLibrary("Kotlin Test", "Apache License 2.0"),
    OpenSourceLibrary("kotlinx-coroutines-test", "Apache License 2.0"),
)

@Composable
private fun LicensesSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    var showApache by remember { mutableStateOf(false) }
    SettingsScaffold(stringResource(R.string.settings_licenses_title), onBack) { innerPadding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp + navigationBarBottomPadding()),
        ) {
            item {
                Text(
                    stringResource(R.string.settings_licenses_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            item { SettingsSectionLabel(stringResource(R.string.settings_licenses_runtime)) }
            item {
                SettingsCard {
                    runtimeLibraries.forEachIndexed { index, library ->
                        if (index > 0) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant,
                                modifier = Modifier.padding(start = 16.dp),
                            )
                        }
                        LibraryRow(library)
                    }
                }
            }
            item { SettingsSectionLabel(stringResource(R.string.settings_licenses_test)) }
            item {
                SettingsCard {
                    testLibraries.forEachIndexed { index, library ->
                        if (index > 0) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant,
                                modifier = Modifier.padding(start = 16.dp),
                            )
                        }
                        LibraryRow(library)
                    }
                }
            }
            item { SettingsSectionLabel(stringResource(R.string.settings_licenses_apache)) }
            item {
                SettingsCard {
                    SettingsActionRow(
                        icon = Icons.Outlined.Info,
                        title = "Apache License 2.0",
                        supporting = stringResource(R.string.settings_licenses_view_full),
                        enabled = true,
                    ) { showApache = true }
                }
            }
        }
    }
    if (showApache) {
        val apacheText = remember {
            runCatching {
                context.resources.openRawResource(R.raw.apache_license_2_0)
                    .bufferedReader()
                    .use { it.readText() }
            }.getOrDefault("")
        }
        AlertDialog(
            onDismissRequest = { showApache = false },
            title = { Text("Apache License 2.0") },
            text = {
                Box(
                    Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                ) {
                    Text(apacheText, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { showApache = false }) {
                    Text(stringResource(R.string.settings_licenses_close))
                }
            },
        )
    }
}

@Composable
private fun LibraryRow(library: OpenSourceLibrary) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(library.name, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(2.dp))
        Text(
            library.license,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        library.note?.let { note ->
            Spacer(Modifier.height(2.dp))
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AboutSettingsPage(onBack: () -> Unit) {
    SettingsScaffold(stringResource(R.string.settings_about_title), onBack) { innerPadding ->
        Column(
            Modifier.fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 32.dp + navigationBarBottomPadding()),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Vector mark tinted with theme roles: matches Android 13 monochrome/themed icons
                // and follows light/dark/dynamic color automatically.
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(96.dp),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(R.drawable.ic_app_mark),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(56.dp),
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text("Salt Reader", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.settings_about_tagline),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                )
            }
            SettingsSectionLabel(stringResource(R.string.settings_about_section_app))
            SettingsCard {
                SettingsInfoRow(
                    stringResource(R.string.settings_about_version),
                    "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(start = 16.dp),
                )
                SettingsInfoRow(stringResource(R.string.settings_about_build), BuildConfig.BUILD_TYPE)
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(start = 16.dp),
                )
                val context = LocalContext.current
                SettingsInfoRow(
                    stringResource(R.string.settings_about_github),
                    "PolarisSdesu/SaltReader",
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/PolarisSdesu/SaltReader")),
                            )
                        }
                    },
                )
            }
        }
    }

}

@Composable
private fun navigationBarBottomPadding(): Dp =
    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

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
                    maxLines = 2,
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
                    maxLines = 2,
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
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .alpha(if (enabled) 1f else 0.38f)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsIcon(Icons.Outlined.FolderOpen)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                displayName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                treeUri,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(enabled = enabled, onClick = onRemove, modifier = Modifier.size(48.dp)) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = stringResource(R.string.settings_storage_remove_folder, displayName),
            )
        }
    }
}

@Composable
private fun SettingsInfoRow(label: String, value: String, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.width(16.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (onClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

/** Menu-style setting row: title + current value + right-aligned dropdown. */
@Composable
private fun <T> SettingsMenuRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    selected: T,
    options: List<T>,
    labelRes: (T) -> Int,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable { expanded = true }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsIcon(icon)
            Spacer(Modifier.width(16.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(
                stringResource(labelRes(selected)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val menuWidth = 220.dp
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            offset = DpOffset(x = (maxWidth - menuWidth).coerceAtLeast(0.dp), y = 0.dp),
            modifier = Modifier.width(menuWidth),
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(labelRes(option))) },
                    leadingIcon = if (option == selected) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else {
                        null
                    },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun <T> SettingsChoiceGroup(
    title: String,
    options: List<T>,
    selected: T,
    labelRes: (T) -> Int,
    onSelect: (T) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))
        // Scrollable pills keep long translations (e.g. Japanese) readable instead of ellipsizing
        // them inside equal-width segments.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .selectableGroup(),
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
                ) {
                    Box(
                        Modifier
                            .selectable(
                                selected = checked,
                                role = Role.RadioButton,
                                onClick = { onSelect(option) },
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(labelRes(option)),
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/** Native language names stay in their own language, the "system" option is translated. */
private fun languageLabelRes(tag: String): Int = when (tag) {
    LANGUAGE_ZH_CN -> R.string.settings_language_zh_cn
    LANGUAGE_ZH_TW -> R.string.settings_language_zh_tw
    LANGUAGE_EN -> R.string.settings_language_en
    LANGUAGE_JA -> R.string.settings_language_ja
    else -> R.string.settings_language_system
}

private fun ThemeMode.labelRes(): Int = when (this) {
    ThemeMode.SYSTEM -> R.string.settings_theme_system
    ThemeMode.LIGHT -> R.string.settings_theme_light
    ThemeMode.DARK -> R.string.settings_theme_dark
}

private fun ReaderMode.labelRes(): Int = when (this) {
    ReaderMode.PAGED -> R.string.settings_reader_mode_paged
    ReaderMode.VERTICAL -> R.string.settings_reader_mode_vertical
}

private fun ReadingDirection.labelRes(): Int = when (this) {
    ReadingDirection.LTR -> R.string.settings_direction_ltr
    ReadingDirection.RTL -> R.string.settings_direction_rtl
}

private fun ReaderBackground.labelRes(): Int = when (this) {
    ReaderBackground.AUTO -> R.string.settings_background_auto
    ReaderBackground.WHITE -> R.string.settings_background_white
    ReaderBackground.INK -> R.string.settings_background_ink
    ReaderBackground.CUSTOM -> R.string.settings_background_custom
}
