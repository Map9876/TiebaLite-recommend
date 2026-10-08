package com.huanchengfly.tieba.post.models.database

import androidx.compose.runtime.Immutable
import org.litepal.crud.LitePalSupport

/**
 * 本地收藏（免登录）。与服务端「我的收藏」完全独立，数据只落在本机数据库。
 *
 * content 为收藏瞬间的浏览缓冲区快照（ThreadViewCache），可能为空；
 * 搜索时命中的是 title / forumName / authorName / abstractText / content。
 */
@Immutable
data class Favorite(
    val threadId: Long = 0,
    val title: String = "",
    val forumName: String = "",
    val authorName: String? = null,
    val abstractText: String? = null,
    val url: String = "",
    val content: String? = null,
    val imageUrls: String? = null,
    val coverUrl: String? = null,
    /** 结构化楼层（[ThreadViewCache.CachedFloor] 的 JSON），导出 HTML 用 */
    val floorsJson: String? = null,
    val lastPage: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
) : LitePalSupport() {
    val id: Long = 0
}
