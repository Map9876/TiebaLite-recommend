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
    /**
     * 锚点帖子 id + 它当时的发帖时间。
     *
     * 热门榜是会重排的：一周前「第 50 页」这周再翻可能已经不是同批帖子了，
     * 所以光记页码不可靠。改成记一个具体的帖子当游标——下次回来先确认这个
     * 帖子还在不在，在就说明榜面漂移不大，不在就知道位置已经变了。
     * 思路和 GitHub commits 的 `?after=<sha>` 一样：不锚绝对位置，锚具体对象。
     */
    val anchorTid: Long = 0,
    /** 锚点帖子的发帖时间（秒），漂移量靠它和当前页的时间比出来 */
    val anchorTime: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
) : LitePalSupport() {
    val id: Long = 0
}