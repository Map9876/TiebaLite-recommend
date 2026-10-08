package com.huanchengfly.tieba.post.utils

import com.huanchengfly.tieba.post.models.SwanThread
import com.huanchengfly.tieba.post.models.database.ForumBrowse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.litepal.LitePal
import org.litepal.extension.findFirst

/**
 * 「上次翻到哪了」的记账 + 估算。
 *
 * 为什么要这个：热门榜页码越大越老（实测「方便面」吧翻 100 页从 10-09 推到 09-25，
 * 还能翻到 2024-11），但翻 100 页要 100 次请求，没人愿意每次重来。
 * 所以记住上次翻到的日期，下次从那儿接着翻。
 */
object ForumBrowseMemory {

    private const val KEY_PREFIX = "hot_"

    fun keyOf(forumName: String, tabId: Int) = "$KEY_PREFIX${forumName}_$tabId"

    suspend fun load(forumName: String, tabId: Int): ForumBrowse? = withContext(Dispatchers.IO) {
        runCatching {
            LitePal.where("key = ?", keyOf(forumName, tabId)).findFirst<ForumBrowse>()
        }.getOrNull()
    }

    /**
     * 把「这次翻到的页码 + 这批帖子的时间范围」记下来。
     * 只增不减：往回翻不会把进度倒退。
     */
    fun record(
        forumName: String,
        tabId: Int,
        page: Int,
        threads: List<SwanThread>,
    ) {
        val times = threads.map { it.createTime }.filter { it > 0 }
        if (times.isEmpty()) return
        val oldest = times.min()
        val newest = times.max()
        // 拿这批里最老那条当锚点
        val anchor = threads.filter { it.createTime > 0 }.minByOrNull { it.createTime }
        GlobalScope.launch(Dispatchers.IO) {
            runCatching {
                val old = LitePal.where("key = ?", keyOf(forumName, tabId)).findFirst<ForumBrowse>()
                ForumBrowse(
                    key = keyOf(forumName, tabId),
                    forumName = forumName,
                    tabId = tabId,
                    maxPage = maxOf(old?.maxPage ?: 0, page),
                    oldestSeenTime = minOf(old?.oldestSeenTime ?: Long.MAX_VALUE, oldest).let {
                        if (it == Long.MAX_VALUE) oldest else it
                    },
                    newestSeenTime = maxOf(old?.newestSeenTime ?: 0L, newest),
                    anchorTid = anchor?.takeIf { page >= (old?.maxPage ?: 0) }?.tid
                        ?: old?.anchorTid ?: 0L,
                    anchorTime = anchor?.takeIf { page >= (old?.maxPage ?: 0) }?.createTime
                        ?.takeIf { it > 0 }
                        ?: old?.anchorTime ?: 0L,
                ).saveOrUpdateByKey()
            }
        }
    }

    private fun ForumBrowse.saveOrUpdateByKey() {
        if (id == 0L) save() else update(id)
    }

    /**
     * 估算「要看到 [targetTime] 那批帖子，该翻到第几页」。
     *
     * 不做二分——热门榜的日期不是严格单调的，二分容易落到错误的区间。
     * 这里用**已知的两个锚点线性外推**（第 1 页 ≈ 最新、已翻到的 maxPage ≈ oldestSeenTime），
     * 再用「每天大约几页」的经验值收敛。宁可估偏几页，也不要给一个看着精确其实错的数。
     */
    fun estimatePage(
        browse: ForumBrowse?,
        targetTime: Long,
        newestPage1Time: Long,
    ): Int? {
        if (browse == null || browse.maxPage <= 0) return null
        val newest = if (browse.newestSeenTime > 0) browse.newestSeenTime else newestPage1Time
        if (newest <= targetTime) return 1
        val span = newest - browse.oldestSeenTime
        if (span <= 0) return null
        val need = (newest - targetTime).toDouble() / span
        val page = (1 + need * (browse.maxPage - 1)).toInt()
        return page.coerceIn(1, 10000)
    }
}