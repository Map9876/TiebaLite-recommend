package com.huanchengfly.tieba.post.repository

import com.huanchengfly.tieba.post.models.ThreadFavoriteInfo
import com.huanchengfly.tieba.post.api.TiebaApi
import com.huanchengfly.tieba.post.models.database.Favorite
import com.huanchengfly.tieba.post.utils.ThreadViewCache
import com.huanchengfly.tieba.post.utils.extension.findFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
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

    /**
     * 落库完成后的通知。
     *
     * 收藏/取消收藏都是异步写 DB 的：用户点完爱心立刻跳到收藏页时，
     * 查询可能还没看到这条记录，页面就像「没收藏成功」一样。
     * 所以写入完成后发一个信号，收藏页订阅它重新查一次。
     */
    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val changes: SharedFlow<Unit> = _changes

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
                floorsJson = cached?.floorsJson?.takeIf { it.isNotBlank() },
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
        floorsJson: String? = null,
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
                floorsJson = floorsJson ?: old?.floorsJson,
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

    /**
     * 浏览缓冲区有内容时，回填到已存在的收藏记录上。
     *
     * 两种场景都靠它：
     * ① 从列表收藏时没看帖子 → 落库的 content 是空的；之后进详情页看了，
     *    缓冲区就有内容了，这里补上，导出 HTML 才不会是空壳
     * ② 详情页收藏后退出、再进来看更多 → 缓冲区更新，这里同步进库
     *
     * 走导出时读的是数据库，所以浏览缓冲区过期也不影响已落库的内容。
     */
    fun backfillFromCache(threadId: Long) {
        val snapshot = ThreadViewCache.snapshotContent(threadId) ?: return
        if (snapshot.text.isBlank()) return
        GlobalScope.launch(Dispatchers.IO) {
            val old = LitePal.where("threadId = ?", threadId.toString()).findFirst<Favorite>()
                ?: return@launch
            // 已经存了更长的内容就不覆盖（用户可能看过更多）
            if ((old.content?.length ?: 0) >= snapshot.text.length) return@launch
            old.copy(
                content = snapshot.text,
                imageUrls = snapshot.imageUrls.joinToString("\n")
                    .ifBlank { old.imageUrls },
                coverUrl = old.coverUrl ?: snapshot.imageUrls.firstOrNull(),
                floorsJson = snapshot.floorsJson.takeIf { it.isNotBlank() } ?: old.floorsJson,
                lastPage = maxOf(old.lastPage, snapshot.maxPage),
            ).update(old.id)
            refreshIds()
            _changes.tryEmit(Unit)
        }
    }

    fun remove(threadId: Long) {
        GlobalScope.launch(Dispatchers.IO) {
            LitePal.deleteAll<Favorite>("threadId = ?", threadId.toString())
            _favoriteIds.value = _favoriteIds.value - threadId
            _changes.tryEmit(Unit)
        }
    }

    fun removeAll() {
        GlobalScope.launch(Dispatchers.IO) {
            LitePal.deleteAll<Favorite>()
            _favoriteIds.value = emptySet()
            _changes.tryEmit(Unit)
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
        val floorsJson: String? = null,
        val lastPage: Int = 0,
        val timestamp: Long = 0,
    )

    @Serializable
    data class ExportPayload(
        val version: Int = 1,
        val exportedAt: Long = System.currentTimeMillis(),
        val items: List<ExportItem> = emptyList(),
    )

    /**
     * 异步补封面。
     *
     * 贴吧图片过期后不报 403，而是返回一张 238x238 的默认图标，
     * 所以「封面还在不在」不能用 HTTP 状态码判断。做法是：
     * 列表先按现有封面照常显示（不阻塞），这里后台把图抓下来看尺寸，
     * 确认是占位图就再去请求一次帖子详情，拿一楼首图写回库。
     */
    fun refreshCoverIfPlaceholder(favorite: Favorite) {
        val url = favorite.coverUrl?.takeIf { it.isNotBlank() } ?: return
        GlobalScope.launch(Dispatchers.IO) {
            runCatching {
                if (!FavoriteHtmlExporter.isPlaceholderImage(url)) return@runCatching
                // pbPageFlow 是 Flow，取第一个（也就是第一页）
                val fresh = TiebaApi.getInstance()
                    .pbPageFlow(threadId = favorite.threadId, page = 1)
                    .first()
                    .data_
                    ?.post_list
                    ?.firstOrNull()
                    ?.content
                    ?.asSequence()
                    // type == 3 是图片；src / originSrc / bigSrc / cdnSrc 依次取第一个非空
                    ?.filter { it.type == 3 }
                    ?.map {
                        it.originSrc.ifBlank { it.bigSrc.ifBlank { it.src.ifBlank { it.cdnSrc } } }
                    }
                    ?.map { FavoriteHtmlExporter.stripQuery(it) }
                    ?.firstOrNull { it.isNotBlank() }
                    ?: return@runCatching
                val old = LitePal.where("threadId = ?", favorite.threadId.toString())
                    .findFirst<Favorite>() ?: return@runCatching
                old.copy(coverUrl = fresh).update(old.id)
                refreshIds()
                _changes.tryEmit(Unit)
            }
        }
    }

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
    /**
     * 导入。三种导出格式都能吃回来：
     *
     * - **json**：完整备份（正文、图片、楼层结构、封面），按 threadId 去重合并
     * - **txt**：「标题\t链接\t吧\t作者\t收藏时间」逐行，只恢复条目本身
     * - **html**：从导出的网页里解析出 `<section class="post" id="t<threadId>"`
     *   和标题，同样只恢复条目本身
     */
    suspend fun importAny(raw: String): Int = withContext(Dispatchers.IO) {
        when {
            raw.trimStart().startsWith("{") -> importPayload(raw)
            raw.contains("<!DOCTYPE html>") || raw.contains("<section class=\"post\"") ->
                importHtml(raw)

            else -> importLinks(raw)
        }
    }

    /** 从导出的 HTML 里把帖子条目捞回来 */
    private fun importHtml(html: String): Int {
        val sectionRe =
            Regex("<section[^>]*class=\"post\"[^>]*id=\"t(\\d+)\"[^>]*>([\\s\\S]*?)</section>")
        val titleRe = Regex("<h2>([\\s\\S]*?)</h2>")
        var added = 0
        sectionRe.findAll(html).forEach { m ->
            val threadId = m.groupValues[1].toLongOrNull() ?: return@forEach
            if (threadId == 0L) return@forEach
            if (LitePal.where("threadId = ?", threadId.toString()).findFirst<Favorite>() != null) {
                return@forEach
            }
            val title = titleRe.find(m.groupValues[2])?.groupValues[1]
                ?.let { unescapeHtml(it) }
                ?.takeIf { it.isNotBlank() }
                ?: "(来自 HTML 导入)"
            Favorite(
                threadId = threadId,
                title = title,
                forumName = "",
                url = "https://tieba.baidu.com/p/$threadId",
            ).save()
            added++
        }
        if (added > 0) {
            refreshIds()
            _changes.tryEmit(Unit)
        }
        return added
    }

    private fun unescapeHtml(text: String): String = text
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .trim()

    /** 导入「链接+标题」的 txt（标题\t链接\t吧\t作者\t收藏时间） */
    private fun importLinks(raw: String): Int {
        var added = 0
        raw.lineSequence()
            .drop(1) // 第一行是表头
            .map { it.split("\t") }
            .filter { it.size >= 2 }
            .forEach { cols ->
                val url = cols[1].trim()
                val threadId = Regex("/p/(\\d+)").find(url)?.groupValues[1]?.toLongOrNull()
                    ?: return@forEach
                if (LitePal.where("threadId = ?", threadId.toString()).findFirst<Favorite>() != null) {
                    return@forEach
                }
                Favorite(
                    threadId = threadId,
                    title = cols[0].trim().ifBlank { "(来自链接导入)" },
                    forumName = cols.getOrNull(2)?.trim().orEmpty(),
                    authorName = cols.getOrNull(3)?.trim()?.takeIf { it.isNotBlank() },
                    url = url.ifBlank { "https://tieba.baidu.com/p/$threadId" },
                ).save()
                added++
            }
        if (added > 0) {
            refreshIds()
            _changes.tryEmit(Unit)
        }
        return added
    }

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
                floorsJson = item.floorsJson,
                lastPage = item.lastPage,
                timestamp = item.timestamp,
            ).save()
            added++
        }
        refreshIds()
        _changes.tryEmit(Unit)
        added
    }
}
