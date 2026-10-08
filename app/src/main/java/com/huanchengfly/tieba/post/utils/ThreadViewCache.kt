package com.huanchengfly.tieba.post.utils

import androidx.compose.runtime.Immutable
import kotlin.math.max

/**
 * 帖子浏览缓冲区 —— 纯内存，10 分钟过期，进程被杀（上划清理）即清空。
 *
 * 目的：用户翻到第 3 页时点收藏，收藏里要能存下他「看过的内容」，
 * 但又不能为此额外发请求、不能卡 UI。所以这里只是把详情页**已经在内存里**
 * 的楼层文本/图片地址按页记一份，收藏瞬间再落库。
 *
 * 写入都发生在后台协程，主线程只做一次 map 合并（微秒级）。
 */
object ThreadViewCache {

    /** 缓冲区有效期：10 分钟 */
    const val TTL_MILLIS = 10 * 60 * 1000L

    private const val MAX_THREADS = 50
    private const val MAX_FLOORS_PER_PAGE = 80
    private const val MAX_IMAGES_PER_FLOOR = 4
    private const val MAX_TEXT_CHARS = 20_000
    private const val MAX_IMAGES_TOTAL = 40

    @Immutable
    data class Floor(
        val floor: Int,
        val author: String?,
        val text: String,
        val images: List<String>,
    )

    @Immutable
    data class Snapshot(
        val threadId: Long,
        val title: String,
        val forumName: String,
        val authorName: String?,
        val url: String,
        val pages: Map<Int, List<Floor>>,
        val updatedAt: Long,
    ) {
        val maxPage: Int
            get() = pages.keys.maxOrNull() ?: 0

        /** 拼成可搜索、可导出的纯文本 */
        fun toPlainText(): String {
            val sb = StringBuilder()
            pages.keys.sorted().forEach { page ->
                sb.append("—— 第 ").append(page).append(" 页 ——\n")
                pages[page]?.forEach { floor ->
                    if (floor.text.isBlank() && floor.images.isEmpty()) return@forEach
                    sb.append('#').append(floor.floor).append(' ')
                    if (!floor.author.isNullOrBlank()) sb.append(floor.author).append("：")
                    sb.append(floor.text)
                    if (floor.images.isNotEmpty()) sb.append(" [图片]")
                    sb.append('\n')
                }
            }
            return sb.toString().let { if (it.length > MAX_TEXT_CHARS) it.substring(0, MAX_TEXT_CHARS) else it }
        }

        /** 看过的楼层里的图片地址，去重后按出现顺序 */
        fun imageUrls(): List<String> {
            val urls = LinkedHashSet<String>()
            pages.keys.sorted().forEach { page ->
                pages[page]?.forEach { floor ->
                    floor.images.forEach {
                        if (it.isNotBlank()) urls.add(it)
                    }
                }
            }
            return urls.take(MAX_IMAGES_TOTAL)
        }
    }

    private val lock = Any()
    private val cache = LinkedHashMap<Long, Snapshot>(32, 0.75f, true)

    /**
     * 记录一页。重复记录同一页会覆盖（内容有更新时）。
     * @param floors 该页楼层，已按楼层号升序
     */
    fun record(
        threadId: Long,
        page: Int,
        title: String,
        forumName: String,
        authorName: String?,
        floors: List<Floor>,
    ) {
        if (threadId == 0L) return
        val trimmed = floors.take(MAX_FLOORS_PER_PAGE).map {
            if (it.images.size > MAX_IMAGES_PER_FLOOR) it.copy(images = it.images.take(MAX_IMAGES_PER_FLOOR)) else it
        }
        synchronized(lock) {
            sweepLocked()
            val old = cache[threadId]
            val pages = LinkedHashMap<Int, List<Floor>>(old?.pages ?: emptyMap())
            pages[page] = trimmed
            cache[threadId] = Snapshot(
                threadId = threadId,
                title = title.ifBlank { old?.title.orEmpty() },
                forumName = forumName.ifBlank { old?.forumName.orEmpty() },
                authorName = authorName?.ifBlank { null } ?: old?.authorName,
                url = "https://tieba.baidu.com/p/$threadId",
                pages = pages,
                updatedAt = System.currentTimeMillis(),
            )
            evictLocked()
        }
    }

    /** 取快照，过期返回 null */
    fun get(threadId: Long): Snapshot? {
        val snapshot = synchronized(lock) { cache[threadId] } ?: return null
        if (System.currentTimeMillis() - snapshot.updatedAt > TTL_MILLIS) {
            synchronized(lock) { cache.remove(threadId) }
            return null
        }
        return snapshot
    }

    /** 收藏时用：只取内容，不续期 */
    fun snapshotContent(threadId: Long): Content? {
        val snapshot = get(threadId) ?: return null
        return Content(
            text = snapshot.toPlainText(),
            maxPage = max(snapshot.maxPage, 1),
            imageUrls = snapshot.imageUrls()
        )
    }

    @Immutable
    data class Content(
        val text: String,
        val maxPage: Int,
        val imageUrls: List<String>,
    )

    fun clear() {
        synchronized(lock) { cache.clear() }
    }

    private fun sweepLocked() {
        val now = System.currentTimeMillis()
        val expired = cache.filter { now - it.value.updatedAt > TTL_MILLIS }.map { it.key }
        expired.forEach { cache.remove(it) }
    }

    private fun evictLocked() {
        while (cache.size > MAX_THREADS) {
            val oldest = cache.keys.firstOrNull() ?: break
            cache.remove(oldest)
        }
    }
}
