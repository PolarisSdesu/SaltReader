package moe.gbxin.saltreader.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/** 已添加到书库的 SAF 根目录。 */
@Serializable
data class LibraryRoot(val treeUri: String, val displayName: String)

/**
 * roots.json 的同步缓存。
 *
 * - [all] 同步返回内存缓存，供 `LibraryRepository.roots()` 直接调用；
 * - 首次读取（或写入）在 IO 线程完成，初始化时会预热；
 * - 写盘复用 [writeFileAtomically]。
 */
internal class LibraryRootsStore(context: Context) {
    private val lock = Any()
    private val file = File(File(context.filesDir, "library"), "roots.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    @Volatile
    private var cached: List<LibraryRoot>? = null

    fun all(): List<LibraryRoot> {
        cached?.let { return it }
        return synchronized(lock) {
            cached ?: readFromDisk().also { cached = it }
        }
    }

    fun save(roots: List<LibraryRoot>) {
        synchronized(lock) {
            writeFileAtomically(file) { out ->
                out.write(json.encodeToString(roots).toByteArray(Charsets.UTF_8))
            }
            cached = roots
        }
    }

    fun add(root: LibraryRoot) {
        synchronized(lock) {
            save(all().filterNot { it.treeUri == root.treeUri } + root)
        }
    }

    fun remove(treeUri: String) {
        synchronized(lock) {
            val next = all().filterNot { it.treeUri == treeUri }
            if (next.size == all().size) return
            save(next)
        }
    }

    private fun readFromDisk(): List<LibraryRoot> = runCatching {
        if (!file.exists()) emptyList()
        else json.decodeFromString<List<LibraryRoot>>(file.readText(Charsets.UTF_8))
    }.getOrElse { emptyList() }
}

/**
 * 原子写文件：先写同目录临时文件，flush + fd.sync() 后 rename 替换。
 * 失败会删除临时文件并抛出异常，绝不静默留下半成品。
 */
internal fun writeFileAtomically(target: File, write: (OutputStream) -> Unit) {
    val dir = target.parentFile ?: throw IOException("目标文件路径无效：$target")
    if (!dir.exists() && !dir.mkdirs()) throw IOException("无法创建目录：$dir")
    val tmp = File(dir, target.name + ".tmp")
    try {
        FileOutputStream(tmp).use { fileOut ->
            val buffered = BufferedOutputStream(fileOut, 64 * 1024)
            write(buffered)
            buffered.flush()
            fileOut.fd.sync()
        }
        if (!tmp.renameTo(target)) throw IOException("无法替换文件：$target")
    } catch (t: Throwable) {
        tmp.delete()
        throw t
    }
}
