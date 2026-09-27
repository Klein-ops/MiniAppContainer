package com.miniapp.container.util

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException

/**
 * SAF（系统文件选择器）读写统一入口。
 *
 * 设计要点：**失败必须抛异常**，不允许静默通过——旧代码用
 * `contentResolver.openOutputStream(uri)?.use { }` 时，uri 打不开会静默跳过，
 * 界面却提示"导出成功"，实际没写入任何内容（已在多处重复出现）。
 *
 * 所有方法均为阻塞 IO，请在 Dispatchers.IO 中调用。
 */
object SafIo {

    /** 读取 SAF uri 全部字节；打不开抛 IOException。 */
    fun readBytes(context: Context, uri: Uri): ByteArray =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException("cannot read selected file (provider returned no stream)")

    /** 把字节写入 SAF uri；打不开抛 IOException。 */
    fun writeBytes(context: Context, uri: Uri, bytes: ByteArray) {
        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: throw IOException("cannot write to selected location (provider returned no stream)")
    }

    /** 把本地文件内容写入 SAF uri。 */
    fun writeFile(context: Context, uri: Uri, src: File) {
        context.contentResolver.openOutputStream(uri)?.use { out ->
            src.inputStream().use { it.copyTo(out) }
        } ?: throw IOException("cannot write to selected location (provider returned no stream)")
    }

    /** 把 SAF uri 内容读到本地文件（自动建父目录）。 */
    fun readToFile(context: Context, uri: Uri, dest: File) {
        context.contentResolver.openInputStream(uri)?.use { IoUtil.copy(it, dest) }
            ?: throw IOException("cannot read selected file (provider returned no stream)")
    }
}
