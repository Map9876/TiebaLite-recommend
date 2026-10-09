package com.huanchengfly.tieba.post.utils

import com.huanchengfly.tieba.post.models.database.Favorite

/** 一条楼层命中：楼里出现了搜索词 */
data class FloorHit(
    val floor: Int,
    val author: String,
    /** 命中片段（已裁到词周围，带上下文） */
    val snippet: String,
)

object FavoriteSearchHelper {

    /** 裁剪命中片段，让关键词落在中间，前后各留一点上下文 */
    private const val CONTEXT = 22

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
    ): List<FloorHit> {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return emptyList()
        val floors = ThreadViewCache.parseFloorsJsonDeduplicated(favorite.floorsJson)
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
                snippet = snippet(floor.text, index, key.length)
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