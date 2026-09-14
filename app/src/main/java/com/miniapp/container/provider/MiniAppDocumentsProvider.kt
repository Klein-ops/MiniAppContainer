package com.miniapp.container.provider

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
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
        val cursor = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        cursor.newRow().apply {
            add(DocumentsContract.Root.COLUMN_ROOT_ID, ROOT_ID)
            add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT_ID)
            add(DocumentsContract.Root.COLUMN_TITLE, "蜗壳应用数据")
            add(
                DocumentsContract.Root.COLUMN_FLAGS,
                DocumentsContract.Root.FLAG_LOCAL_ONLY or DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD
            )
            add(DocumentsContract.Root.COLUMN_ICON, R.mipmap.ic_launcher)
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val file = fileFor(documentId)
        includeFile(cursor, file)
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
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
        val file = fileFor(documentId)
        if (!file.exists()) throw FileNotFoundException(documentId)
        val flags = when (mode) {
            "r" -> ParcelFileDescriptor.MODE_READ_ONLY
            "w", "wt" -> ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE
            "rw", "rwt" -> ParcelFileDescriptor.MODE_READ_WRITE
            else -> ParcelFileDescriptor.MODE_READ_ONLY
        }
        return ParcelFileDescriptor.open(file, flags)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = try {
        val parent = fileFor(parentDocumentId).path
        val child = fileFor(documentId).path
        child.startsWith(parent + File.separator)
    } catch (_: Exception) {
        false
    }

    private fun includeFile(cursor: MatrixCursor, file: File) {
        val flags = if (file.isDirectory)
            DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
        else DocumentsContract.Document.FLAG_SUPPORTS_WRITE
        cursor.newRow().apply {
            add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, docIdFor(file))
            add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, file.name)
            add(DocumentsContract.Document.COLUMN_MIME_TYPE, getMimeType(file))
            add(DocumentsContract.Document.COLUMN_SIZE, if (file.isFile) file.length() else 0)
            add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, file.lastModified())
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
