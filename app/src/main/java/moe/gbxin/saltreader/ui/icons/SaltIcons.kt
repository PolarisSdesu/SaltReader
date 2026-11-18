/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// 只把应用实际用到的 35 个 Material 图标从 androidx material-icons-extended（11,400 个类）里
// 内联出来，去掉该依赖：debug APK 少 11,000+ 个类（23MB → 15MB），打包和 R8 也快很多。
// 代码由 material-icons-extended 1.7.8 的 sources jar 原样提取（Apache-2.0，见上）。
package moe.gbxin.saltreader.ui.icons

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.PathFillType.Companion.EvenOdd
import androidx.compose.ui.graphics.vector.ImageVector

// AutoMirrored.Filled.Sort
internal val Icons.AutoMirrored.Filled.Sort: ImageVector
    get() {
        if (_amfilled_sort != null) {
            return _amfilled_sort!!
        }
        _amfilled_sort = materialIcon(name = "AutoMirrored.Filled.Sort", autoMirror = true) {
            materialPath {
                moveTo(3.0f, 18.0f)
                horizontalLineToRelative(6.0f)
                verticalLineToRelative(-2.0f)
                lineTo(3.0f, 16.0f)
                verticalLineToRelative(2.0f)
                close()
                moveTo(3.0f, 6.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(18.0f)
                lineTo(21.0f, 6.0f)
                lineTo(3.0f, 6.0f)
                close()
                moveTo(3.0f, 13.0f)
                horizontalLineToRelative(12.0f)
                verticalLineToRelative(-2.0f)
                lineTo(3.0f, 11.0f)
                verticalLineToRelative(2.0f)
                close()
            }
        }
        return _amfilled_sort!!
    }

private var _amfilled_sort: ImageVector? = null

// AutoMirrored.Outlined.FormatIndentIncrease
internal val Icons.AutoMirrored.Outlined.FormatIndentIncrease: ImageVector
    get() {
        if (_amoutlined_formatIndentIncrease != null) {
            return _amoutlined_formatIndentIncrease!!
        }
        _amoutlined_formatIndentIncrease = materialIcon(name = "AutoMirrored.Outlined.FormatIndentIncrease",
                autoMirror = true) {
            materialPath {
                moveTo(3.0f, 21.0f)
                horizontalLineToRelative(18.0f)
                verticalLineToRelative(-2.0f)
                lineTo(3.0f, 19.0f)
                verticalLineToRelative(2.0f)
                close()
                moveTo(3.0f, 8.0f)
                verticalLineToRelative(8.0f)
                lineToRelative(4.0f, -4.0f)
                lineToRelative(-4.0f, -4.0f)
                close()
                moveTo(11.0f, 17.0f)
                horizontalLineToRelative(10.0f)
                verticalLineToRelative(-2.0f)
                lineTo(11.0f, 15.0f)
                verticalLineToRelative(2.0f)
                close()
                moveTo(3.0f, 3.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(18.0f)
                lineTo(21.0f, 3.0f)
                lineTo(3.0f, 3.0f)
                close()
                moveTo(11.0f, 9.0f)
                horizontalLineToRelative(10.0f)
                lineTo(21.0f, 7.0f)
                lineTo(11.0f, 7.0f)
                verticalLineToRelative(2.0f)
                close()
                moveTo(11.0f, 13.0f)
                horizontalLineToRelative(10.0f)
                verticalLineToRelative(-2.0f)
                lineTo(11.0f, 11.0f)
                verticalLineToRelative(2.0f)
                close()
            }
        }
        return _amoutlined_formatIndentIncrease!!
    }

private var _amoutlined_formatIndentIncrease: ImageVector? = null

// AutoMirrored.Outlined.MenuBook
internal val Icons.AutoMirrored.Outlined.MenuBook: ImageVector
    get() {
        if (_amoutlined_menuBook != null) {
            return _amoutlined_menuBook!!
        }
        _amoutlined_menuBook = materialIcon(name = "AutoMirrored.Outlined.MenuBook", autoMirror = true) {
            materialPath {
                moveTo(21.0f, 5.0f)
                curveToRelative(-1.11f, -0.35f, -2.33f, -0.5f, -3.5f, -0.5f)
                curveToRelative(-1.95f, 0.0f, -4.05f, 0.4f, -5.5f, 1.5f)
                curveToRelative(-1.45f, -1.1f, -3.55f, -1.5f, -5.5f, -1.5f)
                reflectiveCurveTo(2.45f, 4.9f, 1.0f, 6.0f)
                verticalLineToRelative(14.65f)
                curveToRelative(0.0f, 0.25f, 0.25f, 0.5f, 0.5f, 0.5f)
                curveToRelative(0.1f, 0.0f, 0.15f, -0.05f, 0.25f, -0.05f)
                curveTo(3.1f, 20.45f, 5.05f, 20.0f, 6.5f, 20.0f)
                curveToRelative(1.95f, 0.0f, 4.05f, 0.4f, 5.5f, 1.5f)
                curveToRelative(1.35f, -0.85f, 3.8f, -1.5f, 5.5f, -1.5f)
                curveToRelative(1.65f, 0.0f, 3.35f, 0.3f, 4.75f, 1.05f)
                curveToRelative(0.1f, 0.05f, 0.15f, 0.05f, 0.25f, 0.05f)
                curveToRelative(0.25f, 0.0f, 0.5f, -0.25f, 0.5f, -0.5f)
                verticalLineTo(6.0f)
                curveTo(22.4f, 5.55f, 21.75f, 5.25f, 21.0f, 5.0f)
                close()
                moveTo(21.0f, 18.5f)
                curveToRelative(-1.1f, -0.35f, -2.3f, -0.5f, -3.5f, -0.5f)
                curveToRelative(-1.7f, 0.0f, -4.15f, 0.65f, -5.5f, 1.5f)
                verticalLineTo(8.0f)
                curveToRelative(1.35f, -0.85f, 3.8f, -1.5f, 5.5f, -1.5f)
                curveToRelative(1.2f, 0.0f, 2.4f, 0.15f, 3.5f, 0.5f)
                verticalLineTo(18.5f)
                close()
            }
            materialPath {
                moveTo(17.5f, 10.5f)
                curveToRelative(0.88f, 0.0f, 1.73f, 0.09f, 2.5f, 0.26f)
                verticalLineTo(9.24f)
                curveTo(19.21f, 9.09f, 18.36f, 9.0f, 17.5f, 9.0f)
                curveToRelative(-1.7f, 0.0f, -3.24f, 0.29f, -4.5f, 0.83f)
                verticalLineToRelative(1.66f)
                curveTo(14.13f, 10.85f, 15.7f, 10.5f, 17.5f, 10.5f)
                close()
            }
            materialPath {
                moveTo(13.0f, 12.49f)
                verticalLineToRelative(1.66f)
                curveToRelative(1.13f, -0.64f, 2.7f, -0.99f, 4.5f, -0.99f)
                curveToRelative(0.88f, 0.0f, 1.73f, 0.09f, 2.5f, 0.26f)
                verticalLineTo(11.9f)
                curveToRelative(-0.79f, -0.15f, -1.64f, -0.24f, -2.5f, -0.24f)
                curveTo(15.8f, 11.66f, 14.26f, 11.96f, 13.0f, 12.49f)
                close()
            }
            materialPath {
                moveTo(17.5f, 14.33f)
                curveToRelative(-1.7f, 0.0f, -3.24f, 0.29f, -4.5f, 0.83f)
                verticalLineToRelative(1.66f)
                curveToRelative(1.13f, -0.64f, 2.7f, -0.99f, 4.5f, -0.99f)
                curveToRelative(0.88f, 0.0f, 1.73f, 0.09f, 2.5f, 0.26f)
                verticalLineToRelative(-1.52f)
                curveTo(19.21f, 14.41f, 18.36f, 14.33f, 17.5f, 14.33f)
                close()
            }
        }
        return _amoutlined_menuBook!!
    }

private var _amoutlined_menuBook: ImageVector? = null

// AutoMirrored.Outlined.VolumeUp
internal val Icons.AutoMirrored.Outlined.VolumeUp: ImageVector
    get() {
        if (_amoutlined_volumeUp != null) {
            return _amoutlined_volumeUp!!
        }
        _amoutlined_volumeUp = materialIcon(name = "AutoMirrored.Outlined.VolumeUp", autoMirror = true) {
            materialPath {
                moveTo(3.0f, 9.0f)
                verticalLineToRelative(6.0f)
                horizontalLineToRelative(4.0f)
                lineToRelative(5.0f, 5.0f)
                lineTo(12.0f, 4.0f)
                lineTo(7.0f, 9.0f)
                lineTo(3.0f, 9.0f)
                close()
                moveTo(10.0f, 8.83f)
                verticalLineToRelative(6.34f)
                lineTo(7.83f, 13.0f)
                lineTo(5.0f, 13.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(2.83f)
                lineTo(10.0f, 8.83f)
                close()
                moveTo(16.5f, 12.0f)
                curveToRelative(0.0f, -1.77f, -1.02f, -3.29f, -2.5f, -4.03f)
                verticalLineToRelative(8.05f)
                curveToRelative(1.48f, -0.73f, 2.5f, -2.25f, 2.5f, -4.02f)
                close()
                moveTo(14.0f, 3.23f)
                verticalLineToRelative(2.06f)
                curveToRelative(2.89f, 0.86f, 5.0f, 3.54f, 5.0f, 6.71f)
                reflectiveCurveToRelative(-2.11f, 5.85f, -5.0f, 6.71f)
                verticalLineToRelative(2.06f)
                curveToRelative(4.01f, -0.91f, 7.0f, -4.49f, 7.0f, -8.77f)
                curveToRelative(0.0f, -4.28f, -2.99f, -7.86f, -7.0f, -8.77f)
                close()
            }
        }
        return _amoutlined_volumeUp!!
    }

private var _amoutlined_volumeUp: ImageVector? = null

// AutoMirrored.Rounded.FormatListBulleted
internal val Icons.AutoMirrored.Rounded.FormatListBulleted: ImageVector
    get() {
        if (_amrounded_formatListBulleted != null) {
            return _amrounded_formatListBulleted!!
        }
        _amrounded_formatListBulleted = materialIcon(name = "AutoMirrored.Rounded.FormatListBulleted",
                autoMirror = true) {
            materialPath {
                moveTo(4.0f, 10.5f)
                curveToRelative(-0.83f, 0.0f, -1.5f, 0.67f, -1.5f, 1.5f)
                reflectiveCurveToRelative(0.67f, 1.5f, 1.5f, 1.5f)
                reflectiveCurveToRelative(1.5f, -0.67f, 1.5f, -1.5f)
                reflectiveCurveToRelative(-0.67f, -1.5f, -1.5f, -1.5f)
                close()
                moveTo(4.0f, 4.5f)
                curveToRelative(-0.83f, 0.0f, -1.5f, 0.67f, -1.5f, 1.5f)
                reflectiveCurveTo(3.17f, 7.5f, 4.0f, 7.5f)
                reflectiveCurveTo(5.5f, 6.83f, 5.5f, 6.0f)
                reflectiveCurveTo(4.83f, 4.5f, 4.0f, 4.5f)
                close()
                moveTo(4.0f, 16.5f)
                curveToRelative(-0.83f, 0.0f, -1.5f, 0.68f, -1.5f, 1.5f)
                reflectiveCurveToRelative(0.68f, 1.5f, 1.5f, 1.5f)
                reflectiveCurveToRelative(1.5f, -0.68f, 1.5f, -1.5f)
                reflectiveCurveToRelative(-0.67f, -1.5f, -1.5f, -1.5f)
                close()
                moveTo(8.0f, 19.0f)
                horizontalLineToRelative(12.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                reflectiveCurveToRelative(-0.45f, -1.0f, -1.0f, -1.0f)
                lineTo(8.0f, 17.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                reflectiveCurveToRelative(0.45f, 1.0f, 1.0f, 1.0f)
                close()
                moveTo(8.0f, 13.0f)
                horizontalLineToRelative(12.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                reflectiveCurveToRelative(-0.45f, -1.0f, -1.0f, -1.0f)
                lineTo(8.0f, 11.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                reflectiveCurveToRelative(0.45f, 1.0f, 1.0f, 1.0f)
                close()
                moveTo(7.0f, 6.0f)
                curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
                horizontalLineToRelative(12.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                reflectiveCurveToRelative(-0.45f, -1.0f, -1.0f, -1.0f)
                lineTo(8.0f, 5.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                close()
            }
        }
        return _amrounded_formatListBulleted!!
    }

private var _amrounded_formatListBulleted: ImageVector? = null

// Filled.AutoStories
internal val Icons.Filled.AutoStories: ImageVector
    get() {
        if (_filled_autoStories != null) {
            return _filled_autoStories!!
        }
        _filled_autoStories = materialIcon(name = "Filled.AutoStories") {
            materialPath {
                moveTo(19.0f, 1.0f)
                lineToRelative(-5.0f, 5.0f)
                verticalLineToRelative(11.0f)
                lineToRelative(5.0f, -4.5f)
                lineTo(19.0f, 1.0f)
                close()
                moveTo(1.0f, 6.0f)
                verticalLineToRelative(14.65f)
                curveToRelative(0.0f, 0.25f, 0.25f, 0.5f, 0.5f, 0.5f)
                curveToRelative(0.1f, 0.0f, 0.15f, -0.05f, 0.25f, -0.05f)
                curveTo(3.1f, 20.45f, 5.05f, 20.0f, 6.5f, 20.0f)
                curveToRelative(1.95f, 0.0f, 4.05f, 0.4f, 5.5f, 1.5f)
                lineTo(12.0f, 6.0f)
                curveToRelative(-1.45f, -1.1f, -3.55f, -1.5f, -5.5f, -1.5f)
                reflectiveCurveTo(2.45f, 4.9f, 1.0f, 6.0f)
                close()
                moveTo(23.0f, 19.5f)
                lineTo(23.0f, 6.0f)
                curveToRelative(-0.6f, -0.45f, -1.25f, -0.75f, -2.0f, -1.0f)
                verticalLineToRelative(13.5f)
                curveToRelative(-1.1f, -0.35f, -2.3f, -0.5f, -3.5f, -0.5f)
                curveToRelative(-1.7f, 0.0f, -4.15f, 0.65f, -5.5f, 1.5f)
                verticalLineToRelative(2.0f)
                curveToRelative(1.35f, -0.85f, 3.8f, -1.5f, 5.5f, -1.5f)
                curveToRelative(1.65f, 0.0f, 3.35f, 0.3f, 4.75f, 1.05f)
                curveToRelative(0.1f, 0.05f, 0.15f, 0.05f, 0.25f, 0.05f)
                curveToRelative(0.25f, 0.0f, 0.5f, -0.25f, 0.5f, -0.5f)
                verticalLineToRelative(-1.1f)
                close()
            }
        }
        return _filled_autoStories!!
    }

private var _filled_autoStories: ImageVector? = null

// Filled.ArrowDownward
internal val Icons.Filled.ArrowDownward: ImageVector
    get() {
        if (_filled_arrowDownward != null) {
            return _filled_arrowDownward!!
        }
        _filled_arrowDownward = materialIcon(name = "Filled.ArrowDownward") {
            materialPath {
                moveTo(20.0f, 12.0f)
                lineToRelative(-1.41f, -1.41f)
                lineTo(13.0f, 16.17f)
                verticalLineTo(4.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(12.17f)
                lineToRelative(-5.58f, -5.59f)
                lineTo(4.0f, 12.0f)
                lineToRelative(8.0f, 8.0f)
                lineToRelative(8.0f, -8.0f)
                close()
            }
        }
        return _filled_arrowDownward!!
    }

private var _filled_arrowDownward: ImageVector? = null

// Filled.ArrowUpward
internal val Icons.Filled.ArrowUpward: ImageVector
    get() {
        if (_filled_arrowUpward != null) {
            return _filled_arrowUpward!!
        }
        _filled_arrowUpward = materialIcon(name = "Filled.ArrowUpward") {
            materialPath {
                moveTo(4.0f, 12.0f)
                lineToRelative(1.41f, 1.41f)
                lineTo(11.0f, 7.83f)
                verticalLineTo(20.0f)
                horizontalLineToRelative(2.0f)
                verticalLineTo(7.83f)
                lineToRelative(5.58f, 5.59f)
                lineTo(20.0f, 12.0f)
                lineToRelative(-8.0f, -8.0f)
                lineToRelative(-8.0f, 8.0f)
                close()
            }
        }
        return _filled_arrowUpward!!
    }

private var _filled_arrowUpward: ImageVector? = null


// Filled.DragHandle
internal val Icons.Filled.DragHandle: ImageVector
    get() {
        if (_filled_dragHandle != null) {
            return _filled_dragHandle!!
        }
        _filled_dragHandle = materialIcon(name = "Filled.DragHandle") {
            materialPath {
                moveTo(20.0f, 9.0f)
                horizontalLineTo(4.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(16.0f)
                verticalLineTo(9.0f)
                close()
                moveTo(4.0f, 15.0f)
                horizontalLineToRelative(16.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineTo(4.0f)
                verticalLineTo(15.0f)
                close()
            }
        }
        return _filled_dragHandle!!
    }

private var _filled_dragHandle: ImageVector? = null

// Filled.FolderOpen
internal val Icons.Filled.FolderOpen: ImageVector
    get() {
        if (_filled_folderOpen != null) {
            return _filled_folderOpen!!
        }
        _filled_folderOpen = materialIcon(name = "Filled.FolderOpen") {
            materialPath {
                moveTo(20.0f, 6.0f)
                horizontalLineToRelative(-8.0f)
                lineToRelative(-2.0f, -2.0f)
                lineTo(4.0f, 4.0f)
                curveToRelative(-1.1f, 0.0f, -1.99f, 0.9f, -1.99f, 2.0f)
                lineTo(2.0f, 18.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(16.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                lineTo(22.0f, 8.0f)
                curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                close()
                moveTo(20.0f, 18.0f)
                lineTo(4.0f, 18.0f)
                lineTo(4.0f, 8.0f)
                horizontalLineToRelative(16.0f)
                verticalLineToRelative(10.0f)
                close()
            }
        }
        return _filled_folderOpen!!
    }

private var _filled_folderOpen: ImageVector? = null

// Filled.GridView
internal val Icons.Filled.GridView: ImageVector
    get() {
        if (_filled_gridView != null) {
            return _filled_gridView!!
        }
        _filled_gridView = materialIcon(name = "Filled.GridView") {
            materialPath(pathFillType = EvenOdd) {
                moveTo(3.0f, 3.0f)
                verticalLineToRelative(8.0f)
                horizontalLineToRelative(8.0f)
                lineTo(11.0f, 3.0f)
                lineTo(3.0f, 3.0f)
                close()
                moveTo(9.0f, 9.0f)
                lineTo(5.0f, 9.0f)
                lineTo(5.0f, 5.0f)
                horizontalLineToRelative(4.0f)
                verticalLineToRelative(4.0f)
                close()
                moveTo(3.0f, 13.0f)
                verticalLineToRelative(8.0f)
                horizontalLineToRelative(8.0f)
                verticalLineToRelative(-8.0f)
                lineTo(3.0f, 13.0f)
                close()
                moveTo(9.0f, 19.0f)
                lineTo(5.0f, 19.0f)
                verticalLineToRelative(-4.0f)
                horizontalLineToRelative(4.0f)
                verticalLineToRelative(4.0f)
                close()
                moveTo(13.0f, 3.0f)
                verticalLineToRelative(8.0f)
                horizontalLineToRelative(8.0f)
                lineTo(21.0f, 3.0f)
                horizontalLineToRelative(-8.0f)
                close()
                moveTo(19.0f, 9.0f)
                horizontalLineToRelative(-4.0f)
                lineTo(15.0f, 5.0f)
                horizontalLineToRelative(4.0f)
                verticalLineToRelative(4.0f)
                close()
                moveTo(13.0f, 13.0f)
                verticalLineToRelative(8.0f)
                horizontalLineToRelative(8.0f)
                verticalLineToRelative(-8.0f)
                horizontalLineToRelative(-8.0f)
                close()
                moveTo(19.0f, 19.0f)
                horizontalLineToRelative(-4.0f)
                verticalLineToRelative(-4.0f)
                horizontalLineToRelative(4.0f)
                verticalLineToRelative(4.0f)
                close()
            }
        }
        return _filled_gridView!!
    }

private var _filled_gridView: ImageVector? = null

// Filled.RadioButtonUnchecked
internal val Icons.Filled.RadioButtonUnchecked: ImageVector
    get() {
        if (_filled_radioButtonUnchecked != null) {
            return _filled_radioButtonUnchecked!!
        }
        _filled_radioButtonUnchecked = materialIcon(name = "Filled.RadioButtonUnchecked") {
            materialPath {
                moveTo(12.0f, 2.0f)
                curveTo(6.48f, 2.0f, 2.0f, 6.48f, 2.0f, 12.0f)
                reflectiveCurveToRelative(4.48f, 10.0f, 10.0f, 10.0f)
                reflectiveCurveToRelative(10.0f, -4.48f, 10.0f, -10.0f)
                reflectiveCurveTo(17.52f, 2.0f, 12.0f, 2.0f)
                close()
                moveTo(12.0f, 20.0f)
                curveToRelative(-4.42f, 0.0f, -8.0f, -3.58f, -8.0f, -8.0f)
                reflectiveCurveToRelative(3.58f, -8.0f, 8.0f, -8.0f)
                reflectiveCurveToRelative(8.0f, 3.58f, 8.0f, 8.0f)
                reflectiveCurveToRelative(-3.58f, 8.0f, -8.0f, 8.0f)
                close()
            }
        }
        return _filled_radioButtonUnchecked!!
    }

private var _filled_radioButtonUnchecked: ImageVector? = null

// Outlined.AspectRatio
internal val Icons.Outlined.AspectRatio: ImageVector
    get() {
        if (_outlined_aspectRatio != null) {
            return _outlined_aspectRatio!!
        }
        _outlined_aspectRatio = materialIcon(name = "Outlined.AspectRatio") {
            materialPath {
                moveTo(19.0f, 12.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(3.0f)
                horizontalLineToRelative(-3.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(5.0f)
                verticalLineToRelative(-5.0f)
                close()
                moveTo(7.0f, 9.0f)
                horizontalLineToRelative(3.0f)
                lineTo(10.0f, 7.0f)
                lineTo(5.0f, 7.0f)
                verticalLineToRelative(5.0f)
                horizontalLineToRelative(2.0f)
                lineTo(7.0f, 9.0f)
                close()
                moveTo(21.0f, 3.0f)
                lineTo(3.0f, 3.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(14.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(18.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                lineTo(23.0f, 5.0f)
                curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                close()
                moveTo(21.0f, 19.01f)
                lineTo(3.0f, 19.01f)
                lineTo(3.0f, 4.99f)
                horizontalLineToRelative(18.0f)
                verticalLineToRelative(14.02f)
                close()
            }
        }
        return _outlined_aspectRatio!!
    }

private var _outlined_aspectRatio: ImageVector? = null

// Outlined.BugReport
internal val Icons.Outlined.BugReport: ImageVector
    get() {
        if (_outlined_bugReport != null) {
            return _outlined_bugReport!!
        }
        _outlined_bugReport = materialIcon(name = "Outlined.BugReport") {
            materialPath {
                moveTo(20.0f, 8.0f)
                horizontalLineToRelative(-2.81f)
                curveToRelative(-0.45f, -0.78f, -1.07f, -1.45f, -1.82f, -1.96f)
                lineTo(17.0f, 4.41f)
                lineTo(15.59f, 3.0f)
                lineToRelative(-2.17f, 2.17f)
                curveTo(12.96f, 5.06f, 12.49f, 5.0f, 12.0f, 5.0f)
                reflectiveCurveToRelative(-0.96f, 0.06f, -1.41f, 0.17f)
                lineTo(8.41f, 3.0f)
                lineTo(7.0f, 4.41f)
                lineToRelative(1.62f, 1.63f)
                curveTo(7.88f, 6.55f, 7.26f, 7.22f, 6.81f, 8.0f)
                lineTo(4.0f, 8.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(2.09f)
                curveToRelative(-0.05f, 0.33f, -0.09f, 0.66f, -0.09f, 1.0f)
                verticalLineToRelative(1.0f)
                lineTo(4.0f, 12.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(1.0f)
                curveToRelative(0.0f, 0.34f, 0.04f, 0.67f, 0.09f, 1.0f)
                lineTo(4.0f, 16.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(2.81f)
                curveToRelative(1.04f, 1.79f, 2.97f, 3.0f, 5.19f, 3.0f)
                reflectiveCurveToRelative(4.15f, -1.21f, 5.19f, -3.0f)
                lineTo(20.0f, 18.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(-2.09f)
                curveToRelative(0.05f, -0.33f, 0.09f, -0.66f, 0.09f, -1.0f)
                verticalLineToRelative(-1.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(-1.0f)
                curveToRelative(0.0f, -0.34f, -0.04f, -0.67f, -0.09f, -1.0f)
                lineTo(20.0f, 10.0f)
                lineTo(20.0f, 8.0f)
                close()
                moveTo(16.0f, 12.0f)
                verticalLineToRelative(3.0f)
                curveToRelative(0.0f, 0.22f, -0.03f, 0.47f, -0.07f, 0.7f)
                lineToRelative(-0.1f, 0.65f)
                lineToRelative(-0.37f, 0.65f)
                curveToRelative(-0.72f, 1.24f, -2.04f, 2.0f, -3.46f, 2.0f)
                reflectiveCurveToRelative(-2.74f, -0.77f, -3.46f, -2.0f)
                lineToRelative(-0.37f, -0.64f)
                lineToRelative(-0.1f, -0.65f)
                curveTo(8.03f, 15.48f, 8.0f, 15.23f, 8.0f, 15.0f)
                verticalLineToRelative(-4.0f)
                curveToRelative(0.0f, -0.23f, 0.03f, -0.48f, 0.07f, -0.7f)
                lineToRelative(0.1f, -0.65f)
                lineToRelative(0.37f, -0.65f)
                curveToRelative(0.3f, -0.52f, 0.72f, -0.97f, 1.21f, -1.31f)
                lineToRelative(0.57f, -0.39f)
                lineToRelative(0.74f, -0.18f)
                curveToRelative(0.31f, -0.08f, 0.63f, -0.12f, 0.94f, -0.12f)
                curveToRelative(0.32f, 0.0f, 0.63f, 0.04f, 0.95f, 0.12f)
                lineToRelative(0.68f, 0.16f)
                lineToRelative(0.61f, 0.42f)
                curveToRelative(0.5f, 0.34f, 0.91f, 0.78f, 1.21f, 1.31f)
                lineToRelative(0.38f, 0.65f)
                lineToRelative(0.1f, 0.65f)
                curveToRelative(0.04f, 0.22f, 0.07f, 0.47f, 0.07f, 0.69f)
                verticalLineToRelative(1.0f)
                close()
                moveTo(10.0f, 14.0f)
                horizontalLineToRelative(4.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(-4.0f)
                close()
                moveTo(10.0f, 10.0f)
                horizontalLineToRelative(4.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(-4.0f)
                close()
            }
        }
        return _outlined_bugReport!!
    }

private var _outlined_bugReport: ImageVector? = null

// Outlined.Code
internal val Icons.Outlined.Code: ImageVector
    get() {
        if (_outlined_code != null) {
            return _outlined_code!!
        }
        _outlined_code = materialIcon(name = "Outlined.Code") {
            materialPath {
                moveTo(9.4f, 16.6f)
                lineTo(4.8f, 12.0f)
                lineToRelative(4.6f, -4.6f)
                lineTo(8.0f, 6.0f)
                lineToRelative(-6.0f, 6.0f)
                lineToRelative(6.0f, 6.0f)
                lineToRelative(1.4f, -1.4f)
                close()
                moveTo(14.6f, 16.6f)
                lineToRelative(4.6f, -4.6f)
                lineToRelative(-4.6f, -4.6f)
                lineTo(16.0f, 6.0f)
                lineToRelative(6.0f, 6.0f)
                lineToRelative(-6.0f, 6.0f)
                lineToRelative(-1.4f, -1.4f)
                close()
            }
        }
        return _outlined_code!!
    }

private var _outlined_code: ImageVector? = null

// Outlined.Contrast
internal val Icons.Outlined.Contrast: ImageVector
    get() {
        if (_outlined_contrast != null) {
            return _outlined_contrast!!
        }
        _outlined_contrast = materialIcon(name = "Outlined.Contrast") {
            materialPath {
                moveTo(12.0f, 22.0f)
                curveToRelative(5.52f, 0.0f, 10.0f, -4.48f, 10.0f, -10.0f)
                reflectiveCurveTo(17.52f, 2.0f, 12.0f, 2.0f)
                reflectiveCurveTo(2.0f, 6.48f, 2.0f, 12.0f)
                reflectiveCurveTo(6.48f, 22.0f, 12.0f, 22.0f)
                close()
                moveTo(13.0f, 4.07f)
                curveToRelative(3.94f, 0.49f, 7.0f, 3.85f, 7.0f, 7.93f)
                reflectiveCurveToRelative(-3.05f, 7.44f, -7.0f, 7.93f)
                verticalLineTo(4.07f)
                close()
            }
        }
        return _outlined_contrast!!
    }

private var _outlined_contrast: ImageVector? = null

// Outlined.DarkMode
internal val Icons.Outlined.DarkMode: ImageVector
    get() {
        if (_outlined_darkMode != null) {
            return _outlined_darkMode!!
        }
        _outlined_darkMode = materialIcon(name = "Outlined.DarkMode") {
            materialPath {
                moveTo(9.37f, 5.51f)
                curveTo(9.19f, 6.15f, 9.1f, 6.82f, 9.1f, 7.5f)
                curveToRelative(0.0f, 4.08f, 3.32f, 7.4f, 7.4f, 7.4f)
                curveToRelative(0.68f, 0.0f, 1.35f, -0.09f, 1.99f, -0.27f)
                curveTo(17.45f, 17.19f, 14.93f, 19.0f, 12.0f, 19.0f)
                curveToRelative(-3.86f, 0.0f, -7.0f, -3.14f, -7.0f, -7.0f)
                curveTo(5.0f, 9.07f, 6.81f, 6.55f, 9.37f, 5.51f)
                close()
                moveTo(12.0f, 3.0f)
                curveToRelative(-4.97f, 0.0f, -9.0f, 4.03f, -9.0f, 9.0f)
                reflectiveCurveToRelative(4.03f, 9.0f, 9.0f, 9.0f)
                reflectiveCurveToRelative(9.0f, -4.03f, 9.0f, -9.0f)
                curveToRelative(0.0f, -0.46f, -0.04f, -0.92f, -0.1f, -1.36f)
                curveToRelative(-0.98f, 1.37f, -2.58f, 2.26f, -4.4f, 2.26f)
                curveToRelative(-2.98f, 0.0f, -5.4f, -2.42f, -5.4f, -5.4f)
                curveToRelative(0.0f, -1.81f, 0.89f, -3.42f, 2.26f, -4.4f)
                curveTo(12.92f, 3.04f, 12.46f, 3.0f, 12.0f, 3.0f)
                lineTo(12.0f, 3.0f)
                close()
            }
        }
        return _outlined_darkMode!!
    }

private var _outlined_darkMode: ImageVector? = null

// Outlined.Description
internal val Icons.Outlined.Description: ImageVector
    get() {
        if (_outlined_description != null) {
            return _outlined_description!!
        }
        _outlined_description = materialIcon(name = "Outlined.Description") {
            materialPath {
                moveTo(8.0f, 16.0f)
                horizontalLineToRelative(8.0f)
                verticalLineToRelative(2.0f)
                lineTo(8.0f, 18.0f)
                close()
                moveTo(8.0f, 12.0f)
                horizontalLineToRelative(8.0f)
                verticalLineToRelative(2.0f)
                lineTo(8.0f, 14.0f)
                close()
                moveTo(14.0f, 2.0f)
                lineTo(6.0f, 2.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(16.0f)
                curveToRelative(0.0f, 1.1f, 0.89f, 2.0f, 1.99f, 2.0f)
                lineTo(18.0f, 22.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                lineTo(20.0f, 8.0f)
                lineToRelative(-6.0f, -6.0f)
                close()
                moveTo(18.0f, 20.0f)
                lineTo(6.0f, 20.0f)
                lineTo(6.0f, 4.0f)
                horizontalLineToRelative(7.0f)
                verticalLineToRelative(5.0f)
                horizontalLineToRelative(5.0f)
                verticalLineToRelative(11.0f)
                close()
            }
        }
        return _outlined_description!!
    }

private var _outlined_description: ImageVector? = null

// Outlined.FilterBAndW
internal val Icons.Outlined.FilterBAndW: ImageVector
    get() {
        if (_outlined_filterBAndW != null) {
            return _outlined_filterBAndW!!
        }
        _outlined_filterBAndW = materialIcon(name = "Outlined.FilterBAndW") {
            materialPath {
                moveTo(19.0f, 3.0f)
                lineTo(5.0f, 3.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(14.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(14.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                lineTo(21.0f, 5.0f)
                curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                close()
                moveTo(19.0f, 19.0f)
                lineToRelative(-7.0f, -8.0f)
                verticalLineToRelative(8.0f)
                lineTo(5.0f, 19.0f)
                lineToRelative(7.0f, -8.0f)
                lineTo(12.0f, 5.0f)
                horizontalLineToRelative(7.0f)
                verticalLineToRelative(14.0f)
                close()
            }
        }
        return _outlined_filterBAndW!!
    }

private var _outlined_filterBAndW: ImageVector? = null

// Outlined.FolderOpen
internal val Icons.Outlined.FolderOpen: ImageVector
    get() {
        if (_outlined_folderOpen != null) {
            return _outlined_folderOpen!!
        }
        _outlined_folderOpen = materialIcon(name = "Outlined.FolderOpen") {
            materialPath {
                moveTo(20.0f, 6.0f)
                horizontalLineToRelative(-8.0f)
                lineToRelative(-2.0f, -2.0f)
                lineTo(4.0f, 4.0f)
                curveToRelative(-1.1f, 0.0f, -1.99f, 0.9f, -1.99f, 2.0f)
                lineTo(2.0f, 18.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(16.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                lineTo(22.0f, 8.0f)
                curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                close()
                moveTo(20.0f, 18.0f)
                lineTo(4.0f, 18.0f)
                lineTo(4.0f, 8.0f)
                horizontalLineToRelative(16.0f)
                verticalLineToRelative(10.0f)
                close()
            }
        }
        return _outlined_folderOpen!!
    }

private var _outlined_folderOpen: ImageVector? = null

// Outlined.FormatLineSpacing
internal val Icons.Outlined.FormatLineSpacing: ImageVector
    get() {
        if (_outlined_formatLineSpacing != null) {
            return _outlined_formatLineSpacing!!
        }
        _outlined_formatLineSpacing = materialIcon(name = "Outlined.FormatLineSpacing") {
            materialPath {
                moveTo(6.0f, 7.0f)
                horizontalLineToRelative(2.5f)
                lineTo(5.0f, 3.5f)
                lineTo(1.5f, 7.0f)
                lineTo(4.0f, 7.0f)
                verticalLineToRelative(10.0f)
                lineTo(1.5f, 17.0f)
                lineTo(5.0f, 20.5f)
                lineTo(8.5f, 17.0f)
                lineTo(6.0f, 17.0f)
                lineTo(6.0f, 7.0f)
                close()
                moveTo(10.0f, 5.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(12.0f)
                lineTo(22.0f, 5.0f)
                lineTo(10.0f, 5.0f)
                close()
                moveTo(10.0f, 19.0f)
                horizontalLineToRelative(12.0f)
                verticalLineToRelative(-2.0f)
                lineTo(10.0f, 17.0f)
                verticalLineToRelative(2.0f)
                close()
                moveTo(10.0f, 13.0f)
                horizontalLineToRelative(12.0f)
                verticalLineToRelative(-2.0f)
                lineTo(10.0f, 11.0f)
                verticalLineToRelative(2.0f)
                close()
            }
        }
        return _outlined_formatLineSpacing!!
    }

private var _outlined_formatLineSpacing: ImageVector? = null

// Outlined.FormatSize
internal val Icons.Outlined.FormatSize: ImageVector
    get() {
        if (_outlined_formatSize != null) {
            return _outlined_formatSize!!
        }
        _outlined_formatSize = materialIcon(name = "Outlined.FormatSize") {
            materialPath {
                moveTo(9.0f, 4.0f)
                verticalLineToRelative(3.0f)
                horizontalLineToRelative(5.0f)
                verticalLineToRelative(12.0f)
                horizontalLineToRelative(3.0f)
                lineTo(17.0f, 7.0f)
                horizontalLineToRelative(5.0f)
                lineTo(22.0f, 4.0f)
                lineTo(9.0f, 4.0f)
                close()
                moveTo(3.0f, 12.0f)
                horizontalLineToRelative(3.0f)
                verticalLineToRelative(7.0f)
                horizontalLineToRelative(3.0f)
                verticalLineToRelative(-7.0f)
                horizontalLineToRelative(3.0f)
                lineTo(12.0f, 9.0f)
                lineTo(3.0f, 9.0f)
                verticalLineToRelative(3.0f)
                close()
            }
        }
        return _outlined_formatSize!!
    }

private var _outlined_formatSize: ImageVector? = null

// Outlined.Language
internal val Icons.Outlined.Language: ImageVector
    get() {
        if (_outlined_language != null) {
            return _outlined_language!!
        }
        _outlined_language = materialIcon(name = "Outlined.Language") {
            materialPath {
                moveTo(11.99f, 2.0f)
                curveTo(6.47f, 2.0f, 2.0f, 6.48f, 2.0f, 12.0f)
                reflectiveCurveToRelative(4.47f, 10.0f, 9.99f, 10.0f)
                curveTo(17.52f, 22.0f, 22.0f, 17.52f, 22.0f, 12.0f)
                reflectiveCurveTo(17.52f, 2.0f, 11.99f, 2.0f)
                close()
                moveTo(18.92f, 8.0f)
                horizontalLineToRelative(-2.95f)
                curveToRelative(-0.32f, -1.25f, -0.78f, -2.45f, -1.38f, -3.56f)
                curveToRelative(1.84f, 0.63f, 3.37f, 1.91f, 4.33f, 3.56f)
                close()
                moveTo(12.0f, 4.04f)
                curveToRelative(0.83f, 1.2f, 1.48f, 2.53f, 1.91f, 3.96f)
                horizontalLineToRelative(-3.82f)
                curveToRelative(0.43f, -1.43f, 1.08f, -2.76f, 1.91f, -3.96f)
                close()
                moveTo(4.26f, 14.0f)
                curveTo(4.1f, 13.36f, 4.0f, 12.69f, 4.0f, 12.0f)
                reflectiveCurveToRelative(0.1f, -1.36f, 0.26f, -2.0f)
                horizontalLineToRelative(3.38f)
                curveToRelative(-0.08f, 0.66f, -0.14f, 1.32f, -0.14f, 2.0f)
                reflectiveCurveToRelative(0.06f, 1.34f, 0.14f, 2.0f)
                lineTo(4.26f, 14.0f)
                close()
                moveTo(5.08f, 16.0f)
                horizontalLineToRelative(2.95f)
                curveToRelative(0.32f, 1.25f, 0.78f, 2.45f, 1.38f, 3.56f)
                curveToRelative(-1.84f, -0.63f, -3.37f, -1.9f, -4.33f, -3.56f)
                close()
                moveTo(8.03f, 8.0f)
                lineTo(5.08f, 8.0f)
                curveToRelative(0.96f, -1.66f, 2.49f, -2.93f, 4.33f, -3.56f)
                curveTo(8.81f, 5.55f, 8.35f, 6.75f, 8.03f, 8.0f)
                close()
                moveTo(12.0f, 19.96f)
                curveToRelative(-0.83f, -1.2f, -1.48f, -2.53f, -1.91f, -3.96f)
                horizontalLineToRelative(3.82f)
                curveToRelative(-0.43f, 1.43f, -1.08f, 2.76f, -1.91f, 3.96f)
                close()
                moveTo(14.34f, 14.0f)
                lineTo(9.66f, 14.0f)
                curveToRelative(-0.09f, -0.66f, -0.16f, -1.32f, -0.16f, -2.0f)
                reflectiveCurveToRelative(0.07f, -1.35f, 0.16f, -2.0f)
                horizontalLineToRelative(4.68f)
                curveToRelative(0.09f, 0.65f, 0.16f, 1.32f, 0.16f, 2.0f)
                reflectiveCurveToRelative(-0.07f, 1.34f, -0.16f, 2.0f)
                close()
                moveTo(14.59f, 19.56f)
                curveToRelative(0.6f, -1.11f, 1.06f, -2.31f, 1.38f, -3.56f)
                horizontalLineToRelative(2.95f)
                curveToRelative(-0.96f, 1.65f, -2.49f, 2.93f, -4.33f, 3.56f)
                close()
                moveTo(16.36f, 14.0f)
                curveToRelative(0.08f, -0.66f, 0.14f, -1.32f, 0.14f, -2.0f)
                reflectiveCurveToRelative(-0.06f, -1.34f, -0.14f, -2.0f)
                horizontalLineToRelative(3.38f)
                curveToRelative(0.16f, 0.64f, 0.26f, 1.31f, 0.26f, 2.0f)
                reflectiveCurveToRelative(-0.1f, 1.36f, -0.26f, 2.0f)
                horizontalLineToRelative(-3.38f)
                close()
            }
        }
        return _outlined_language!!
    }

private var _outlined_language: ImageVector? = null

// Outlined.LightMode
internal val Icons.Outlined.LightMode: ImageVector
    get() {
        if (_outlined_lightMode != null) {
            return _outlined_lightMode!!
        }
        _outlined_lightMode = materialIcon(name = "Outlined.LightMode") {
            materialPath {
                moveTo(12.0f, 9.0f)
                curveToRelative(1.65f, 0.0f, 3.0f, 1.35f, 3.0f, 3.0f)
                reflectiveCurveToRelative(-1.35f, 3.0f, -3.0f, 3.0f)
                reflectiveCurveToRelative(-3.0f, -1.35f, -3.0f, -3.0f)
                reflectiveCurveTo(10.35f, 9.0f, 12.0f, 9.0f)
                moveTo(12.0f, 7.0f)
                curveToRelative(-2.76f, 0.0f, -5.0f, 2.24f, -5.0f, 5.0f)
                reflectiveCurveToRelative(2.24f, 5.0f, 5.0f, 5.0f)
                reflectiveCurveToRelative(5.0f, -2.24f, 5.0f, -5.0f)
                reflectiveCurveTo(14.76f, 7.0f, 12.0f, 7.0f)
                lineTo(12.0f, 7.0f)
                close()
                moveTo(2.0f, 13.0f)
                lineToRelative(2.0f, 0.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                reflectiveCurveToRelative(-0.45f, -1.0f, -1.0f, -1.0f)
                lineToRelative(-2.0f, 0.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                reflectiveCurveTo(1.45f, 13.0f, 2.0f, 13.0f)
                close()
                moveTo(20.0f, 13.0f)
                lineToRelative(2.0f, 0.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                reflectiveCurveToRelative(-0.45f, -1.0f, -1.0f, -1.0f)
                lineToRelative(-2.0f, 0.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                reflectiveCurveTo(19.45f, 13.0f, 20.0f, 13.0f)
                close()
                moveTo(11.0f, 2.0f)
                verticalLineToRelative(2.0f)
                curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
                reflectiveCurveToRelative(1.0f, -0.45f, 1.0f, -1.0f)
                verticalLineTo(2.0f)
                curveToRelative(0.0f, -0.55f, -0.45f, -1.0f, -1.0f, -1.0f)
                reflectiveCurveTo(11.0f, 1.45f, 11.0f, 2.0f)
                close()
                moveTo(11.0f, 20.0f)
                verticalLineToRelative(2.0f)
                curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
                reflectiveCurveToRelative(1.0f, -0.45f, 1.0f, -1.0f)
                verticalLineToRelative(-2.0f)
                curveToRelative(0.0f, -0.55f, -0.45f, -1.0f, -1.0f, -1.0f)
                curveTo(11.45f, 19.0f, 11.0f, 19.45f, 11.0f, 20.0f)
                close()
                moveTo(5.99f, 4.58f)
                curveToRelative(-0.39f, -0.39f, -1.03f, -0.39f, -1.41f, 0.0f)
                curveToRelative(-0.39f, 0.39f, -0.39f, 1.03f, 0.0f, 1.41f)
                lineToRelative(1.06f, 1.06f)
                curveToRelative(0.39f, 0.39f, 1.03f, 0.39f, 1.41f, 0.0f)
                reflectiveCurveToRelative(0.39f, -1.03f, 0.0f, -1.41f)
                lineTo(5.99f, 4.58f)
                close()
                moveTo(18.36f, 16.95f)
                curveToRelative(-0.39f, -0.39f, -1.03f, -0.39f, -1.41f, 0.0f)
                curveToRelative(-0.39f, 0.39f, -0.39f, 1.03f, 0.0f, 1.41f)
                lineToRelative(1.06f, 1.06f)
                curveToRelative(0.39f, 0.39f, 1.03f, 0.39f, 1.41f, 0.0f)
                curveToRelative(0.39f, -0.39f, 0.39f, -1.03f, 0.0f, -1.41f)
                lineTo(18.36f, 16.95f)
                close()
                moveTo(19.42f, 5.99f)
                curveToRelative(0.39f, -0.39f, 0.39f, -1.03f, 0.0f, -1.41f)
                curveToRelative(-0.39f, -0.39f, -1.03f, -0.39f, -1.41f, 0.0f)
                lineToRelative(-1.06f, 1.06f)
                curveToRelative(-0.39f, 0.39f, -0.39f, 1.03f, 0.0f, 1.41f)
                reflectiveCurveToRelative(1.03f, 0.39f, 1.41f, 0.0f)
                lineTo(19.42f, 5.99f)
                close()
                moveTo(7.05f, 18.36f)
                curveToRelative(0.39f, -0.39f, 0.39f, -1.03f, 0.0f, -1.41f)
                curveToRelative(-0.39f, -0.39f, -1.03f, -0.39f, -1.41f, 0.0f)
                lineToRelative(-1.06f, 1.06f)
                curveToRelative(-0.39f, 0.39f, -0.39f, 1.03f, 0.0f, 1.41f)
                reflectiveCurveToRelative(1.03f, 0.39f, 1.41f, 0.0f)
                lineTo(7.05f, 18.36f)
                close()
            }
        }
        return _outlined_lightMode!!
    }

private var _outlined_lightMode: ImageVector? = null

// Outlined.Memory
internal val Icons.Outlined.Memory: ImageVector
    get() {
        if (_outlined_memory != null) {
            return _outlined_memory!!
        }
        _outlined_memory = materialIcon(name = "Outlined.Memory") {
            materialPath {
                moveTo(15.0f, 9.0f)
                lineTo(9.0f, 9.0f)
                verticalLineToRelative(6.0f)
                horizontalLineToRelative(6.0f)
                lineTo(15.0f, 9.0f)
                close()
                moveTo(13.0f, 13.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(2.0f)
                close()
                moveTo(21.0f, 11.0f)
                lineTo(21.0f, 9.0f)
                horizontalLineToRelative(-2.0f)
                lineTo(19.0f, 7.0f)
                curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                horizontalLineToRelative(-2.0f)
                lineTo(15.0f, 3.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(-2.0f)
                lineTo(11.0f, 3.0f)
                lineTo(9.0f, 3.0f)
                verticalLineToRelative(2.0f)
                lineTo(7.0f, 5.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(2.0f)
                lineTo(3.0f, 9.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(2.0f)
                lineTo(3.0f, 13.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(2.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(2.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(2.0f)
                close()
                moveTo(17.0f, 17.0f)
                lineTo(7.0f, 17.0f)
                lineTo(7.0f, 7.0f)
                horizontalLineToRelative(10.0f)
                verticalLineToRelative(10.0f)
                close()
            }
        }
        return _outlined_memory!!
    }

private var _outlined_memory: ImageVector? = null

// Outlined.Numbers
internal val Icons.Outlined.Numbers: ImageVector
    get() {
        if (_outlined_numbers != null) {
            return _outlined_numbers!!
        }
        _outlined_numbers = materialIcon(name = "Outlined.Numbers") {
            materialPath {
                moveTo(20.5f, 10.0f)
                lineTo(21.0f, 8.0f)
                horizontalLineToRelative(-4.0f)
                lineToRelative(1.0f, -4.0f)
                horizontalLineToRelative(-2.0f)
                lineToRelative(-1.0f, 4.0f)
                horizontalLineToRelative(-4.0f)
                lineToRelative(1.0f, -4.0f)
                horizontalLineToRelative(-2.0f)
                lineTo(9.0f, 8.0f)
                horizontalLineTo(5.0f)
                lineToRelative(-0.5f, 2.0f)
                horizontalLineToRelative(4.0f)
                lineToRelative(-1.0f, 4.0f)
                horizontalLineToRelative(-4.0f)
                lineTo(3.0f, 16.0f)
                horizontalLineToRelative(4.0f)
                lineToRelative(-1.0f, 4.0f)
                horizontalLineToRelative(2.0f)
                lineToRelative(1.0f, -4.0f)
                horizontalLineToRelative(4.0f)
                lineToRelative(-1.0f, 4.0f)
                horizontalLineToRelative(2.0f)
                lineToRelative(1.0f, -4.0f)
                horizontalLineToRelative(4.0f)
                lineToRelative(0.5f, -2.0f)
                horizontalLineToRelative(-4.0f)
                lineToRelative(1.0f, -4.0f)
                horizontalLineTo(20.5f)
                close()
                moveTo(13.5f, 14.0f)
                horizontalLineToRelative(-4.0f)
                lineToRelative(1.0f, -4.0f)
                horizontalLineToRelative(4.0f)
                lineTo(13.5f, 14.0f)
                close()
            }
        }
        return _outlined_numbers!!
    }

private var _outlined_numbers: ImageVector? = null

// Outlined.Palette
internal val Icons.Outlined.Palette: ImageVector
    get() {
        if (_outlined_palette != null) {
            return _outlined_palette!!
        }
        _outlined_palette = materialIcon(name = "Outlined.Palette") {
            materialPath {
                moveTo(12.0f, 22.0f)
                curveTo(6.49f, 22.0f, 2.0f, 17.51f, 2.0f, 12.0f)
                reflectiveCurveTo(6.49f, 2.0f, 12.0f, 2.0f)
                reflectiveCurveToRelative(10.0f, 4.04f, 10.0f, 9.0f)
                curveToRelative(0.0f, 3.31f, -2.69f, 6.0f, -6.0f, 6.0f)
                horizontalLineToRelative(-1.77f)
                curveToRelative(-0.28f, 0.0f, -0.5f, 0.22f, -0.5f, 0.5f)
                curveToRelative(0.0f, 0.12f, 0.05f, 0.23f, 0.13f, 0.33f)
                curveToRelative(0.41f, 0.47f, 0.64f, 1.06f, 0.64f, 1.67f)
                curveTo(14.5f, 20.88f, 13.38f, 22.0f, 12.0f, 22.0f)
                close()
                moveTo(12.0f, 4.0f)
                curveToRelative(-4.41f, 0.0f, -8.0f, 3.59f, -8.0f, 8.0f)
                reflectiveCurveToRelative(3.59f, 8.0f, 8.0f, 8.0f)
                curveToRelative(0.28f, 0.0f, 0.5f, -0.22f, 0.5f, -0.5f)
                curveToRelative(0.0f, -0.16f, -0.08f, -0.28f, -0.14f, -0.35f)
                curveToRelative(-0.41f, -0.46f, -0.63f, -1.05f, -0.63f, -1.65f)
                curveToRelative(0.0f, -1.38f, 1.12f, -2.5f, 2.5f, -2.5f)
                horizontalLineTo(16.0f)
                curveToRelative(2.21f, 0.0f, 4.0f, -1.79f, 4.0f, -4.0f)
                curveTo(20.0f, 7.14f, 16.41f, 4.0f, 12.0f, 4.0f)
                close()
            }
            materialPath {
                moveTo(6.5f, 11.5f)
                moveToRelative(-1.5f, 0.0f)
                arcToRelative(1.5f, 1.5f, 0.0f, true, true, 3.0f, 0.0f)
                arcToRelative(1.5f, 1.5f, 0.0f, true, true, -3.0f, 0.0f)
            }
            materialPath {
                moveTo(9.5f, 7.5f)
                moveToRelative(-1.5f, 0.0f)
                arcToRelative(1.5f, 1.5f, 0.0f, true, true, 3.0f, 0.0f)
                arcToRelative(1.5f, 1.5f, 0.0f, true, true, -3.0f, 0.0f)
            }
            materialPath {
                moveTo(14.5f, 7.5f)
                moveToRelative(-1.5f, 0.0f)
                arcToRelative(1.5f, 1.5f, 0.0f, true, true, 3.0f, 0.0f)
                arcToRelative(1.5f, 1.5f, 0.0f, true, true, -3.0f, 0.0f)
            }
            materialPath {
                moveTo(17.5f, 11.5f)
                moveToRelative(-1.5f, 0.0f)
                arcToRelative(1.5f, 1.5f, 0.0f, true, true, 3.0f, 0.0f)
                arcToRelative(1.5f, 1.5f, 0.0f, true, true, -3.0f, 0.0f)
            }
        }
        return _outlined_palette!!
    }

private var _outlined_palette: ImageVector? = null

// Outlined.Sort
internal val Icons.Outlined.Sort: ImageVector
    get() {
        if (_outlined_sort != null) {
            return _outlined_sort!!
        }
        _outlined_sort = materialIcon(name = "Outlined.Sort") {
            materialPath {
                moveTo(3.0f, 18.0f)
                horizontalLineToRelative(6.0f)
                verticalLineToRelative(-2.0f)
                lineTo(3.0f, 16.0f)
                verticalLineToRelative(2.0f)
                close()
                moveTo(3.0f, 6.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(18.0f)
                lineTo(21.0f, 6.0f)
                lineTo(3.0f, 6.0f)
                close()
                moveTo(3.0f, 13.0f)
                horizontalLineToRelative(12.0f)
                verticalLineToRelative(-2.0f)
                lineTo(3.0f, 11.0f)
                verticalLineToRelative(2.0f)
                close()
            }
        }
        return _outlined_sort!!
    }

private var _outlined_sort: ImageVector? = null

// Outlined.Storage
internal val Icons.Outlined.Storage: ImageVector
    get() {
        if (_outlined_storage != null) {
            return _outlined_storage!!
        }
        _outlined_storage = materialIcon(name = "Outlined.Storage") {
            materialPath {
                moveTo(2.0f, 20.0f)
                horizontalLineToRelative(20.0f)
                verticalLineToRelative(-4.0f)
                lineTo(2.0f, 16.0f)
                verticalLineToRelative(4.0f)
                close()
                moveTo(4.0f, 17.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(2.0f)
                lineTo(4.0f, 19.0f)
                verticalLineToRelative(-2.0f)
                close()
                moveTo(2.0f, 4.0f)
                verticalLineToRelative(4.0f)
                horizontalLineToRelative(20.0f)
                lineTo(22.0f, 4.0f)
                lineTo(2.0f, 4.0f)
                close()
                moveTo(6.0f, 7.0f)
                lineTo(4.0f, 7.0f)
                lineTo(4.0f, 5.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(2.0f)
                close()
                moveTo(2.0f, 14.0f)
                horizontalLineToRelative(20.0f)
                verticalLineToRelative(-4.0f)
                lineTo(2.0f, 10.0f)
                verticalLineToRelative(4.0f)
                close()
                moveTo(4.0f, 11.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(2.0f)
                lineTo(4.0f, 13.0f)
                verticalLineToRelative(-2.0f)
                close()
            }
        }
        return _outlined_storage!!
    }

private var _outlined_storage: ImageVector? = null

// Outlined.TextFields
internal val Icons.Outlined.TextFields: ImageVector
    get() {
        if (_outlined_textFields != null) {
            return _outlined_textFields!!
        }
        _outlined_textFields = materialIcon(name = "Outlined.TextFields") {
            materialPath {
                moveTo(2.5f, 4.0f)
                verticalLineToRelative(3.0f)
                horizontalLineToRelative(5.0f)
                verticalLineToRelative(12.0f)
                horizontalLineToRelative(3.0f)
                lineTo(10.5f, 7.0f)
                horizontalLineToRelative(5.0f)
                lineTo(15.5f, 4.0f)
                horizontalLineToRelative(-13.0f)
                close()
                moveTo(21.5f, 9.0f)
                horizontalLineToRelative(-9.0f)
                verticalLineToRelative(3.0f)
                horizontalLineToRelative(3.0f)
                verticalLineToRelative(7.0f)
                horizontalLineToRelative(3.0f)
                verticalLineToRelative(-7.0f)
                horizontalLineToRelative(3.0f)
                lineTo(21.5f, 9.0f)
                close()
            }
        }
        return _outlined_textFields!!
    }

private var _outlined_textFields: ImageVector? = null

// Rounded.FormatSize
internal val Icons.Rounded.FormatSize: ImageVector
    get() {
        if (_rounded_formatSize != null) {
            return _rounded_formatSize!!
        }
        _rounded_formatSize = materialIcon(name = "Rounded.FormatSize") {
            materialPath {
                moveTo(9.0f, 5.5f)
                curveToRelative(0.0f, 0.83f, 0.67f, 1.5f, 1.5f, 1.5f)
                horizontalLineTo(14.0f)
                verticalLineToRelative(10.5f)
                curveToRelative(0.0f, 0.83f, 0.67f, 1.5f, 1.5f, 1.5f)
                reflectiveCurveToRelative(1.5f, -0.67f, 1.5f, -1.5f)
                verticalLineTo(7.0f)
                horizontalLineToRelative(3.5f)
                curveToRelative(0.83f, 0.0f, 1.5f, -0.67f, 1.5f, -1.5f)
                reflectiveCurveTo(21.33f, 4.0f, 20.5f, 4.0f)
                horizontalLineToRelative(-10.0f)
                curveTo(9.67f, 4.0f, 9.0f, 4.67f, 9.0f, 5.5f)
                close()
                moveTo(4.5f, 12.0f)
                horizontalLineTo(6.0f)
                verticalLineToRelative(5.5f)
                curveToRelative(0.0f, 0.83f, 0.67f, 1.5f, 1.5f, 1.5f)
                reflectiveCurveTo(9.0f, 18.33f, 9.0f, 17.5f)
                verticalLineTo(12.0f)
                horizontalLineToRelative(1.5f)
                curveToRelative(0.83f, 0.0f, 1.5f, -0.67f, 1.5f, -1.5f)
                reflectiveCurveTo(11.33f, 9.0f, 10.5f, 9.0f)
                horizontalLineToRelative(-6.0f)
                curveTo(3.67f, 9.0f, 3.0f, 9.67f, 3.0f, 10.5f)
                reflectiveCurveTo(3.67f, 12.0f, 4.5f, 12.0f)
                close()
            }
        }
        return _rounded_formatSize!!
    }

private var _rounded_formatSize: ImageVector? = null

// Rounded.Tune
internal val Icons.Rounded.Tune: ImageVector
    get() {
        if (_rounded_tune != null) {
            return _rounded_tune!!
        }
        _rounded_tune = materialIcon(name = "Rounded.Tune") {
            materialPath {
                moveTo(3.0f, 18.0f)
                curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
                horizontalLineToRelative(5.0f)
                verticalLineToRelative(-2.0f)
                lineTo(4.0f, 17.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                close()
                moveTo(3.0f, 6.0f)
                curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
                horizontalLineToRelative(9.0f)
                lineTo(13.0f, 5.0f)
                lineTo(4.0f, 5.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                close()
                moveTo(13.0f, 20.0f)
                verticalLineToRelative(-1.0f)
                horizontalLineToRelative(7.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                reflectiveCurveToRelative(-0.45f, -1.0f, -1.0f, -1.0f)
                horizontalLineToRelative(-7.0f)
                verticalLineToRelative(-1.0f)
                curveToRelative(0.0f, -0.55f, -0.45f, -1.0f, -1.0f, -1.0f)
                reflectiveCurveToRelative(-1.0f, 0.45f, -1.0f, 1.0f)
                verticalLineToRelative(4.0f)
                curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
                reflectiveCurveToRelative(1.0f, -0.45f, 1.0f, -1.0f)
                close()
                moveTo(7.0f, 10.0f)
                verticalLineToRelative(1.0f)
                lineTo(4.0f, 11.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                reflectiveCurveToRelative(0.45f, 1.0f, 1.0f, 1.0f)
                horizontalLineToRelative(3.0f)
                verticalLineToRelative(1.0f)
                curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
                reflectiveCurveToRelative(1.0f, -0.45f, 1.0f, -1.0f)
                verticalLineToRelative(-4.0f)
                curveToRelative(0.0f, -0.55f, -0.45f, -1.0f, -1.0f, -1.0f)
                reflectiveCurveToRelative(-1.0f, 0.45f, -1.0f, 1.0f)
                close()
                moveTo(21.0f, 12.0f)
                curveToRelative(0.0f, -0.55f, -0.45f, -1.0f, -1.0f, -1.0f)
                horizontalLineToRelative(-9.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(9.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                close()
                moveTo(16.0f, 9.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                lineTo(17.0f, 7.0f)
                horizontalLineToRelative(3.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                reflectiveCurveToRelative(-0.45f, -1.0f, -1.0f, -1.0f)
                horizontalLineToRelative(-3.0f)
                lineTo(17.0f, 4.0f)
                curveToRelative(0.0f, -0.55f, -0.45f, -1.0f, -1.0f, -1.0f)
                reflectiveCurveToRelative(-1.0f, 0.45f, -1.0f, 1.0f)
                verticalLineToRelative(4.0f)
                curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
                close()
            }
        }
        return _rounded_tune!!
    }

private var _rounded_tune: ImageVector? = null

// Rounded.ViewCarousel
internal val Icons.Rounded.ViewCarousel: ImageVector
    get() {
        if (_rounded_viewCarousel != null) {
            return _rounded_viewCarousel!!
        }
        _rounded_viewCarousel = materialIcon(name = "Rounded.ViewCarousel") {
            materialPath {
                moveTo(3.0f, 7.0f)
                horizontalLineToRelative(2.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, 0.45f, 1.0f, 1.0f)
                verticalLineToRelative(8.0f)
                curveToRelative(0.0f, 0.55f, -0.45f, 1.0f, -1.0f, 1.0f)
                horizontalLineTo(3.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, -0.45f, -1.0f, -1.0f)
                verticalLineTo(8.0f)
                curveTo(2.0f, 7.45f, 2.45f, 7.0f, 3.0f, 7.0f)
                close()
                moveTo(8.0f, 19.0f)
                horizontalLineToRelative(8.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                verticalLineTo(6.0f)
                curveToRelative(0.0f, -0.55f, -0.45f, -1.0f, -1.0f, -1.0f)
                horizontalLineTo(8.0f)
                curveTo(7.45f, 5.0f, 7.0f, 5.45f, 7.0f, 6.0f)
                verticalLineToRelative(12.0f)
                curveTo(7.0f, 18.55f, 7.45f, 19.0f, 8.0f, 19.0f)
                close()
                moveTo(19.0f, 7.0f)
                horizontalLineToRelative(2.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, 0.45f, 1.0f, 1.0f)
                verticalLineToRelative(8.0f)
                curveToRelative(0.0f, 0.55f, -0.45f, 1.0f, -1.0f, 1.0f)
                horizontalLineToRelative(-2.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, -0.45f, -1.0f, -1.0f)
                verticalLineTo(8.0f)
                curveTo(18.0f, 7.45f, 18.45f, 7.0f, 19.0f, 7.0f)
                close()
            }
        }
        return _rounded_viewCarousel!!
    }

private var _rounded_viewCarousel: ImageVector? = null

// Rounded.ViewStream
internal val Icons.Rounded.ViewStream: ImageVector
    get() {
        if (_rounded_viewStream != null) {
            return _rounded_viewStream!!
        }
        _rounded_viewStream = materialIcon(name = "Rounded.ViewStream") {
            materialPath {
                moveTo(3.0f, 17.0f)
                verticalLineToRelative(-2.0f)
                curveToRelative(0.0f, -1.1f, 0.9f, -2.0f, 2.0f, -2.0f)
                horizontalLineToRelative(14.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, 0.9f, 2.0f, 2.0f)
                verticalLineToRelative(2.0f)
                curveToRelative(0.0f, 1.1f, -0.9f, 2.0f, -2.0f, 2.0f)
                horizontalLineTo(5.0f)
                curveTo(3.9f, 19.0f, 3.0f, 18.1f, 3.0f, 17.0f)
                close()
                moveTo(3.0f, 7.0f)
                verticalLineToRelative(2.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(14.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                verticalLineTo(7.0f)
                curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                horizontalLineTo(5.0f)
                curveTo(3.9f, 5.0f, 3.0f, 5.9f, 3.0f, 7.0f)
                close()
            }
        }
        return _rounded_viewStream!!
    }

private var _rounded_viewStream: ImageVector? = null

// Rounded.WbSunny
internal val Icons.Rounded.WbSunny: ImageVector
    get() {
        if (_rounded_wbSunny != null) {
            return _rounded_wbSunny!!
        }
        _rounded_wbSunny = materialIcon(name = "Rounded.WbSunny") {
            materialPath {
                moveTo(6.05f, 4.14f)
                lineToRelative(-0.39f, -0.39f)
                curveToRelative(-0.39f, -0.39f, -1.02f, -0.38f, -1.4f, 0.0f)
                lineToRelative(-0.01f, 0.01f)
                curveToRelative(-0.39f, 0.39f, -0.39f, 1.02f, 0.0f, 1.4f)
                lineToRelative(0.39f, 0.39f)
                curveToRelative(0.39f, 0.39f, 1.01f, 0.39f, 1.4f, 0.0f)
                lineToRelative(0.01f, -0.01f)
                curveToRelative(0.39f, -0.38f, 0.39f, -1.02f, 0.0f, -1.4f)
                close()
                moveTo(3.01f, 10.5f)
                lineTo(1.99f, 10.5f)
                curveToRelative(-0.55f, 0.0f, -0.99f, 0.44f, -0.99f, 0.99f)
                verticalLineToRelative(0.01f)
                curveToRelative(0.0f, 0.55f, 0.44f, 0.99f, 0.99f, 0.99f)
                lineTo(3.0f, 12.49f)
                curveToRelative(0.56f, 0.01f, 1.0f, -0.43f, 1.0f, -0.98f)
                verticalLineToRelative(-0.01f)
                curveToRelative(0.0f, -0.56f, -0.44f, -1.0f, -0.99f, -1.0f)
                close()
                moveTo(12.01f, 0.55f)
                lineTo(12.0f, 0.55f)
                curveToRelative(-0.56f, 0.0f, -1.0f, 0.44f, -1.0f, 0.99f)
                verticalLineToRelative(0.96f)
                curveToRelative(0.0f, 0.55f, 0.44f, 0.99f, 0.99f, 0.99f)
                lineTo(12.0f, 3.49f)
                curveToRelative(0.56f, 0.01f, 1.0f, -0.43f, 1.0f, -0.98f)
                verticalLineToRelative(-0.97f)
                curveToRelative(0.0f, -0.55f, -0.44f, -0.99f, -0.99f, -0.99f)
                close()
                moveTo(19.75f, 3.76f)
                curveToRelative(-0.39f, -0.39f, -1.02f, -0.39f, -1.41f, -0.01f)
                lineToRelative(-0.39f, 0.39f)
                curveToRelative(-0.39f, 0.39f, -0.39f, 1.02f, 0.0f, 1.4f)
                lineToRelative(0.01f, 0.01f)
                curveToRelative(0.39f, 0.39f, 1.02f, 0.39f, 1.4f, 0.0f)
                lineToRelative(0.39f, -0.39f)
                curveToRelative(0.39f, -0.39f, 0.39f, -1.01f, 0.0f, -1.4f)
                close()
                moveTo(17.94f, 18.86f)
                lineToRelative(0.39f, 0.39f)
                curveToRelative(0.39f, 0.39f, 1.02f, 0.39f, 1.41f, 0.0f)
                curveToRelative(0.39f, -0.39f, 0.39f, -1.02f, 0.0f, -1.41f)
                lineToRelative(-0.39f, -0.39f)
                curveToRelative(-0.39f, -0.39f, -1.02f, -0.38f, -1.4f, 0.0f)
                curveToRelative(-0.4f, 0.4f, -0.4f, 1.02f, -0.01f, 1.41f)
                close()
                moveTo(20.0f, 11.49f)
                verticalLineToRelative(0.01f)
                curveToRelative(0.0f, 0.55f, 0.44f, 0.99f, 0.99f, 0.99f)
                lineTo(22.0f, 12.49f)
                curveToRelative(0.55f, 0.0f, 0.99f, -0.44f, 0.99f, -0.99f)
                verticalLineToRelative(-0.01f)
                curveToRelative(0.0f, -0.55f, -0.44f, -0.99f, -0.99f, -0.99f)
                horizontalLineToRelative(-1.01f)
                curveToRelative(-0.55f, 0.0f, -0.99f, 0.44f, -0.99f, 0.99f)
                close()
                moveTo(12.0f, 5.5f)
                curveToRelative(-3.31f, 0.0f, -6.0f, 2.69f, -6.0f, 6.0f)
                reflectiveCurveToRelative(2.69f, 6.0f, 6.0f, 6.0f)
                reflectiveCurveToRelative(6.0f, -2.69f, 6.0f, -6.0f)
                reflectiveCurveToRelative(-2.69f, -6.0f, -6.0f, -6.0f)
                close()
                moveTo(11.99f, 22.45f)
                lineTo(12.0f, 22.45f)
                curveToRelative(0.55f, 0.0f, 0.99f, -0.44f, 0.99f, -0.99f)
                verticalLineToRelative(-0.96f)
                curveToRelative(0.0f, -0.55f, -0.44f, -0.99f, -0.99f, -0.99f)
                horizontalLineToRelative(-0.01f)
                curveToRelative(-0.55f, 0.0f, -0.99f, 0.44f, -0.99f, 0.99f)
                verticalLineToRelative(0.96f)
                curveToRelative(0.0f, 0.55f, 0.44f, 0.99f, 0.99f, 0.99f)
                close()
                moveTo(4.25f, 19.24f)
                curveToRelative(0.39f, 0.39f, 1.02f, 0.39f, 1.41f, 0.0f)
                lineToRelative(0.39f, -0.39f)
                curveToRelative(0.39f, -0.39f, 0.38f, -1.02f, 0.0f, -1.4f)
                lineToRelative(-0.01f, -0.01f)
                curveToRelative(-0.39f, -0.39f, -1.02f, -0.39f, -1.41f, 0.0f)
                lineToRelative(-0.39f, 0.39f)
                curveToRelative(-0.38f, 0.4f, -0.38f, 1.02f, 0.01f, 1.41f)
                close()
            }
        }
        return _rounded_wbSunny!!
    }

private var _rounded_wbSunny: ImageVector? = null
