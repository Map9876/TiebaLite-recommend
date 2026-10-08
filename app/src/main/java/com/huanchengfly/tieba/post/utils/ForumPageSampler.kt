package com.huanchengfly.tieba.post.utils

import com.huanchengfly.tieba.post.models.SwanThread
import com.huanchengfly.tieba.post.models.database.ForumPageSample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.litepal.LitePal
import org.litepal.extension.deleteAll
import org.litepal.extension.find
import org.litepal.extension.findFirst

/**
 * 「想看某个月的帖子 → 大概翻到第几页」的稀疏采样映射。
 *
 * 做法：每次加载某页时把该页的时间范围记成一个采样点（不额外发请求），
 *攒够几个点之后，在相邻两个采样点之间按时间线性插值，估出目标页码。
 *
 * 为什么不用二分：热门榜的页码↔日期不是单调的（前 30 页里就有 13 次
 * 局部抖动），二分收敛到的区间经常是错的。插值虽然也不精确，但能给出
 * 一个「大致从这里开始翻」的位置，配合往下翻很快就能接上。
 */
object ForumPageSampler {

    private const val MAX_SAMPLES_PER_FORUM = 60

    private fun keyOf(forumName: String, tabId: Int) = "${ForumBrowseMemory.keyOf(forumName, tabId)}_p"

    /** 记一个采样点。同一页重复加载会覆盖（时间范围可能变了）。 */
    fun record(forumName: String, tabId: Int, page: Int, threads: List<SwanThread>) {
        val times = threads.map { it.createTime }.filter { it > 0 }
        if (times.isEmpty()) return
        GlobalScope.launch(Dispatchers.IO) {
            runCatching {
                val key = keyOf(forumName, tabId)
                val old = LitePal.where("key = ?", "$key$page").findFirst<ForumPageSample>()
                ForumPageSample(
                    key = "$key$page",
                    forumName = forumName,
                    tabId = tabId,
                    page = page,
                    newestTime = times.max(),
                    oldestTime = times.min(),
                    postCount = times.size,
                ).let { if (old != null) it.update(old.id) else it.save() }

                // 控制单吧的采样点数量，太多没必要
                val all = LitePal.where("key like ?", "$key%")
                    .order("page desc").find<ForumPageSample>().orEmpty()
                if (all.size > MAX_SAMPLES_PER_FORUM) {
                    all.drop(MAX_SAMPLES_PER_FORUM).forEach {
                        LitePal.deleteAll<ForumPageSample>("id = ?", it.id.toString())
                    }
                }
            }
        }
    }

    suspend fun samples(forumName: String, tabId: Int): List<ForumPageSample> =
        withContext(Dispatchers.IO) {
            runCatching {
                LitePal.where("key like ?", "${keyOf(forumName, tabId)}%")
                    .order("page asc").find<ForumPageSample>().orEmpty()
            }.getOrDefault(emptyList())
        }

    /**
     * 估算 [targetTime]（秒）大概在哪一页。
     *
     * @return null 表示采样点还不够覆盖那个时间，需要先往后翻几页攒采样点
     */
    fun estimatePage(
        samples: List<ForumPageSample>,
        targetTime: Long,
    ): Int? {
        if (samples.isEmpty() || targetTime <= 0) return null
        val byPage = samples.sortedBy { it.page }
        // 目标比最新的采样点还新 → 第 1 页附近
        if (targetTime >= byPage.first().newestTime) return 1
        val last = byPage.last()
        // 目标比最老的采样点还老 → 探不到，得多翻几页
        if (targetTime <= last.oldestTime) return null

        // 找包围目标时间的两个采样点
        for (i in 0 until byPage.lastIndex) {
            val a = byPage[i]
            val b = byPage[i + 1]
            if (targetTime in a.oldestTime..b.newestTime) {
                val span = (b.newestTime - a.oldestTime).toDouble()
                if (span <= 0) return a.page
                val ratio = (a.newestTime - targetTime) / span
                return (a.page + ratio * (b.page - a.page))
                    .toInt().coerceIn(a.page, b.page)
            }
        }
        return null
    }

    /** 采样点覆盖到的最老时间，UI 用来说明「还能翻到多早」 */
    fun oldestCovered(samples: List<ForumPageSample>): Long =
        samples.minOfOrNull { it.oldestTime } ?: 0L

    /**
     * 后台锚定：进页面时按稀疏序列自动采几个点，把「页码 ↔ 日期」表建起来。
     *
     * 序列按 1→2倍 递增（2/4/8/16…封顶 400），这样前段密、后段疏，
     * 十几页就能覆盖一两个月。已经有采样点的页直接跳过，不重复请求。
     *
     * 每步之间停 [PROBE_INTERVAL_MS]，一是别把接口打爆，二是让前台
     * 自己的请求优先。离开页面时协程被取消，探测自然停止。
     */
    suspend fun backgroundProbe(
        forumName: String,
        tabId: Int,
        fetch: suspend (page: Int) -> List<SwanThread>,
    ) {
        val known = samples(forumName, tabId).map { it.page }.toSet()
        var page = 2
        var rounds = 0
        while (page <= PROBE_MAX_PAGE && rounds < PROBE_MAX_ROUNDS) {
            rounds++
            if (page in known) {
                page *= 2
                continue
            }
            delay(PROBE_INTERVAL_MS)
            val threads = runCatching { fetch(page) }.getOrNull() ?: break
            if (threads.isEmpty()) break          // 翻到头了，这个吧没这么多页
            record(forumName, tabId, page, threads)
            page *= 2
        }
    }

    private const val PROBE_INTERVAL_MS = 1800L
    private const val PROBE_MAX_PAGE = 400
    private const val PROBE_MAX_ROUNDS = 10
}
