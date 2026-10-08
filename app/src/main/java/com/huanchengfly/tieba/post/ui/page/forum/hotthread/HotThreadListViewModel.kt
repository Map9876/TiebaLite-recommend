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
        HotThreadPartialChangeProducer

    override fun dispatchEvent(partialChange: HotThreadPartialChange): UiEvent? = when (partialChange) {
        is HotThreadPartialChange.Refresh.Failure -> CommonUiEvent.Toast(partialChange.error)
        is HotThreadPartialChange.LoadMore.Failure -> CommonUiEvent.Toast(partialChange.error)
        else -> null
    }

    private object HotThreadPartialChangeProducer :
        PartialChangeProducer<HotThreadUiIntent, HotThreadPartialChange, HotThreadUiState> {
        override fun toPartialChangeFlow(intentFlow: Flow<HotThreadUiIntent>): Flow<HotThreadPartialChange> =
            merge(
                intentFlow.filterIsInstance<HotThreadUiIntent.Refresh>()
                    .flatMapConcat { it.producePartialChange() },
                intentFlow.filterIsInstance<HotThreadUiIntent.LoadMore>()
                    .flatMapConcat { it.producePartialChange() },
            )

        private fun HotThreadUiIntent.Refresh.producePartialChange() =
            flow { emit(SwanTiebaApi.threads(forumName, page = 1, tabId = SwanTiebaApi.TAB_HOT)) }
                .map<List<SwanThread>, HotThreadPartialChange.Refresh> {
                    HotThreadPartialChange.Refresh.Success(it)
                }
                .onStart { emit(HotThreadPartialChange.Refresh.Start) }
                .catch { emit(HotThreadPartialChange.Refresh.Failure(it.message.orEmpty())) }

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
    data class Refresh(val forumName: String) : HotThreadUiIntent

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
    val isLoadingMore: Boolean = false,
    val data: List<SwanThread> = emptyList(),
    val currentPage: Int = 1,
    val hasMore: Boolean = true,
    val error: ImmutableHolder<String>? = null,
) : UiState

sealed interface HotThreadUiEvent : UiEvent