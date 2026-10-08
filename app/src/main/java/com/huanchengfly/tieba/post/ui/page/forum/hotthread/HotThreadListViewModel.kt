package com.huanchengfly.tieba.post.ui.page.forum.hotthread

import androidx.compose.runtime.Stable
import com.huanchengfly.tieba.post.api.swan.SwanTiebaApi
import com.huanchengfly.tieba.post.arch.BaseViewModel
import com.huanchengfly.tieba.post.arch.CommonUiEvent
import com.huanchengfly.tieba.post.arch.ImmutableHolder
import com.huanchengfly.tieba.post.arch.PartialChange
import com.huanchengfly.tieba.post.arch.PartialChangeProducer
import com.huanchengfly.tieba.post.arch.UiEvent
import com.huanchengfly.tieba.post.arch.UiIntent
import com.huanchengfly.tieba.post.arch.UiState
import com.huanchengfly.tieba.post.arch.wrapImmutable
import com.huanchengfly.tieba.post.models.SwanThread
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject

/** 吧内「热门」tab —— 数据来自百度贴吧小程序 frs/page 接口（免登录） */
@Stable
@HiltViewModel
class HotThreadListViewModel @Inject constructor() :
    BaseViewModel<HotThreadUiIntent, HotThreadPartialChange, HotThreadUiState, HotThreadUiEvent>() {

    override fun createInitialState(): HotThreadUiState = HotThreadUiState()

    override fun createPartialChangeProducer():
            PartialChangeProducer<HotThreadUiIntent, HotThreadPartialChange, HotThreadUiState> =
        HotThreadPartialChangeProducer()

    override fun dispatchEvent(partialChange: HotThreadPartialChange): UiEvent? = when (partialChange) {
        is HotThreadPartialChange.Refresh.Failure -> CommonUiEvent.Toast(partialChange.error)
        is HotThreadPartialChange.LoadMore.Failure -> CommonUiEvent.Toast(partialChange.error)
        else -> null
    }

    /**
     * 记住已经加载过的吧：从帖子返回时页面会重新进组合，
     * 如果这里不拦一下就会又发一次请求，用户看到的就是「列表自己刷新了」。
     */
    private class HotThreadPartialChangeProducer :
        PartialChangeProducer<HotThreadUiIntent, HotThreadPartialChange, HotThreadUiState> {
        private var loadedForum: String? = null

        override fun toPartialChangeFlow(intentFlow: Flow<HotThreadUiIntent>): Flow<HotThreadPartialChange> =
            merge(
                intentFlow.filterIsInstance<HotThreadUiIntent.Refresh>()
                    .filter { intent ->
                        val need = intent.force || loadedForum != intent.forumName
                        if (need) loadedForum = intent.forumName
                        need
                    }
                    .flatMapConcat { it.producePartialChange() },
                intentFlow.filterIsInstance<HotThreadUiIntent.LoadMore>()
                    .flatMapConcat { it.producePartialChange() },
                intentFlow.filterIsInstance<HotThreadUiIntent.JumpTo>()
                    .flatMapConcat { it.producePartialChange() },
            )

        private fun HotThreadUiIntent.Refresh.producePartialChange() =
            flow { emit(SwanTiebaApi.threads(forumName, page = 1, tabId = SwanTiebaApi.TAB_HOT)) }
                .map<List<SwanThread>, HotThreadPartialChange.Refresh> {
                    HotThreadPartialChange.Refresh.Success(it)
                }
                .onStart { emit(HotThreadPartialChange.Refresh.Start) }
                .catch { emit(HotThreadPartialChange.Refresh.Failure(it.message.orEmpty())) }

        private fun HotThreadUiIntent.JumpTo.producePartialChange() =
            flow<List<SwanThread>> {
                emit(SwanTiebaApi.threads(forumName, page = page, tabId = SwanTiebaApi.TAB_HOT))
            }
                .map<List<SwanThread>, HotThreadPartialChange.JumpTo> { threads ->
                    val hit = anchorTid != 0L && threads.any { it.tid == anchorTid }
                    HotThreadPartialChange.JumpTo.Success(
                        threads, page,
                        anchorVerified = hit || anchorTid == 0L,
                        drift = if (hit || anchorTid == 0L) 0 else driftOf(threads, anchorTime)
                    )
                }
                .onStart { emit(HotThreadPartialChange.JumpTo.Start(page)) }
                .catch { emit(HotThreadPartialChange.JumpTo.Failure(it.message.orEmpty(), page)) }

        /**
         * 榜面往前漂了多少页。
         *
         * 锚点帖子不在目标页里，说明它被新帖挤到后面去了。拿锚点当初的
         * 发帖时间跟这一页最新的帖子比：锚点比这一页还新，说明中间插进了
         * 若干页新内容，得往后翻那么多页才能接上。
         *
         * 经验值：30 条/页 的吧约 3~5 页/天，这里取 4，只用来给用户一个
         * 「大概漂了多久」的提示，不当作精确值。
         */
        private fun driftOf(threads: List<SwanThread>, anchorTime: Long): Int {
            if (anchorTime <= 0) return 0
            val newest = threads.maxOfOrNull { it.createTime } ?: return 0
            val days = (anchorTime - newest).toDouble() / 86400.0
            if (days <= 0) return 0
            return (days * 4).toInt().coerceAtLeast(1)
        }

        private fun HotThreadUiIntent.LoadMore.producePartialChange() =
            flow { emit(SwanTiebaApi.threads(forumName, page = page, tabId = SwanTiebaApi.TAB_HOT)) }
                .map<List<SwanThread>, HotThreadPartialChange.LoadMore> {
                    HotThreadPartialChange.LoadMore.Success(it, page)
                }
                .onStart { emit(HotThreadPartialChange.LoadMore.Start) }
                .catch { emit(HotThreadPartialChange.LoadMore.Failure(it.message.orEmpty())) }
    }
}

