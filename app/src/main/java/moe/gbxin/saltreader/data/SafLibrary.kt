package moe.gbxin.saltreader.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import java.io.InputStream

/**
 * 一层很薄的 SAF 封装：把「持久化 tree URI + 相对路径」翻译成 Document URI，
 * 并负责列举/查找子文档。
 *
 * 注意：这里的每个方法都可能真的访问 ContentProvider，只能在 IO 线程调用。
 * [SafTree] 自身只是持有 URI 与根文档 id，构造不产生 I/O，因此可以被页面源惰性持有。
 */
internal class SafTree(private val context: Context, val treeUri: Uri) {
    private val resolver: ContentResolver get() = context.contentResolver

    val rootDocumentId: String = DocumentsContract.getTreeDocumentId(treeUri)

    /** SAF 中的一个子文档。documentPath 是本应用自己的逻辑路径（相对 tree root）。 */
    data class Entry(
        val documentId: String,
        val documentPath: String,
        val name: String,
        val isDirectory: Boolean,
        val sizeBytes: Long,
        val lastModified: Long,
        val uri: Uri,
    )

    fun rootEntry(): Entry? = documentEntry(rootDocumentId, "")

    fun rootName(): String? = rootEntry()?.name

    fun documentUri(documentId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

    /** 查询单个文档的元数据；documentPath 由调用方保证（root 传 ""）。 */
    fun documentEntry(documentId: String, documentPath: String): Entry? = runCatching {
        val uri = documentUri(documentId)
        val cursor = resolver.query(uri, PROJECTION, null, null, null) ?: return@runCatching null
        cursor.use {
            if (!it.moveToFirst()) return@use null
            parse(it, documentPath, uri)?.copy(documentPath = documentPath)
        }
    }.getOrNull()

    /** 列举某个目录的直接子级；provider 出错时返回 null（区别于空目录）。 */
    fun children(parentDocumentId: String, parentPath: String): List<Entry>? = runCatching {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val cursor = resolver.query(childrenUri, PROJECTION, null, null, null) ?: return@runCatching null
        cursor.use { cursor ->
            val result = ArrayList<Entry>()
            while (cursor.moveToNext()) {
                parse(cursor, parentPath, null)?.let(result::add)
            }
            result
        }
    }.getOrNull()

    /**
     * 按逻辑路径查找文档：逐级列举子级并匹配显示名，不依赖 provider 的 documentId 格式。
     * 书库深度通常很浅，这点开销可以接受，换来跨 provider 的健壮性。
     */
    fun find(documentPath: String?): Entry? {
        if (documentPath.isNullOrEmpty()) return rootEntry()
        var current = rootEntry() ?: return null
        for (segment in documentPath.split('/')) {
            if (segment.isEmpty()) continue
            if (!current.isDirectory) return null
            val children = children(current.documentId, current.documentPath) ?: return null
            current = children.firstOrNull { it.name == segment } ?: return null
        }
        return current
    }

    fun openInputStream(uri: Uri): InputStream? = runCatching { resolver.openInputStream(uri) }.getOrNull()

    private fun parse(cursor: Cursor, parentPath: String, fixedUri: Uri?): Entry? {
        val documentIdIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        if (documentIdIndex < 0 || cursor.isNull(documentIdIndex)) return null
        val documentId = cursor.getString(documentIdIndex) ?: return null

        val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val name = if (nameIndex >= 0 && !cursor.isNull(nameIndex)) cursor.getString(nameIndex) else null
        if (name.isNullOrBlank()) return null

        val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
        val mime = if (mimeIndex >= 0 && !cursor.isNull(mimeIndex)) cursor.getString(mimeIndex) else null
        val isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR

        val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
        val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else 0L

        val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        val modified = if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) cursor.getLong(modifiedIndex) else 0L

        val documentPath = if (parentPath.isEmpty()) name else "$parentPath/$name"
        val uri = fixedUri ?: documentUri(documentId)
        return Entry(
            documentId = documentId,
            documentPath = documentPath,
            name = name,
            isDirectory = isDirectory,
            sizeBytes = size.coerceAtLeast(0L),
            lastModified = modified.coerceAtLeast(0L),
            uri = uri,
        )
    }

    private companion object {
        val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}

/** 申请持久化读权限；provider 不支持时返回 false，但不影响本次会话的临时授权。 */
internal fun takePersistableReadPermission(context: Context, uri: Uri): Boolean = runCatching {
    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    true
}.getOrElse { false }

/** 把「treeUri + 相对路径」解析成可打开的文件 Document URI；失败/目录返回 null。 */
internal fun resolveDocumentUri(context: Context, treeUri: String, documentPath: String): Uri? = runCatching {
    val tree = SafTree(context, Uri.parse(treeUri))
    tree.find(documentPath)?.takeIf { !it.isDirectory }?.uri
}.getOrNull()
