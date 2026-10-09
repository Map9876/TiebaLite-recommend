package com.huanchengfly.tieba.post.utils

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.documentfile.provider.DocumentFile
import com.huanchengfly.tieba.post.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 导出文件的落点。
 *
 * 默认写 App 私有目录（Android/data/.../files/tieba_favorites）；
 * 用户在设置页打开开关之后写到公共目录下的 TiebaLite 文件夹——
 * 卸载 App 文件也还在，而且能在文件管理器里直接翻到。
 *
 * 用户不该被要求「自己找一个文件夹、还想名字」。所以：
 *
 * - Android 10 及以下：直接写 `/sdcard/Download/TiebaLite`，一次授权都不用
 * - Android 11+：公共目录受作用域存储限制，只能走 SAF。此时用户只需在系统
 *   选择器里选到「下载」这一层，App 会自己建好 TiebaLite 子目录并记住它，
 *   用户全程不需要输入任何文件夹名
 */
object FavoriteExportDir {

    private const val PRIVATE_DIR_NAME = "tieba_favorites"
    private const val PUBLIC_DIR_NAME = "TiebaLite"

    /** 免授权就能写的公共目录；拿不到就返回 null，交给 SAF */
    fun legacyPublicDir(): File? {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.Q) return null
        @Suppress("DEPRECATION")
        val ext = android.os.Environment.getExternalStorageDirectory() ?: return null
        val dir = File(ext, "Download/$PUBLIC_DIR_NAME")
        if (dir.exists()) return dir.takeIf { it.isDirectory && it.canWrite() }
        return runCatching { if (dir.mkdirs()) dir else null }.getOrNull()
    }

    /**
     * 在用户授权的 tree Uri 下建好 TiebaLite 子目录，返回它的 Uri。
     *
     * 已存在就直接复用——这样重复授权不会套出「TiebaLite/TiebaLite」。
     */
    fun resolveUserDir(context: Context, parentTreeUri: Uri): Uri {
        val parent = DocumentFile.fromTreeUri(context, parentTreeUri)
        if (parent == null || !parent.canWrite()) {
            throw IllegalStateException(
                context.getString(R.string.toast_favorite_export_dir_failed)
            )
        }
        val existing = parent.findDirectory(PUBLIC_DIR_NAME)
        val dir = existing ?: parent.createDirectory(PUBLIC_DIR_NAME)
            ?: throw IllegalStateException(
                context.getString(R.string.toast_favorite_export_dir_failed)
            )
        return dir.uri
    }

    /**
     * 写一次导出文件。
     *
     * 落点优先级：已授权的公共目录 → 自己能直接写的公共目录 → App 私有目录。
     * 授权失效（文件夹被删）会自动降级，不让导出直接失败。
     *
     * @return 展示用的结果：[Result.path] 是给人看的路径，[Result.uri] 用于分享
     */
    fun write(
        context: Context,
        content: String,
        baseName: String,
        extension: String,
    ): Result {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "${baseName}_$stamp.$extension"

        val public = publicDir(context)
        if (public != null) {
            return runCatching {
                val file = File(public, fileName)
                file.writeText(content, Charsets.UTF_8)
                Result(
                    path = file.absolutePath,
                    displayName = file.name,
                    uri = file,
                    isUserDir = true,
                    mime = mimeOf(extension)
                )
            }.getOrElse {
                // 目录写着失败了（比如权限被回收），落到私有目录
                writePrivate(context, content, fileName, mimeOf(extension))
            }
        }

        return writePrivate(context, content, fileName, mimeOf(extension))
    }

    /** 已授权就用授权的，否则看能不能免授权直接写公共目录 */
    private fun publicDir(context: Context): File? {
        val savedUri = context.appPreferences.exportDirUri.orEmpty()
        if (savedUri.isNotBlank()) {
            val treeUri = runCatching { Uri.parse(savedUri) }.getOrNull()
            val tree = treeUri?.let {
                runCatching { DocumentFile.fromTreeUri(context, it) }.getOrNull()
            }
            if (tree != null && tree.canWrite()) return null // 走 SAF 分支，不返回 File
            // 授权失效（文件夹被删/ 权限回收），清掉让它下次重新走免授权路径
            context.appPreferences.exportDirUri = ""
        }
        return legacyPublicDir()
    }

    private fun writePrivate(context: Context, content: String, fileName: String, mime: String): Result {
        val dir = File(
            context.getExternalFilesDir(null) ?: context.filesDir,
            PRIVATE_DIR_NAME
        )
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, fileName)
        file.writeText(content, Charsets.UTF_8)
        return Result(
            path = file.absolutePath,
            displayName = file.name,
            uri = file,
            isUserDir = false,
            mime = mime
        )
    }

    /** 私有目录里已经导出的文件，用来授权后迁移过去 */
    fun existingPrivateFiles(context: Context): List<File> {
        val dir = File(
            context.getExternalFilesDir(null) ?: context.filesDir,
            PRIVATE_DIR_NAME
        )
        return dir.takeIf { it.exists() }?.listFiles()?.filter { it.isFile }.orEmpty()
    }

    /**
     * 把私有目录里已有的导出文件挪到公共目录。
     *
     * 只在能免授权直接写的情况下搬（Android 10 及以下）；Android 11+ 走 SAF
     * 逐个复制代价高、还可能失败，让用户重新导出一次更省事。
     */
    fun migrateToPublicDir(context: Context): Int {
        val target = legacyPublicDir() ?: return 0
        var moved = 0
        existingPrivateFiles(context).forEach { file ->
            runCatching {
                val dest = File(target, file.name)
                dest.writeBytes(file.readBytes())
                file.delete()
                moved++
            }
        }
        return moved
    }

    private fun mimeOf(extension: String): String = when (extension) {
        "html" -> "text/html"
        "json" -> "application/json"
        else -> "text/plain"
    }

    data class Result(
        val path: String,
        val displayName: String,
        val uri: Any,          // File（本地路径）或 Uri（SAF），调用方分别处理
        val isUserDir: Boolean,
        val mime: String = "",
    )
}