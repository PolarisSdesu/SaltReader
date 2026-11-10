package moe.gbxin.saltreader.data

import androidx.annotation.StringRes
import moe.gbxin.saltreader.R

/**
 * 数据层用户可见文案的错误码。
 *
 * 无 Context 的纯逻辑（扫描/解析/校验）只产出这里的枚举值，由 [LibraryRepository]
 * 用 `context.getString(code.resId)` 映射成当前语言的文案；Log 与异常 message 不经过这里。
 */
internal enum class DataMessage(@param:StringRes val resId: Int) {
    SOURCE_FOLDER(R.string.data_source_folder),
    SOURCE_LIBRARY(R.string.data_source_library),
    UNTITLED_COMIC(R.string.data_untitled_comic),
    SELECT_FOLDER_FAILED(R.string.data_error_select_folder),
    FOLDER_ACCESS_FAILED(R.string.data_error_folder_access_failed),
    FOLDER_READ_FAILED(R.string.data_error_folder_read_failed),
    SCAN_FAILED(R.string.data_error_scan_failed),
    REFRESH_FAILED(R.string.data_error_refresh_failed),
    PERMISSION_NOT_PERSISTED(R.string.data_error_permission_not_persisted),
    INDEX_SAVE_FAILED(R.string.data_error_index_save_failed),
    SAVE_FAILED(R.string.data_error_save_failed),
    NO_SUPPORTED_BOOKS(R.string.data_error_no_supported_books),
    NO_REFRESHABLE_ROOTS(R.string.data_error_no_refreshable_roots),
    INVALID_ROOT(R.string.data_error_invalid_root),
    ROOT_UNREADABLE(R.string.data_error_root_unreadable),
    DIRECTORY_UNREADABLE(R.string.data_error_directory_unreadable),
    READ_FAILED(R.string.data_error_read_failed),
    NO_READABLE_PAGES(R.string.data_error_no_readable_pages),
}