sealed interface HotThreadUiIntent : UiIntent {
    /** [force] 为 true 时无视「已加载过」的判断，强制重新拉 */
    data class Refresh(val forumName: String, val force: Boolean = false) : HotThreadUiIntent

    /**
     * 直接跳到指定页。用「继续翻上次的位置」或手动输入页码。
     * [anchorTid] 是上次记下的锚点帖子，用来判断榜面漂移了多少。
     */
    data class JumpTo(
        val forumName: String,
        val page: Int,
        val anchorTid: Long = 0L,
        val anchorTime: Long = 0L,
    ) : HotThreadUiIntent

    data class LoadMore(val forumName: String, val page: Int) : HotThreadUiIntent
}

sealed interface HotThreadPartialChange : PartialChange<HotThreadUiState> {
    sealed class Refresh : HotThreadPartialChange {
        override fun reduce(oldState: HotThreadUiState): HotThreadUiState = when (this) {
            Start -> oldState.copy(isRefreshing = true)
            is Success -> oldState.copy(
                isRefreshing = false,
                data = data,
                currentPage = 1,
                hasMore = data.isNotEmpty(),
                error = null
            )

            is Failure -> oldState.copy(isRefreshing = false, error = wrapImmutable(error))
        }

        data object Start : Refresh()

        data class Success(val data: List<SwanThread>) : Refresh()

        data class Failure(val error: String) : Refresh()
    }

    /** 跳页：结果直接替换整个列表，并把 currentPage 设成目标页 */
    sealed class JumpTo : HotThreadPartialChange {
        override fun reduce(oldState: HotThreadUiState): HotThreadUiState = when (this) {
            is Start -> oldState.copy(isRefreshing = true)
            is Success -> oldState.copy(
                isRefreshing = false,
                data = data.distinctBy { it.tid },
                currentPage = page,
                hasMore = data.isNotEmpty(),
                driftHint = drift
            )

            is Failure -> oldState.copy(isRefreshing = false)
        }

        data class Start(val page: Int) : JumpTo()

        data class Success(
            val data: List<SwanThread>,
            val page: Int,
            /** 锚点帖子是否还在这一页里；false 说明榜面已经重排过 */
            val anchorVerified: Boolean = true,
            /** 估计漂移了多少页，仅用于提示 */
            val drift: Int = 0,
        ) : JumpTo()

        data class Failure(val error: String, val page: Int) : JumpTo()
    }

    sealed class LoadMore : HotThreadPartialChange {
        override fun reduce(oldState: HotThreadUiState): HotThreadUiState = when (this) {
            Start -> oldState.copy(isLoadingMore = true)
            is Success -> {
                val merged = (oldState.data + data).distinctBy { it.tid }
                oldState.copy(
                    isLoadingMore = false,
                    data = merged,
                    currentPage = page,
                    hasMore = data.isNotEmpty()
                )
            }

            is Failure -> oldState.copy(isLoadingMore = false)
        }

        data object Start : LoadMore()

        data class Success(val data: List<SwanThread>, val page: Int) : LoadMore()

        data class Failure(val error: String) : LoadMore()
    }
}

data class HotThreadUiState(
    val isRefreshing: Boolean = false,
    /** 上次跳页时榜面漂移了多少页（>0 表示锚点帖被挤走了），UI 据此给提示 */
    val driftHint: Int = 0,
    val isLoadingMore: Boolean = false,
    val data: List<SwanThread> = emptyList(),
    val currentPage: Int = 1,
    val hasMore: Boolean = true,
    val error: ImmutableHolder<String>? = null,
) : UiState

sealed interface HotThreadUiEvent : UiEvent