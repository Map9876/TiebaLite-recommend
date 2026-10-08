package com.huanchengfly.tieba.post.ui.page.forum.hotthread

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.IconButton
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Text
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.huanchengfly.tieba.post.R
import com.huanchengfly.tieba.post.api.swan.SwanTiebaApi
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
import com.huanchengfly.tieba.post.ui.widgets.compose.PromptDialog
import com.huanchengfly.tieba.post.ui.widgets.compose.NetworkImage
import com.huanchengfly.tieba.post.ui.widgets.compose.rememberDialogState
import com.huanchengfly.tieba.post.ui.widgets.compose.Sizes
import com.huanchengfly.tieba.post.ui.widgets.compose.ThreadAgreeBtn
import com.huanchengfly.tieba.post.ui.widgets.compose.ThreadContent
import com.huanchengfly.tieba.post.ui.widgets.compose.ThreadForumInfo
import com.huanchengfly.tieba.post.ui.widgets.compose.ThreadReplyBtn
import com.huanchengfly.tieba.post.ui.widgets.compose.ThreadShareBtn
import com.huanchengfly.tieba.post.ui.widgets.compose.UserHeader
import com.huanchengfly.tieba.post.ui.widgets.compose.states.StateScreen
import com.huanchengfly.tieba.post.models.database.ForumBrowse
import com.huanchengfly.tieba.post.utils.FavoriteHtmlExporter
import com.huanchengfly.tieba.post.models.database.ForumPageSample
import com.huanchengfly.tieba.post.utils.DateTimeUtils.getRelativeTimeString
import com.huanchengfly.tieba.post.utils.ForumBrowseMemory
import com.huanchengfly.tieba.post.utils.ForumPageSampler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

        // 榜面重排：锚点帖不在目标页里了。只说「位置可能不准」，
        // 不给漂移页数——那取决于每个吧的发帖密度，编不出来
        val anchorMissing by viewModel.uiState.collectPartialAsState(
            prop1 = HotThreadUiState::anchorMissing,
            initial = false
        )

        // 上次翻到哪了
        var lastSeen by remember(forumName) { mutableStateOf<ForumBrowse?>(null) }
        var samples by remember(forumName) { mutableStateOf(emptyList<ForumPageSample>()) }
        var jumpHint by remember(forumName) { mutableStateOf<String?>(null) }
        val jumpToDateDialog = rememberDialogState()
        LaunchedEffect(forumName) {
            lastSeen = ForumBrowseMemory.load(forumName, SwanTiebaApi.TAB_HOT)
            samples = ForumPageSampler.samples(forumName, SwanTiebaApi.TAB_HOT)
        }

        // 后台锚定：按 2/4/8/16… 的稀疏序列自动采样，把「页码↔日期」表建起来，
        // 这样「跳到 9 月初」能估出大致页码。离开页面时协程自动取消。
        LaunchedEffect(forumName) {
            runCatching {
                ForumPageSampler.backgroundProbe(forumName, SwanTiebaApi.TAB_HOT) { probePage ->
                    SwanTiebaApi.threads(
                        forumName = forumName,
                        page = probePage,
                        tabId = SwanTiebaApi.TAB_HOT
                    )
                }
            }
            samples = ForumPageSampler.samples(forumName, SwanTiebaApi.TAB_HOT)
        }
        // 每拿到一批就把进度记下来（后台协程，不挡UI）
        LaunchedEffect(data.size, currentPage, forumName) {
            if (data.isEmpty()) return@LaunchedEffect
            withContext(Dispatchers.IO) {
                ForumBrowseMemory.record(
                    forumName, SwanTiebaApi.TAB_HOT, currentPage, data
                )
                // 顺手攒一个「页码 → 时间范围」的采样点，用来估算「看某个月大概翻到第几页」。
                // 不额外发请求：这批数据本来就是刚拉回来的
                ForumPageSampler.record(forumName, SwanTiebaApi.TAB_HOT, currentPage, data)
            }
        }

        // 「跳到指定日期」：靠采样点插值估算页码，估不准也无所谓，往下翻能接上
        PromptDialog(
            dialogState = jumpToDateDialog,
            onConfirm = { input ->
                val target = parseTargetDate(input)
                if (target == null) {
                    jumpHint = "看不懂这个日期，试试 2026-09-01 或 0901"
                } else {
                    val page = ForumPageSampler.estimatePage(samples, target)
                    if (page == null) {
                        val oldest = ForumPageSampler.oldestCovered(samples)
                        jumpHint = if (oldest <= 0) {
                            "还没有采样点，先往后翻几页再试"
                        } else {
                            "现有采样只探到 ${formatDay(oldest)}，" +
                                "再往后翻几页就能定位到更早的"
                        }
                    } else {
                        viewModel.send(
                            HotThreadUiIntent.JumpTo(
                                forumName = forumName,
                                page = page,
                                anchorTid = lastSeen?.anchorTid ?: 0L
                            )
                        )
                        jumpHint = "已跳到第 $page 页附近，往下翻对照标题找 ${
                            formatDay(target)
                        }的帖子"
                    }
                }
            },
            title = { Text(text = "跳到指定日期") },
        ) {
            Text(
                text = "输入日期，如 2026-09-01 或 0901",
                fontSize = 12.sp,
                color = ExtendedTheme.colors.textSecondary,
            )
        }

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
                onRefresh = { viewModel.send(HotThreadUiIntent.Refresh(forumName, force = true)) }
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
                        stickyHeader(key = "HotHeader") {
                            // 手动刷新入口：放在 sticky 里，上划不会被划走
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(ExtendedTheme.colors.background)
                                    .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 6.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.tab_forum_hot),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ExtendedTheme.colors.textSecondary,
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "· ${data.size}",
                                    fontSize = 12.sp,
                                    color = ExtendedTheme.colors.textSecondary,
                                )
                                Spacer(modifier = Modifier.weight(1f))
                                // 记住上次翻到的日期，下次从那儿接着翻
                                lastSeen?.let { seen ->
                                    Text(
                                        text = "· 上次看到 ${formatDay(seen.oldestSeenTime)}",
                                        fontSize = 12.sp,
                                        color = ExtendedTheme.colors.textSecondary,
                                        modifier = Modifier.clickable {
                                            viewModel.send(
                                                HotThreadUiIntent.JumpTo(
                                                    forumName = forumName,
                                                    page = seen.maxPage.coerceAtLeast(1),
                                                    anchorTid = seen.anchorTid
                                                )
                                            )
                                        }
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        viewModel.send(
                                            HotThreadUiIntent.JumpTo(
                                                forumName = forumName,
                                                page = (lastSeen?.maxPage ?: 0) + 1,
                                                anchorTid = lastSeen?.anchorTid ?: 0L
                                            )
                                        )
                                    },
                                    enabled = !isRefreshing && lastSeen != null,
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.KeyboardArrowDown,
                                        contentDescription = "继续往后翻",
                                        tint = ExtendedTheme.colors.textSecondary,
                                    )
                                }
                                IconButton(
                                    onClick = { jumpToDateDialog.show() },
                                    enabled = !isRefreshing,
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.DateRange,
                                        contentDescription = "跳到指定日期",
                                        tint = ExtendedTheme.colors.textSecondary,
                                    )
                                }
                                if (isRefreshing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .padding(4.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    IconButton(
                                        onClick = {
                                            viewModel.send(
                                                HotThreadUiIntent.Refresh(forumName, force = true)
                                            )
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Refresh,
                                            contentDescription = "刷新",
                                            tint = ExtendedTheme.colors.textSecondary,
                                        )
                                    }
                                }
                            }
                        }
                        if (jumpHint != null) {
                            item(key = "JumpHint") {
                                Text(
                                    text = jumpHint.orEmpty(),
                                    fontSize = 11.sp,
                                    color = ExtendedTheme.colors.textSecondary,
                                    modifier = Modifier.padding(
                                        start = 16.dp, end = 16.dp, bottom = 6.dp
                                    )
                                )
                            }
                        }
                        if (anchorMissing) {
                            item(key = "AnchorMissing") {
                                Text(
                                    text = "热门榜已重排，上次的位置可能不准了，可以往下翻对照标题找回来",
                                    fontSize = 11.sp,
                                    color = ExtendedTheme.colors.textSecondary,
                                    modifier = Modifier.padding(
                                        start = 16.dp, end = 16.dp, bottom = 6.dp
                                    ),
                                )
                            }
                        }
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

