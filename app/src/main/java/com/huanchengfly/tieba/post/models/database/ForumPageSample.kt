package com.huanchengfly.tieba.post.models.database

import androidx.compose.runtime.Immutable
import org.litepal.crud.LitePalSupport

/**
 * 「页码 → 时间范围」的采样点。
 *
 * 热门榜的页码和日期不是严格单调（同一页里能横跨半个月），所以不做二分查找，
 * 而是稀疏采样：跳页时顺手把这一页的时间范围记下来，攒够几个点之后就能
 * 在相邻两点之间插值，估出「我想看 9 月初的帖子，大概在第几页」。
 *
 * 采样不额外发请求——跳页本来就要请求，只是把结果留下来。
 */
@Immutable
data class ForumPageSample(
    /** 复合 key：`吧名_tabId_页码` */
    val key: String = "",
    val forumName: String = "",
    val tabId: Int = 0,
    val page: Int = 0,
    /** 该页最新帖时间（秒） */
    val newestTime: Long = 0,
    /** 该页最老帖时间（秒） */
    val oldestTime: Long = 0,
    val postCount: Int = 0,
    val sampledAt: Long = System.currentTimeMillis(),
) : LitePalSupport() {
    val id: Long = 0
}
