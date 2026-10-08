package com.huanchengfly.tieba.post.models.database

import androidx.compose.runtime.Immutable
import org.litepal.crud.LitePalSupport

/**
 * 记录「某个吧的某个 tab 翻到了哪里」。
 *
 * 热门榜的页码越大整体越老（实测翻 100 页约推进 14 天），但存在局部抖动，
 * 所以不适合每次都从头翻。这里记住上次翻到的日期，下次可以直接跳过去继续。
 */
@Immutable
data class ForumBrowse(
    /** 复合 key：`吧名_tabId`，同一个吧的不同 tab 各记一份 */
    val key: String = "",
    val forumName: String = "",
    val tabId: Int = 0,
    /** 已经翻到的最大页码 */
    val maxPage: Int = 0,
    /** 已经看到的最老一条帖子时间（秒） */
    val oldestSeenTime: Long = 0,
    /** 已经看到的最新一条帖子时间（秒） */
    val newestSeenTime: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
) : LitePalSupport() {
    val id: Long = 0
}