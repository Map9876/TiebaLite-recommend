package com.huanchengfly.tieba.post.models

import androidx.compose.runtime.Immutable

/** 贴吧小程序 uni-frs 接口返回的一条帖子（组件化结构解析后的结果） */
@Immutable
data class SwanThread(
    val tid: Long = 0,
    val forumId: Long = 0,
    val title: String = "",
    val abstractText: String = "",
    val authorName: String = "",
    val authorAvatar: String = "",
    val pics: List<String> = emptyList(),
    val replyNum: Int = 0,
    val agreeNum: Int = 0,
    val shareNum: Int = 0,
    val isLive: Boolean = false,
) {
    val url: String
        get() = "https://tieba.baidu.com/p/$tid"

    /** feed_head 里带的用户主页 schema，点头像能跳过去 */
    val userSchema: String = ""
}