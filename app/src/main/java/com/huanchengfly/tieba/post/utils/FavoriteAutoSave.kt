package com.huanchengfly.tieba.post.utils

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.huanchengfly.tieba.post.repository.FavoriteRepository
import kotlinx.coroutines.Dispatchers
import java.io.File
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 收藏夹的自动存档。
 *
 * 用户在收藏页授权一个文件夹之后，每次收藏/取消收藏/导入，
 * 都顺手把整份收藏夹写成一个 json 存过去。这样：
 *
 * - 不用手动导出，换机时把那个 json 拷过去导入就行
 * - 卸载 App 文件也还在（写的是用户目录，不是 App 私有目录）
 *
 * 文件名固定，每次覆盖写，所以目录里永远只有一份最新的。
 */
object FavoriteAutoSave {

    const val FILE_NAME = "tieba_favorites_autosave.json"

    /**
     * 触发一次存档。没授权或写失败都静默跳过——存档是锦上添花，
     * 不能因为它把正常的收藏操作搞崩。
     */
    fun trigger(context: Context) {
        // 开关关着就不存。老系统（能免授权直接写公共目录）不需要 Uri，靠 exportDirUri
        // 为空来区分：这时走 FavoriteExportDir.write，它自己会落到公共目录。
        val saved = context.appPreferences.exportDirUri.orEmpty()
        val legacy = saved.isBlank() && FavoriteExportDir.legacyPublicDir() != null
        if (saved.isBlank() && !legacy) return
        GlobalScope.launch(Dispatchers.IO) {
            runCatching {
                if (legacy) {
                    // 覆盖写同名文件，目录里永远只有一份最新的
                    val dir = FavoriteExportDir.legacyPublicDir()
                    File(dir, FILE_NAME).writeText(
                        FavoriteRepository.exportPayload(null),
                        Charsets.UTF_8
                    )
                } else {
                    write(context, Uri.parse(saved))
                }
            }
        }
    }

    private suspend fun write(context: Context, treeUri: Uri) = withContext(Dispatchers.IO) {
        val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext
        if (!tree.canWrite()) {
            // 授权失效了就关掉自动存档，别每次都白试一次
            context.appPreferences.exportDirUri = ""
            return@withContext
        }
        val json = FavoriteRepository.exportPayload(null)
        // 同名的先删掉，不然会落成 "xxx (1).json"
        tree.findFile(FILE_NAME)?.delete()
        val doc = tree.createFile("application/json", FILE_NAME) ?: return@withContext
        context.contentResolver.openOutputStream(doc.uri)?.use { out ->
            out.write(json.toByteArray(Charsets.UTF_8))
        }
    }

    /** 展示用：授权了就返回存档文件路径，没授权返回 null */
    fun savedPath(context: Context): String? {
        val saved = context.appPreferences.exportDirUri.orEmpty()
        if (saved.isBlank()) {
            return FavoriteExportDir.legacyPublicDir()
                ?.let { "${it.absolutePath}/$FILE_NAME" }
        }
        val readable = Uri.parse(saved).lastPathSegment.orEmpty().replace(":", "/")
        return if (readable.isBlank()) FILE_NAME else "$readable/$FILE_NAME"
    }
}
