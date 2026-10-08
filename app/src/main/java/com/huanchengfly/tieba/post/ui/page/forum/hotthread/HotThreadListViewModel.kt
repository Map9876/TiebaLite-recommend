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
                    // 锚点帖还在不在这个页里。只回答「在/不在」——
                    // 漂了多少页取决于每个吧的发帖密度，没有可靠算法能算，
                    // 与其编个数字误导人，不如只说事实。
                    val anchorPresent =
                        anchorTid == 0L || threads.any { it.tid == anchorTid }
                    HotThreadPartialChange.JumpTo.Success(threads, page, anchorPresent)
                }
                .onStart { emit(HotThreadPartialChange.JumpTo.Start(page)) }
                .catch { emit(HotThreadPartialChange.JumpTo.Failure(it.message.orEmpty(), page)) }

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
                anchorMissing = !anchorPresent
            )

            is Failure -> oldState.copy(isRefreshing = false)
        }

        data class Start(val page: Int) : JumpTo()

        data class Success(
            val data: List<SwanThread>,
            val page: Int,
            /** 锚点帖子是否还在这一页里；false 说明榜面已经重排过 */
            val anchorPresent: Boolean = true,
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
    /** 上次跳页时锚点帖不在目标页里 —— 榜面已经重排，只提示不量化 */
    val anchorMissing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val data: List<SwanThread> = emptyList(),
    val currentPage: Int = 1,
    val hasMore: Boolean = true,
    val error: ImmutableHolder<String>? = null,
) : UiState

sealed interface HotThreadUiEvent : UiEvent