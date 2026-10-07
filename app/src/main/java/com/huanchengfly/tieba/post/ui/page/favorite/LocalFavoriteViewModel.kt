package com.huanchengfly.tieba.post.ui.page.favorite

import androidx.compose.runtime.Stable
import com.huanchengfly.tieba.post.arch.BaseViewModel
import com.huanchengfly.tieba.post.arch.ImmutableHolder
import com.huanchengfly.tieba.post.arch.PartialChange
import com.huanchengfly.tieba.post.arch.PartialChangeProducer
import com.huanchengfly.tieba.post.arch.UiEvent
import com.huanchengfly.tieba.post.arch.UiIntent
import com.huanchengfly.tieba.post.arch.UiState
import com.huanchengfly.tieba.post.arch.wrapImmutable
import com.huanchengfly.tieba.post.models.database.Favorite
import com.huanchengfly.tieba.post.repository.FavoriteRepository
import com.huanchengfly.tieba.post.utils.FavoriteHtmlExporter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject

enum class FavoriteExportKind { HTML, LINKS, BACKUP }

@Stable
@HiltViewModel
class LocalFavoriteViewModel @Inject constructor() :
    BaseViewModel<LocalFavoriteUiIntent, LocalFavoritePartialChange, LocalFavoriteUiState, LocalFavoriteUiEvent>() {

    override fun createInitialState(): LocalFavoriteUiState = LocalFavoriteUiState()

    override fun createPartialChangeProducer():
            PartialChangeProducer<LocalFavoriteUiIntent, LocalFavoritePartialChange, LocalFavoriteUiState> =
        LocalFavoritePartialChangeProducer

    override fun dispatchEvent(partialChange: LocalFavoritePartialChange): UiEvent? = when (partialChange) {
        is LocalFavoritePartialChange.Export.Success ->
            LocalFavoriteUiEvent.Exported(partialChange.kind, partialChange.content)

        is LocalFavoritePartialChange.Export.Failure ->
            LocalFavoriteUiEvent.Failed(partialChange.message)

        is LocalFavoritePartialChange.Import.Success ->
            LocalFavoriteUiEvent.Imported(partialChange.count)

        is LocalFavoritePartialChange.Import.Failure ->
            LocalFavoriteUiEvent.Failed(partialChange.message)

        else -> null
    }

    private object LocalFavoritePartialChangeProducer :
        PartialChangeProducer<LocalFavoriteUiIntent, LocalFavoritePartialChange, LocalFavoriteUiState> {

        private var keyword: String = ""

        @OptIn(ExperimentalCoroutinesApi::class)
        override fun toPartialChangeFlow(intentFlow: Flow<LocalFavoriteUiIntent>):
                Flow<LocalFavoritePartialChange> =
            merge(
                intentFlow.filterIsInstance<LocalFavoriteUiIntent.Refresh>()
                    .flatMapConcat { load(keyword) },
                intentFlow.filterIsInstance<LocalFavoriteUiIntent.Search>()
                    .flatMapConcat {
                        keyword = it.keyword
                        load(it.keyword)
                    },
                intentFlow.filterIsInstance<LocalFavoriteUiIntent.ToggleSelect>()
                    .map<LocalFavoriteUiIntent.ToggleSelect, LocalFavoritePartialChange> {
                        LocalFavoritePartialChange.Selection.Toggled(it.threadId)
                    },
                intentFlow.filterIsInstance<LocalFavoriteUiIntent.SelectAll>()
                    .map<LocalFavoriteUiIntent.SelectAll, LocalFavoritePartialChange> {
                        LocalFavoritePartialChange.Selection.All(it.threadIds)
                    },
                intentFlow.filterIsInstance<LocalFavoriteUiIntent.ClearSelection>()
                    .map<LocalFavoriteUiIntent.ClearSelection, LocalFavoritePartialChange> {
                        LocalFavoritePartialChange.Selection.Cleared
                    },
                intentFlow.filterIsInstance<LocalFavoriteUiIntent.DeleteSelected>()
                    .flatMapConcat { delete(it.threadIds) },
                intentFlow.filterIsInstance<LocalFavoriteUiIntent.Export>()
                    .flatMapConcat { export(it) },
                intentFlow.filterIsInstance<LocalFavoriteUiIntent.Import>()
                    .flatMapConcat { import(it.raw) },
            )

        private fun load(keyword: String): Flow<LocalFavoritePartialChange.Refresh> =
            FavoriteRepository.flow(keyword)
                .map { LocalFavoritePartialChange.Refresh.Success(it, keyword) }
                .onStart { emit(LocalFavoritePartialChange.Refresh.Start) }
                .catch { emit(LocalFavoritePartialChange.Refresh.Failure(it)) }

        private fun delete(threadIds: Collection<Long>): Flow<LocalFavoritePartialChange.Delete> =
            channelFlow {
                if (threadIds.isEmpty()) return@channelFlow
                val ids = threadIds.toList()
                ids.forEach { FavoriteRepository.remove(it) }
                send(LocalFavoritePartialChange.Delete.Success(ids))
            }

        private fun export(intent: LocalFavoriteUiIntent.Export): Flow<LocalFavoritePartialChange.Export> =
            channelFlow {
                send(LocalFavoritePartialChange.Export.Start(intent.kind))
                val ids = intent.threadIds.takeIf { it.isNotEmpty() }
                val list = if (ids == null) FavoriteRepository.getAll()
                else FavoriteRepository.getByIds(ids)
                if (list.isEmpty()) {
                    send(LocalFavoritePartialChange.Export.Failure("没有可导出的收藏"))
                    return@channelFlow
                }
                val content = when (intent.kind) {
                    FavoriteExportKind.HTML ->
                        FavoriteHtmlExporter.build(list) { done, total ->
                            send(LocalFavoritePartialChange.Export.Progress(done, total))
                        }

                    FavoriteExportKind.LINKS -> buildString {
                        appendLine("标题\t链接\t吧\t作者\t收藏时间")
                        list.forEach {
                            appendLine(
                                listOf(
                                    it.title.replace('\t', ' '),
                                    it.url.ifBlank { "https://tieba.baidu.com/p/${it.threadId}" },
                                    it.forumName,
                                    it.authorName.orEmpty(),
                                ).joinToString("\t")
                            )
                        }
                    }

                    FavoriteExportKind.BACKUP -> FavoriteRepository.exportPayload(ids)
                }
                send(LocalFavoritePartialChange.Export.Success(intent.kind, content))
            }

        private fun import(raw: String): Flow<LocalFavoritePartialChange.Import> = channelFlow {
            send(LocalFavoritePartialChange.Import.Start)
            val added = runCatching { FavoriteRepository.importPayload(raw) }
                .getOrElse {
                    send(LocalFavoritePartialChange.Import.Failure("导入失败：不是本应用导出的收藏文件"))
                    return@channelFlow
                }
            send(LocalFavoritePartialChange.Import.Success(added))
        }
    }
}

