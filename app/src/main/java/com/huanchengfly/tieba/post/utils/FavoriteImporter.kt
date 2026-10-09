package com.huanchengfly.tieba.post.utils

import android.content.Context
import com.huanchengfly.tieba.post.models.database.Favorite
import com.huanchengfly.tieba.post.repository.FavoriteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.litepal.LitePal

/**
 * 三种导出格式都能导回来。
 *
 * - **json**：完整备份，恢复正文 / 图片 / 楼层结构 / 封面（走 Repository 现成的解析）
 * - **txt**：「标题\t链接\t吧\t作者\t收藏时间」逐行，只恢复条目
 * - **html**：从导出的网页里解析出帖子 id 和标题，只恢复条目
 *
 * 一律按 threadId 去重：已经收藏过的跳过，不覆盖。
 *
 * 判断格式只看内容不看扩展名——从聊天软件收到的文件扩展名经常是乱的。
 */
object FavoriteImporter {

    private val SECTION = Regex(
        "<section[^>]*class=\"post\"[^>]*id=\"t(\\d+)\"[^>]*>([\\s\\S]*?)</section>"
    )
    private val TITLE = Regex("<h2>([\\s\\S]*?)</h2>")
    private val THREAD_ID = Regex("/p/(\\d+)")
    private const val HTML_MARK = "<section class=\"post\""

    suspend fun import(context: Context, raw: String): Int = withContext(Dispatchers.IO) {
        val text = raw.trim()
        val added = when {
            text.isEmpty() -> 0
            text.startsWith("{") -> FavoriteRepository.importPayload(text)
            text.contains(HTML_MARK) -> importHtml(text)
            else -> importLinks(text)
        }
        if (added > 0) FavoriteRepository.refreshIds()
        added
    }

    /** 从导出的 HTML 里把帖子条目捞回来 */
    private fun importHtml(html: String): Int {
        var added = 0
        SECTION.findAll(html).forEach { m ->
            val threadId = m.groupValues[1].toLongOrNull() ?: 0L
            if (threadId == 0L) return@forEach
            val title = TITLE.find(m.groupValues[2])
                ?.groupValues[1]
                ?.let { unescapeHtml(it) }
                ?.takeIf { it.isNotBlank() }
                ?: "（来自 HTML 导入）"
            val ok = insert(
                threadId = threadId,
                title = title,
                forumName = "",
                authorName = null,
                url = "https://tieba.baidu.com/p/$threadId",
            )
            if (ok) added++
        }
        return added
    }

    /** 「标题\t链接\t吧\t作者\t收藏时间」逐行，第一行是表头 */
    private fun importLinks(raw: String): Int {
        var added = 0
        raw.lineSequence().drop(1).forEach { line ->
            val cols = line.split("\t")
            if (cols.size < 2) return@forEach
            val url = cols[1].trim()
            val threadId = THREAD_ID.find(url)?.groupValues[1]?.toLongOrNull() ?: 0L
            if (threadId == 0L) return@forEach
            val title = cols[0].trim().takeIf { it.isNotBlank() } ?: "（来自链接导入）"
            val ok = insert(
                threadId = threadId,
                title = title,
                forumName = cols.getOrNull(2)?.trim().orEmpty(),
                authorName = cols.getOrNull(3)?.trim()?.takeIf { it.isNotBlank() },
                url = url,
            )
            if (ok) added++
        }
        return added
    }

    /** 已收藏过的跳过，返回是否新增 */
    private fun insert(
        threadId: Long,
        title: String,
        forumName: String,
        authorName: String?,
        url: String,
    ): Boolean {
        val exists = LitePal.where("threadId = ?", threadId.toString())
            .findFirst<Favorite>() != null
        if (exists) return false
        Favorite(
            threadId = threadId,
            title = title,
            forumName = forumName,
            authorName = authorName,
            url = url.ifBlank { "https://tieba.baidu.com/p/$threadId" },
        ).save()
        return true
    }

    private fun unescapeHtml(text: String): String = text
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .trim()
}
