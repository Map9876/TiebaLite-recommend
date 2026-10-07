package com.huanchengfly.tieba.post.models

import androidx.compose.runtime.Immutable

/**
 * 收藏一个帖子所需的最小信息，从列表卡片 / 详情页随手就能拿到，
 * 不需要额外发请求。
 */
@Immutable
data class ThreadFavoriteInfo(
    val threadId: Long,
    val title: String,
    val forumName: String,
    val authorName: String? = null,
    val abstractText: String? = null,
    val url: String = "https://tieba.baidu.com/p/$threadId",
)