sealed interface LocalFavoriteUiIntent : UiIntent {
    data object Refresh : LocalFavoriteUiIntent

    data class Search(val keyword: String) : LocalFavoriteUiIntent

    data class ToggleSelect(val threadId: Long) : LocalFavoriteUiIntent

    data class SelectAll(val threadIds: List<Long>) : LocalFavoriteUiIntent

    data object ClearSelection : LocalFavoriteUiIntent

    data class DeleteSelected(val threadIds: List<Long>) : LocalFavoriteUiIntent

    data class Export(val kind: FavoriteExportKind, val threadIds: List<Long> = emptyList()) :
        LocalFavoriteUiIntent

    data class Import(val raw: String) : LocalFavoriteUiIntent
}

sealed interface LocalFavoritePartialChange : PartialChange<LocalFavoriteUiState> {
    sealed class Refresh : LocalFavoritePartialChange {
        override fun reduce(oldState: LocalFavoriteUiState): LocalFavoriteUiState = when (this) {
            Start -> oldState.copy(isLoading = true)
            is Failure -> oldState.copy(isLoading = false, error = wrapImmutable(error))
            is Success -> oldState.copy(
                isLoading = false,
                data = data,
                keyword = keyword,
                error = null,
                selected = oldState.selected intersect data.map { it.threadId }.toSet(),
            )
        }

        data object Start : Refresh()

        data class Success(val data: List<Favorite>, val keyword: String) : Refresh()

        data class Failure(val error: Throwable) : Refresh()
    }

    sealed interface Selection : LocalFavoritePartialChange {
        data class Toggled(val threadId: Long) : Selection {
            override fun reduce(oldState: LocalFavoriteUiState): LocalFavoriteUiState =
                oldState.copy(
                    selected = if (threadId in oldState.selected) oldState.selected - threadId
                    else oldState.selected + threadId
                )
        }

        data class All(val threadIds: List<Long>) : Selection {
            override fun reduce(oldState: LocalFavoriteUiState): LocalFavoriteUiState =
                oldState.copy(selected = threadIds.toSet())
        }

        data object Cleared : Selection {
            override fun reduce(oldState: LocalFavoriteUiState): LocalFavoriteUiState =
                oldState.copy(selected = emptySet())
        }
    }

    data class Delete(val threadIds: List<Long>) : LocalFavoritePartialChange {
        override fun reduce(oldState: LocalFavoriteUiState): LocalFavoriteUiState =
            oldState.copy(
                data = oldState.data.filterNot { it.threadId in threadIds },
                selected = emptySet()
            )
    }

    sealed class Export : LocalFavoritePartialChange {
        override fun reduce(oldState: LocalFavoriteUiState): LocalFavoriteUiState = when (this) {
            Start -> oldState.copy(exporting = true, exportProgress = null)
            is Progress -> oldState.copy(
                exporting = true,
                exportProgress = "正在抓图 $done/$total",
            )

            is Success -> oldState.copy(exporting = false, exportProgress = null)
            is Failure -> oldState.copy(exporting = false, exportProgress = null)
        }

        data class Start(val kind: FavoriteExportKind) : Export()

        data class Progress(val done: Int, val total: Int) : Export()

        data class Success(val kind: FavoriteExportKind, val content: String) : Export()

        data class Failure(val message: String) : Export()
    }

    sealed class Import : LocalFavoritePartialChange {
        override fun reduce(oldState: LocalFavoriteUiState): LocalFavoriteUiState = when (this) {
            Start -> oldState.copy(importing = true)
            is Success -> oldState.copy(importing = false)
            is Failure -> oldState.copy(importing = false)
        }

        data object Start : Import()

        data class Success(val count: Int) : Import()

        data class Failure(val message: String) : Import()
    }
}

data class LocalFavoriteUiState(
    val isLoading: Boolean = false,
    val keyword: String = "",
    val data: List<Favorite> = emptyList(),
    val selected: Set<Long> = emptySet(),
    val exporting: Boolean = false,
    val exportProgress: String? = null,
    val importing: Boolean = false,
    val error: ImmutableHolder<Throwable>? = null,
) : UiState {
    val hasSelection: Boolean
        get() = selected.isNotEmpty()
}

sealed interface LocalFavoriteUiEvent : UiEvent {
    data class Exported(val kind: FavoriteExportKind, val content: String) : LocalFavoriteUiEvent

    data class Imported(val count: Int) : LocalFavoriteUiEvent

    data class Failed(val message: String) : LocalFavoriteUiEvent
}