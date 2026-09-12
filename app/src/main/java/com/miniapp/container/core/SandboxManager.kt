package com.miniapp.container.core

import java.io.File

/** 沙箱存储层：每个 appKey 一个独立目录，内部 app/data/tmp。 */
class SandboxManager(val rootDir: File) {
    init { rootDir.mkdirs() }

    fun appDir(appKey: String): File = File(rootDir, appKey)
    fun resDir(appKey: String): File = File(appDir(appKey), "app")
    fun dataDir(appKey: String): File = File(appDir(appKey), "data")
    fun tmpDir(appKey: String): File = File(appDir(appKey), "tmp")
    fun metaFile(appKey: String): File = File(appDir(appKey), "meta.json")

    fun create(appKey: String) {
        appDir(appKey).mkdirs()
        resDir(appKey).mkdirs()
        dataDir(appKey).mkdirs()
        tmpDir(appKey).mkdirs()
    }

    fun exists(appKey: String): Boolean =
        appDir(appKey).exists() && metaFile(appKey).exists()

    fun delete(appKey: String): Boolean = appDir(appKey).deleteRecursively()
}
