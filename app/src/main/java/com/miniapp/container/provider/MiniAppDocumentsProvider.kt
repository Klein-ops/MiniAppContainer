package com.miniapp.container.provider

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import android.content.Context
import android.os.Process
import android.os.UserManager
import com.miniapp.container.R
import java.io.File
import java.io.FileNotFoundException

/**
 * 数据开放提供者：通过 SAF（DocumentsProvider）把蜗壳的应用私有目录（dataDir）
 * 暴露给其他应用，供其在用户授权后浏览/读取。
 *
 * 安全：所有 documentId 均在 dataDir 内解析，禁止 `..` 逃逸；由系统文档选择器
 * 授权后其他应用方可访问，属用户主动开放行为。
 */
class MiniAppDocumentsProvider : DocumentsProvider() {

    companion object {
        private const val ROOT_ID = "root"
        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_ICON
        )
        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS
        )
    }

    private fun rootDir(): File = context!!.dataDir

    /**
     * 当前是否可以提供服务。
     *
     * 本 provider 为 exported，可能被系统/其他应用在**锁屏（Direct Boot 未解锁）**阶段查询，
     * 也可能运行在隔离进程。这两种情形下 `dataDir` 不可访问，直接返回空列表而非抛异常。
     */
    private fun canServe(): Boolean {
        if (Process.isIsolated()) return false
        val ctx = context ?: return false
        return try {
            (ctx.getSystemService(Context.USER_SERVICE) as UserManager).isUserUnlocked
        } catch (_: Throwable) {
            false
        }
    }

    private fun emptyCursor(projection: Array<out String>?, fallback: Array<String>): Cursor =
        MatrixCursor(projection ?: fallback)

    /** documentId -> 真实文件（严格限制在 rootDir 内）。 */
    private fun fileFor(docId: String): File {
        val root = rootDir().canonicalFile
        val target = if (docId == ROOT_ID || docId.isEmpty()) root
        else File(root, docId).canonicalFile
        if (target != root && !target.path.startsWith(root.path + File.separator)) {
            throw SecurityException("禁止访问开放目录之外: $docId")
        }
        return target
    }

    private fun docIdFor(file: File): String {
        val root = rootDir().canonicalFile
        val f = file.canonicalFile
        return if (f == root) ROOT_ID else f.relativeTo(root).path
    }

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        if (!canServe()) return emptyCursor(projection, DEFAULT_ROOT_PROJECTION)
        val cursor = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        cursor.newRow().apply {
            add(DocumentsContract.Root.COLUMN_ROOT_ID, ROOT_ID)
            add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT_ID)
            add(DocumentsContract.Root.COLUMN_TITLE, "MiniAppContainer Data")
            add(
                DocumentsContract.Root.COLUMN_FLAGS,
                DocumentsContract.Root.FLAG_LOCAL_ONLY
                    or DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD
                    or DocumentsContract.Root.FLAG_SUPPORTS_CREATE
            )
            add(DocumentsContract.Root.COLUMN_ICON, R.mipmap.ic_launcher)
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        if (!canServe()) return emptyCursor(projection, DEFAULT_DOCUMENT_PROJECTION)
        val file = fileFor(documentId)
        // 不存在的 document 必须抛异常：此前返回空行导致 MT 等管理器
        // 查询目标时误判"文件已存在"，拒绝新建/新建文件夹
        if (!file.exists()) throw FileNotFoundException("不存在: $documentId")
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        includeFile(cursor, file)
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        if (!canServe()) return emptyCursor(projection, DEFAULT_DOCUMENT_PROJECTION)
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val parent = fileFor(parentDocumentId)
        parent.listFiles()?.sortedBy { it.name }?.forEach { includeFile(cursor, it) }
        return cursor
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        if (!canServe()) throw FileNotFoundException("存储不可用（用户未解锁）")
        val file = fileFor(documentId)
        if (!file.exists()) throw FileNotFoundException(documentId)
        val flags = when (mode) {
            "r" -> ParcelFileDescriptor.MODE_READ_ONLY
            "w", "wt" -> ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE
            "rw" -> ParcelFileDescriptor.MODE_READ_WRITE
            "rwt" -> ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE
            else -> ParcelFileDescriptor.MODE_READ_ONLY
        }
        return ParcelFileDescriptor.open(file, flags)
    }

    /**
     * 创建文件/目录（复制粘贴、新建文件时系统调用）。
     * 此前目录宣称了 FLAG_DIR_SUPPORTS_CREATE 却未实现本方法，导致
     * 其他应用无法向开放目录写入任何内容。文件名清洗防 `..`/分隔符逃逸。
     */
    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String
    ): String {
        if (!canServe()) throw FileNotFoundException("存储不可用（用户未解锁）")
        val parent = fileFor(parentDocumentId)
        if (!parent.isDirectory) throw FileNotFoundException("父目录不存在: $parentDocumentId")
        val safe = displayName.replace('/', '_').replace('\\', '_')
        val file = uniqueFile(parent, safe)
        val ok = if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) file.mkdirs()
        else file.createNewFile()
        if (!ok) throw java.io.IOException("创建失败: $safe")
        return docIdFor(file)
    }

    /** 删除文件/目录（MT 等管理器"覆盖保存"先建临时文件再删旧文件）。 */
    override fun deleteDocument(documentId: String) {
        val file = fileFor(documentId)
        if (!file.exists()) throw FileNotFoundException("不存在: $documentId")
        if (!file.delete()) throw java.io.IOException("删除失败: $documentId")
    }

    /** 重命名（"覆盖保存"流程把临时文件改回原名时调用）。 */
    override fun renameDocument(documentId: String, displayName: String): String {
        val file = fileFor(documentId)
        if (!file.exists()) throw FileNotFoundException("不存在: $documentId")
        val safe = displayName.replace('/', '_').replace('\\', '_')
        val target = File(file.parentFile, safe)
        if (!file.renameTo(target)) throw java.io.IOException("重命名失败: $documentId")
        return docIdFor(target)
    }

    /** 重名时追加 (2)、(3)… 生成唯一文件名。 */
    private fun uniqueFile(parent: File, displayName: String): File {
        var file = File(parent, displayName)
        if (!file.exists()) return file
        val dot = displayName.lastIndexOf('.')
        val base = if (dot > 0) displayName.substring(0, dot) else displayName
        val ext = if (dot > 0) displayName.substring(dot) else ""
        var i = 2
        while (file.exists()) {
            file = File(parent, "$base ($i)$ext")
            i++
        }
        return file
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = try {
        val parent = fileFor(parentDocumentId).path
        val child = fileFor(documentId).path
        child.startsWith(parent + File.separator)
    } catch (_: Exception) {
        false
    }

    private fun includeFile(cursor: MatrixCursor, file: File) {
        val flags = if (file.isDirectory) {
            DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
        } else {
            DocumentsContract.Document.FLAG_SUPPORTS_WRITE
                or DocumentsContract.Document.FLAG_SUPPORTS_DELETE
                or DocumentsContract.Document.FLAG_SUPPORTS_RENAME
        }
        // 只输出调用方 projection 声明过的列，避免按列名 add 未声明列崩溃
        cursor.newRow().apply {
            if (cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID) >= 0)
                add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, docIdFor(file))
            if (cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME) >= 0)
                add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, file.name)
            if (cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE) >= 0)
                add(DocumentsContract.Document.COLUMN_MIME_TYPE, getMimeType(file))
            if (cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE) >= 0)
                add(DocumentsContract.Document.COLUMN_SIZE, if (file.isFile) file.length() else 0)
            if (cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED) >= 0)
                add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, file.lastModified())
            if (cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS) >= 0)
                add(DocumentsContract.Document.COLUMN_FLAGS, flags)
        }
    }

    private fun getMimeType(file: File): String {
        if (file.isDirectory) return DocumentsContract.Document.MIME_TYPE_DIR
        val ext = file.name.substringAfterLast('.', "")
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase())
            ?: "application/octet-stream"
    }
}
