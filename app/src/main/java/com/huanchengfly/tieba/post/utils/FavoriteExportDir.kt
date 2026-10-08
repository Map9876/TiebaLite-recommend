package com.huanchengfly.tieba.post.utils

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.huanchengfly.tieba.post.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 导出文件的落点。
 *
 * 默认写 App 私有目录（Android/data/.../files/tieba_favorites），
 * 用户在收藏页授权一个文件夹之后就写到那里——卸载 App 文件也还在，
 * 而且能在文件管理器里直接翻到。
 *
 * Android 11+ 往公共目录写要靠 SAF，所以统一用「用户选的 tree Uri」
 * 这种方式，各版本行为一致。
 */
object FavoriteExportDir {

    private const val PRIVATE_DIR_NAME = "tieba_favorites"

    /**
     * 写一次导出文件。
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
        val savedUri = context.appPreferences.exportDirUri

        if (savedUri.isBlank()) {
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
                isUserDir = false
            )
        }

        val treeUri = runCatching { Uri.parse(savedUri) }.getOrNull()
        val tree = treeUri?.let { runCatching { DocumentFile.fromTreeUri(context, it) }.getOrNull() }
        if (tree == null || !tree.canWrite()) {
            // 授权失效了（比如文件夹被删），退回私有目录，别让导出直接失败
            context.appPreferences.exportDirUri = ""
            return write(context, content, baseName, extension)
        }

        val doc = tree.createFile("*/*", fileName)
            ?: throw IllegalStateException(
                context.getString(R.string.toast_favorite_export_dir_failed)
            )
        context.contentResolver.openOutputStream(doc.uri)?.use { out ->
            out.write(content.toByteArray(Charsets.UTF_8))
        } ?: throw IllegalStateException(
            context.getString(R.string.toast_favorite_export_dir_failed)
        )

        return Result(
            path = displayPath(context, treeUri, fileName),
            displayName = fileName,
            uri = doc.uri,
            isUserDir = true
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

    /** 把私有目录里已有的导出文件挪到用户授权的目录 */
    fun migrateToUserDir(context: Context): Int {
        val savedUri = context.appPreferences.exportDirUri
        if (savedUri.isBlank()) return 0
        val treeUri = runCatching { Uri.parse(savedUri) }.getOrNull() ?: return 0
        val tree = runCatching { DocumentFile.fromTreeUri(context, treeUri) }.getOrNull()
            ?: return 0
        if (!tree.canWrite()) return 0

        var moved = 0
        existingPrivateFiles(context).forEach { file ->
            val doc = tree.createFile("*/*", file.name) ?: return@forEach
            runCatching {
                context.contentResolver.openOutputStream(doc.uri)?.use { out ->
                    out.write(file.readBytes())
                }
                file.delete()
                moved++
            }
        }
        return moved
    }

    /**
     * SAF 的 tree Uri 人眼看不懂（content://...），这里尽量还原成
     * 「primary/TiebaLite/xxx.html」这种可辨认的形式，供提示文案用。
     */
    private fun displayPath(context: Context, treeUri: Uri, fileName: String): String {
        val raw = treeUri.lastPathSegment.orEmpty()
        val readable = raw.replace(":", "/")
        return if (readable.isBlank()) fileName else "$readable/$fileName"
    }

    class Result(
        val path: String,
        val displayName: String,
        val uri: Any,          // File（私有目录）或 Uri（用户目录），调用方分别处理
        val isUserDir: Boolean,
        val mime: String = "",
    )
}
