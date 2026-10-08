package com.huanchengfly.tieba.post.repository

import com.huanchengfly.tieba.post.models.ThreadFavoriteInfo
import com.huanchengfly.tieba.post.models.database.Favorite
import com.huanchengfly.tieba.post.utils.ThreadViewCache
import com.huanchengfly.tieba.post.utils.extension.findFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.litepal.LitePal
import org.litepal.extension.deleteAll
import org.litepal.extension.find
import org.litepal.extension.findFirst

/**
 * 免登录本地收藏。数据只在本机，跟服务端「我的收藏」互不相干。
 *
 * 收藏时会把 [ThreadViewCache] 里的浏览快照（翻到第几页 + 看过的正文）一并落库，
 * 这样导出的 HTML 里就有正文，关键词也能搜到正文。
 */
object FavoriteRepository {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private val _favoriteIds = MutableStateFlow<Set<Long>>(emptySet())
    val favoriteIds: StateFlow<Set<Long>> = _favoriteIds

    init {
        refreshIds()
    }

    fun refreshIds() {
        GlobalScope.launch(Dispatchers.IO) {
            runCatching {
                LitePal.order("threadId").find<Favorite>().orEmpty()
                    .map { it.threadId }.toSet()
            }
                .onSuccess { _favoriteIds.value = it }
        }
    }

    fun isFavorite(threadId: Long): Boolean = _favoriteIds.value.contains(threadId)

    fun isFavoriteFlow(threadId: Long): Flow<Boolean> =
        _favoriteIds.map { it.contains(threadId) }

    /** 切换收藏状态。UI 立即反馈，DB 写入放到后台，返回切换后是否为已收藏 */
    fun toggle(info: ThreadFavoriteInfo): Boolean {
        val willFavorite = !isFavorite(info.threadId)
        _favoriteIds.value =
            if (willFavorite) _favoriteIds.value + info.threadId
            else _favoriteIds.value - info.threadId
        GlobalScope.launch(Dispatchers.IO) {
            if (!willFavorite) {
                LitePal.deleteAll<Favorite>("threadId = ?", info.threadId.toString())
                return@launch
            }
            val cached = ThreadViewCache.snapshotContent(info.threadId)
            Favorite(
                threadId = info.threadId,
                title = info.title,
                forumName = info.forumName,
                authorName = info.authorName,
                abstractText = info.abstractText,
                url = info.url.ifBlank { "https://tieba.baidu.com/p/${info.threadId}" },
                content = cached?.text,
                imageUrls = cached?.imageUrls?.joinToString("\n"),
                coverUrl = info.coverUrl ?: cached?.imageUrls?.firstOrNull(),
                lastPage = cached?.maxPage ?: 0,
            ).save()
        }
        return willFavorite
    }

    /** 写入/更新一条收藏（详情页打开时补全标题、吧名等信息用） */
    fun upsert(
        info: ThreadFavoriteInfo,
        content: String? = null,
        imageUrls: List<String>? = null,
        lastPage: Int = 0,
    ) {
        GlobalScope.launch(Dispatchers.IO) {
            val old = LitePal.where("threadId = ?", info.threadId.toString()).findFirst<Favorite>()
            Favorite(
                threadId = info.threadId,
                title = info.title.ifBlank { old?.title.orEmpty() },
                forumName = info.forumName.ifBlank { old?.forumName.orEmpty() },
                authorName = info.authorName ?: old?.authorName,
                abstractText = info.abstractText ?: old?.abstractText,
                url = info.url.ifBlank { old?.url.orEmpty() }
                    .ifBlank { "https://tieba.baidu.com/p/${info.threadId}" },
                content = content ?: old?.content,
                imageUrls = imageUrls?.joinToString("\n") ?: old?.imageUrls,
                coverUrl = info.coverUrl ?: old?.coverUrl,
                lastPage = if (lastPage > 0) lastPage else old?.lastPage ?: 0,
                timestamp = old?.timestamp ?: System.currentTimeMillis(),
            ).let { new ->
                if (old != null) {
                    new.update(old.id)
                } else {
                    new.save()
                }
            }
            _favoriteIds.value = _favoriteIds.value + info.threadId
        }
    }

    fun remove(threadId: Long) {
        GlobalScope.launch(Dispatchers.IO) {
            LitePal.deleteAll<Favorite>("threadId = ?", threadId.toString())
            _favoriteIds.value = _favoriteIds.value - threadId
        }
    }

    fun removeAll() {
        GlobalScope.launch(Dispatchers.IO) {
            LitePal.deleteAll<Favorite>()
            _favoriteIds.value = emptySet()
        }
    }

    /** 关键词搜索：命中标题 / 吧名 / 作者 / 摘要 / 正文 */
    fun flow(keyword: String = "", limit: Int = 500): Flow<List<Favorite>> {
        val query = LitePal.order("timestamp desc").limit(limit)
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) {
            return query.findFlow()
        }
        val like = "%$trimmed%"
        return query.where(
            "title like ? or forumName like ? or authorName like ? or abstractText like ? or content like ?",
            like, like, like, like, like
        ).findFlow()
    }

    suspend fun getAll(): List<Favorite> = withContext(Dispatchers.IO) {
        LitePal.order("timestamp desc").limit(1000).find<Favorite>().orEmpty()
    }

    suspend fun getByIds(ids: Collection<Long>): List<Favorite> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyList()
        LitePal.where("threadId in (${ids.joinToString(",")})").find<Favorite>().orEmpty()
            .sortedBy { ids.indexOf(it.threadId) }
    }

    // ---------------- 导入 / 导出 ----------------

    @Serializable
    data class ExportItem(
        val threadId: Long,
        val title: String = "",
        val forumName: String = "",
        val authorName: String? = null,
        val url: String = "",
        val content: String? = null,
        val imageUrls: String? = null,
        val coverUrl: String? = null,
        val lastPage: Int = 0,
        val timestamp: Long = 0,
    )

    @Serializable
    data class ExportPayload(
        val version: Int = 1,
        val exportedAt: Long = System.currentTimeMillis(),
        val items: List<ExportItem> = emptyList(),
    )

    suspend fun exportPayload(ids: Collection<Long>? = null): String = withContext(Dispatchers.IO) {
        val list = if (ids == null) getAll() else getByIds(ids)
        json.encodeToString(
            ExportPayload.serializer(),
            ExportPayload(items = list.map {
                ExportItem(
                    threadId = it.threadId,
                    title = it.title,
                    forumName = it.forumName,
                    authorName = it.authorName,
                    url = it.url,
                    content = it.content,
                    imageUrls = it.imageUrls,
                    coverUrl = it.coverUrl,
                    lastPage = it.lastPage,
                    timestamp = it.timestamp,
                )
            })
        )
    }

    /** 导入，返回新增条数 */
    suspend fun importPayload(raw: String): Int = withContext(Dispatchers.IO) {
        val payload = json.decodeFromString(ExportPayload.serializer(), raw)
        var added = 0
        payload.items.forEach { item ->
            if (LitePal.where("threadId = ?", item.threadId.toString()).findFirst<Favorite>() != null) {
                return@forEach
            }
            Favorite(
                threadId = item.threadId,
                title = item.title,
                forumName = item.forumName,
                authorName = item.authorName,
                abstractText = null,
                url = item.url.ifBlank { "https://tieba.baidu.com/p/${item.threadId}" },
                content = item.content,
                imageUrls = item.imageUrls,
                coverUrl = item.coverUrl,
                lastPage = item.lastPage,
                timestamp = item.timestamp,
            ).save()
            added++
        }
        refreshIds()
        added
    }
}