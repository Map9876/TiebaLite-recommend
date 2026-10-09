package com.huanchengfly.tieba.post.utils

import android.util.Log
import com.huanchengfly.tieba.post.models.database.Favorite

/** 一条楼层命中：楼里出现了搜索词 */
data class FloorHit(
    val floor: Int,
    val author: String,
    /** 命中片段（已裁到词周围，带上下文） */
    val snippet: String,
    /** 实际命中的那些词，渲染时拿来高亮 */
    val matchedWords: List<String>,
)

object FavoriteSearchHelper {

    /** 裁剪命中片段，让关键词落在中间，前后各留一点上下文 */
    private const val CONTEXT = 22

    /** 楼层 JSON 超过这个大小就不展开搜索结果，避免极端数据拖垮列表 */
    private const val MAX_JSON_CHARS = 512 * 1024

    /**
     * 找出这条收藏里命中关键词的楼层。
     *
     * 用的是结构化楼层（收藏时就存下来了），所以能拿到「第几楼、谁说的、原文」，
     * 而不是只能拿一整坨正文让用户自己找。
     */
    fun floorHits(
        favorite: Favorite,
        keyword: String,
        maxFloors: Int = 8,
    ): List<FloorHit> = runCatching {
        floorHitsOrThrow(favorite, keyword, maxFloors)
    }.getOrElse { error ->
        // 这段要解析用户本地存的历史数据，格式可能来自好几个 App 版本
        // （比如加过 page 字段又删掉）。任何解析异常都只影响这次的搜索展示，
        // 绝不能让它冒到 UI 上把页面搞崩——搜索不到总比闪退好。
        Log.w("FavoriteSearchHelper", "解析楼层失败", error)
        emptyList()
    }

    private fun floorHitsOrThrow(
        favorite: Favorite,
        keyword: String,
        maxFloors: Int,
    ): List<FloorHit> {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return emptyList()
        val json = favorite.floorsJson
        if (json.isNullOrBlank()) return emptyList()
        // 兜底：楼层 JSON 理论上几百层、几十 KB，但真出现异常大的数据时
        // 直接放弃展开，别把列表卡死。只影响搜索展示，不影响数据本身。
        if (json.length > MAX_JSON_CHARS) return emptyList()
        val floors = ThreadViewCache.parseFloorsJsonDeduplicated(json)
        if (floors.isEmpty()) return emptyList()

        // 搜索框可能输「方便面 排行」这种多词，按空格拆开任意一个命中就算
        val keys = trimmed.split(' ').filter { it.isNotBlank() }
        if (keys.isEmpty()) return emptyList()

        return floors.mapNotNull { floor ->
            if (floor.text.isBlank()) return@mapNotNull null
            val at = keys.indexOfFirst { key ->
                floor.text.contains(key, ignoreCase = true)
            }
            if (at < 0) return@mapNotNull null
            val key = keys[at]
            val index = floor.text.indexOf(key, ignoreCase = true)
            FloorHit(
                floor = floor.floor,
                author = floor.author?.takeIf { it.isNotBlank() } ?: "匿名",
                snippet = snippet(floor.text, index, key.length),
                matchedWords = keys.filter { floor.text.contains(it, ignoreCase = true) }
            )
        }.take(maxFloors)
    }

    /** 只在这一条收藏里命中标题/吧名/作者（楼里没命中） */
    fun matchesMeta(favorite: Favorite, keyword: String): Boolean {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return true
        val keys = trimmed.split(' ').filter { it.isNotBlank() }
        if (keys.isEmpty()) return true
        val meta = "${favorite.title} ${favorite.forumName} ${favorite.authorName.orEmpty()}"
        return keys.any { meta.contains(it, ignoreCase = true) }
    }

    private fun snippet(text: String, matchStart: Int, matchLength: Int): String {
        val plain = text.replace("\n", " ")
        if (plain.length <= CONTEXT * 2 + 10) return plain
        val start = (matchStart - CONTEXT).coerceAtLeast(0)
        val end = (matchStart + matchLength + CONTEXT).coerceAtMost(plain.length)
        return buildString {
            if (start > 0) append("…")
            append(plain.substring(start, end))
            if (end < plain.length) append("…")
        }
    }
}