package com.huanchengfly.tieba.post.ui.page.forum.hotthread

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.Text
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.huanchengfly.tieba.post.R
import com.huanchengfly.tieba.post.arch.collectPartialAsState
import com.huanchengfly.tieba.post.arch.pageViewModel
import com.huanchengfly.tieba.post.models.SwanThread
import com.huanchengfly.tieba.post.models.ThreadFavoriteInfo
import com.huanchengfly.tieba.post.ui.common.theme.compose.ExtendedTheme
import com.huanchengfly.tieba.post.ui.common.theme.compose.pullRefreshIndicator
import com.huanchengfly.tieba.post.ui.page.LocalNavigator
import com.huanchengfly.tieba.post.ui.page.destinations.ThreadPageDestination
import com.huanchengfly.tieba.post.ui.widgets.compose.Avatar
import com.huanchengfly.tieba.post.ui.widgets.compose.Card
import com.huanchengfly.tieba.post.ui.widgets.compose.ErrorScreen
import com.huanchengfly.tieba.post.ui.widgets.compose.LoadMoreLayout
import com.huanchengfly.tieba.post.ui.widgets.compose.MyLazyColumn
import com.huanchengfly.tieba.post.ui.widgets.compose.NetworkImage
import com.huanchengfly.tieba.post.ui.widgets.compose.Sizes
import com.huanchengfly.tieba.post.ui.widgets.compose.ThreadAgreeBtn
import com.huanchengfly.tieba.post.ui.widgets.compose.ThreadContent
import com.huanchengfly.tieba.post.ui.widgets.compose.ThreadForumInfo
import com.huanchengfly.tieba.post.ui.widgets.compose.ThreadReplyBtn
import com.huanchengfly.tieba.post.ui.widgets.compose.ThreadShareBtn
import com.huanchengfly.tieba.post.ui.widgets.compose.UserHeader
import com.huanchengfly.tieba.post.ui.widgets.compose.states.StateScreen

/**
 * 吧内「热门」列表。数据来自百度贴吧小程序 frs/page 接口（免登录），
 * 卡片样式复用原帖子的 FeedCard 组件。
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterialApi::class)
@Composable
fun HotThreadListPage(
    forumName: String,
    modifier: Modifier = Modifier,
    viewModel: HotThreadListViewModel = pageViewModel(),
) {
    val navigator = LocalNavigator.current
        LaunchedEffect(Unit) {
            viewModel.send(HotThreadUiIntent.Refresh(forumName))
        }

        val isRefreshing by viewModel.uiState.collectPartialAsState(
            prop1 = HotThreadUiState::isRefreshing,
            initial = true
        )
        val isLoadingMore by viewModel.uiState.collectPartialAsState(
            prop1 = HotThreadUiState::isLoadingMore,
            initial = false
        )
        val data by viewModel.uiState.collectPartialAsState(
            prop1 = HotThreadUiState::data,
            initial = emptyList()
        )
        val currentPage by viewModel.uiState.collectPartialAsState(
            prop1 = HotThreadUiState::currentPage,
            initial = 1
        )
        val hasMore by viewModel.uiState.collectPartialAsState(
            prop1 = HotThreadUiState::hasMore,
            initial = true
        )
        val error by viewModel.uiState.collectPartialAsState(
            prop1 = HotThreadUiState::error,
            initial = null
        )
        val isError by remember { derivedStateOf { error != null } }

        StateScreen(
            isEmpty = data.isEmpty(),
            isError = isError,
            isLoading = isRefreshing,
            modifier = modifier.fillMaxSize(),
            onReload = { viewModel.send(HotThreadUiIntent.Refresh(forumName)) },
            errorScreen = { error?.let { ErrorScreen(error = IllegalStateException(it.get())) } },
        ) {
            val pullRefreshState = rememberPullRefreshState(
                refreshing = isRefreshing,
                onRefresh = { viewModel.send(HotThreadUiIntent.Refresh(forumName)) }
            )
            val lazyListState = rememberLazyListState()
            Box(modifier = Modifier.pullRefresh(pullRefreshState)) {
                LoadMoreLayout(
                    isLoading = isLoadingMore,
                    onLoadMore = {
                        viewModel.send(HotThreadUiIntent.LoadMore(forumName, currentPage + 1))
                    },
                    loadEnd = !hasMore,
                    lazyListState = lazyListState
                ) {
                    MyLazyColumn(state = lazyListState) {
                        items(items = data, key = { it.tid }) { thread ->
                            HotThreadCard(
                                thread = thread,
                                forumName = forumName,
                                onClick = { navigator.navigate(ThreadPageDestination(thread.tid)) }
                            )
                        }
                    }
                }
                PullRefreshIndicator(
                    refreshing = isRefreshing,
                    state = pullRefreshState,
                    modifier = Modifier.align(Alignment.TopCenter),
                    backgroundColor = ExtendedTheme.colors.pullRefreshIndicator,
                    contentColor = ExtendedTheme.colors.primary,
                )
            }
        }
}

@Composable
private fun HotThreadCard(
    thread: SwanThread,
    forumName: String,
    onClick: () -> Unit,
) {
    Card(
        header = {
            UserHeader(
                avatar = {
                    Avatar(
                        data = thread.authorAvatar,
                        size = Sizes.Small,
                        contentDescription = null
                    )
                },
                name = {
                    Text(
                        text = thread.authorName.ifBlank { "匿名" },
                        color = ExtendedTheme.colors.text,
                        fontWeight = FontWeight.Bold
                    )
                },
            )
        },
        content = {
            ThreadContent(
                title = thread.title,
                abstractText = thread.abstractText,
                showTitle = thread.title.isNotBlank(),
                showAbstract = thread.abstractText.isNotBlank(),
                maxLines = 6,
            )
            if (thread.pics.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    thread.pics.take(3).forEach { pic ->
                        NetworkImage(
                            imageUri = pic,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .width(96.dp)
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(6.dp))
                        )
                    }
                }
            }
            ThreadForumInfo(forumName = forumName, forumAvatar = null, onClick = onClick)
        },
        action = {
            Row(modifier = Modifier.fillMaxWidth()) {
                ThreadShareBtn(
                    shareNum = thread.shareNum.toLong(),
                    onClick = {},
                    modifier = Modifier.weight(1f)
                )
                ThreadReplyBtn(
                    replyNum = thread.replyNum,
                    onClick = {},
                    modifier = Modifier.weight(1f)
                )
                ThreadAgreeBtn(
                    hasAgree = false,
                    agreeNum = thread.agreeNum,
                    onClick = {},
                    modifier = Modifier.weight(1f),
                    favoriteInfo = ThreadFavoriteInfo(
                        threadId = thread.tid,
                        title = thread.title,
                        forumName = forumName,
                        authorName = thread.authorName,
                        abstractText = thread.abstractText,
                        url = thread.url,
                    )
                )
            }
        },
        onClick = onClick,
    )
}