/**
 * 解析用户输入的目标日期。支持 `2026-09-01`、`2026/9/1`、`0901`。
 * 解析不了返回 null，交给调用方提示——不猜。
 */
private fun parseTargetDate(input: String): Long? {
    val text = input.trim()
    if (text.isEmpty()) return null
    val (y, m, d) = when {
        Regex("^\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}$").matches(text) -> {
            val p = text.split('-', '/')
            Triple(p[0].toInt(), p[1].toInt(), p[2].toInt())
        }

        // 20260901
        Regex("^\\d{8}$").matches(text) -> {
            Triple(
                text.substring(0, 4).toInt(),
                text.substring(4, 6).toInt(),
                text.substring(6, 8).toInt()
            )
        }

        // 0901
        Regex("^\\d{4}$").matches(text) -> {
            // 0901 这种：补上当前年份
            Triple(java.util.Calendar.getInstance().get(java.util.Calendar.YEAR),
                text.substring(0, 2).toInt(), text.substring(2).toInt())
        }

        else -> return null
    }
    return runCatching {
        java.util.Calendar.getInstance().apply {
            clear()
            set(y, m - 1, d, 12, 0, 0)
        }.timeInMillis / 1000
    }.getOrNull()
}

private fun formatDay(timeSeconds: Long): String =
    if (timeSeconds <= 0) "-" else java.text.SimpleDateFormat("MM-dd", java.util.Locale.getDefault())
        .format(java.util.Date(timeSeconds * 1000))

@Composable
private fun HotThreadCard(
    thread: SwanThread,
    forumName: String,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val createTime = remember(thread.tid, thread.createTime) {
        thread.createTime.takeIf { it > 0 }?.let { getRelativeTimeString(context, it * 1000) }
    }
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
                desc = createTime?.let {
                    { Text(text = it, fontSize = 11.sp, color = ExtendedTheme.colors.textSecondary) }
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
                        coverUrl = thread.pics.firstOrNull()
                            ?.let { FavoriteHtmlExporter.stripQuery(it) },
                    )
                )
            }
        },
        onClick = onClick,
    )
}