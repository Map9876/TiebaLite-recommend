package com.huanchengfly.tieba.post.ui.page.favorite

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.IntrinsicSize
import com.huanchengfly.tieba.post.utils.FavoriteSearchHelper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.rememberScaffoldState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.huanchengfly.tieba.post.BuildConfig
import com.huanchengfly.tieba.post.R
import com.huanchengfly.tieba.post.arch.collectPartialAsState
import com.huanchengfly.tieba.post.arch.onEvent
import com.huanchengfly.tieba.post.arch.pageViewModel
import com.huanchengfly.tieba.post.models.database.Favorite
import com.huanchengfly.tieba.post.repository.FavoriteRepository
import com.huanchengfly.tieba.post.utils.FavoriteCoverRefresher
import com.huanchengfly.tieba.post.utils.FavoriteExportDir
import com.huanchengfly.tieba.post.ui.common.theme.compose.ExtendedTheme
import com.huanchengfly.tieba.post.ui.page.destinations.ThreadPageDestination
import com.huanchengfly.tieba.post.ui.widgets.compose.ActionItem
import com.huanchengfly.tieba.post.ui.widgets.compose.BackNavigationIcon
import com.huanchengfly.tieba.post.ui.widgets.compose.Button
import com.huanchengfly.tieba.post.ui.widgets.compose.Card
import com.huanchengfly.tieba.post.ui.widgets.compose.ConfirmDialog
import com.huanchengfly.tieba.post.ui.widgets.compose.ErrorScreen
import com.huanchengfly.tieba.post.ui.widgets.compose.HighlightText
import com.huanchengfly.tieba.post.ui.widgets.compose.LongClickMenu
import com.huanchengfly.tieba.post.ui.widgets.compose.MyLazyColumn
import com.huanchengfly.tieba.post.ui.widgets.compose.MyScaffold
import com.huanchengfly.tieba.post.ui.widgets.compose.NetworkImage
import com.huanchengfly.tieba.post.ui.widgets.compose.TextButton
import com.huanchengfly.tieba.post.ui.widgets.compose.TipScreen
import com.huanchengfly.tieba.post.ui.widgets.compose.TitleCentredToolbar
import com.huanchengfly.tieba.post.ui.widgets.compose.rememberDialogState
import com.huanchengfly.tieba.post.ui.widgets.compose.rememberMenuState
import com.huanchengfly.tieba.post.ui.widgets.compose.states.StateScreen
import com.huanchengfly.tieba.post.utils.DateTimeUtils.getRelativeTimeString
import com.huanchengfly.tieba.post.utils.FavoriteHtmlExporter
import com.huanchengfly.tieba.post.utils.TiebaUtil
import com.ramcosta.composedestinations.annotation.DeepLink
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val PROVIDER = "${BuildConfig.APPLICATION_ID}.share.FileProvider"

@OptIn(ExperimentalMaterialApi::class)
@Destination(
    deepLinks = [
        DeepLink(uriPattern = "tblite://favorite_local")
    ]
)
@Composable
fun LocalFavoritePage(
    navigator: DestinationsNavigator,
    viewModel: LocalFavoriteViewModel = pageViewModel(),
) {
    LaunchedEffect(Unit) {
        viewModel.send(LocalFavoriteUiIntent.Refresh)
    }

    // 收藏/取消收藏是异步写库的，这里订阅「写完了」的通知重新查一次，
    // 不然刚点完爱心跳进来会看不到那条
    LaunchedEffect(Unit) {
        FavoriteRepository.changes.collect {
            viewModel.send(LocalFavoriteUiIntent.Refresh)
        }
    }

    val context = LocalContext.current
    val scaffoldState = rememberScaffoldState()

    val isLoading by viewModel.uiState.collectPartialAsState(
        prop1 = LocalFavoriteUiState::isLoading,
        initial = true
    )
    val keyword by viewModel.uiState.collectPartialAsState(
        prop1 = LocalFavoriteUiState::keyword,
        initial = ""
    )
    val data by viewModel.uiState.collectPartialAsState(
        prop1 = LocalFavoriteUiState::data,
        initial = emptyList()
    )
    val selected by viewModel.uiState.collectPartialAsState(
        prop1 = LocalFavoriteUiState::selected,
        initial = emptySet()
    )
    val exporting by viewModel.uiState.collectPartialAsState(
        prop1 = LocalFavoriteUiState::exporting,
        initial = false
    )
    val exportProgress by viewModel.uiState.collectPartialAsState(
        prop1 = LocalFavoriteUiState::exportProgress,
        initial = null
    )
    val error by viewModel.uiState.collectPartialAsState(
        prop1 = LocalFavoriteUiState::error,
        initial = null
    )
    val isError by remember { derivedStateOf { error != null } }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val raw = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        if (raw.isNullOrBlank()) {
            viewModel.send(LocalFavoriteUiIntent.Import("\u0000"))
        } else {
            viewModel.send(LocalFavoriteUiIntent.Import(raw))
        }
    }

    viewModel.onEvent<LocalFavoriteUiEvent.Failed> {
        scaffoldState.snackbarHostState.showSnackbar(it.message)
    }
    viewModel.onEvent<LocalFavoriteUiEvent.Imported> {
        viewModel.send(LocalFavoriteUiIntent.Refresh)
        scaffoldState.snackbarHostState.showSnackbar(
            context.getString(R.string.toast_favorite_imported, it.count)
        )
    }
    viewModel.onEvent<LocalFavoriteUiEvent.Exported> { event ->
        runCatching {
            val result = when (event.kind) {
                FavoriteExportKind.HTML -> FavoriteExportDir.write(
                    context, event.content, "tieba_favorites", "html"
                )

                FavoriteExportKind.LINKS -> FavoriteExportDir.write(
                    context, event.content, "tieba_favorites_links", "txt"
                )

                FavoriteExportKind.BACKUP -> FavoriteExportDir.write(
                    context, event.content, "tieba_favorites_backup", "json"
                )
            }
            // 私有目录用 FileProvider，用户目录直接用 SAF 给回来的 Uri
            val shareUri: Uri = when (val target = result.uri) {
                is File -> FileProvider.getUriForFile(context, PROVIDER, target)
                is Uri -> target
                else -> throw IllegalStateException("未知的导出目标")
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = result.mime
                putExtra(Intent.EXTRA_STREAM, shareUri)
                putExtra(Intent.EXTRA_SUBJECT, result.displayName)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, result.displayName))
            scaffoldState.snackbarHostState.showSnackbar(
                context.getString(R.string.toast_favorite_exported, result.path)
            )
        }.onFailure {
            runCatching {
                scaffoldState.snackbarHostState.showSnackbar(it.message.orEmpty())
            }
        }
    }

    // 「选择模式」和「当前有勾选」是两件事：
    // 平时是浏览模式（不画勾选框、左上角是返回箭头）；用户点了顶栏的「全选」才进选择模式。
    // 分开的好处是——在选择模式里把勾一个个全取消，勾选框不会跟着消失，
    // 否则用户会莫名其妙地看着勾选框一个个没了。
    var selectMode by remember { mutableStateOf(false) }
    val exitSelectMode = {
        selectMode = false
        viewModel.send(LocalFavoriteUiIntent.ClearSelection)
    }

    val confirmDelete = rememberDialogState()
    ConfirmDialog(
        dialogState = confirmDelete,
        onConfirm = {
            viewModel.send(LocalFavoriteUiIntent.DeleteSelected(selected.toList()))
        },
        onDismiss = { exitSelectMode() },
    ) {
        Text(text = stringResource(R.string.dialog_delete_favorite_confirm, selected.size))
    }

    val selectedIds = remember(selected) { selected.toList() }


    // 选择模式下按返回键先退回浏览模式，不要直接退出页面
    BackHandler(enabled = selectMode) {
        exitSelectMode()
    }
    val allSelected = remember(data, selected) {
        data.isNotEmpty() && data.all { it.threadId in selected }
    }

    MyScaffold(
        backgroundColor = Color.Transparent,
        scaffoldState = scaffoldState,
        topBar = {
            TitleCentredToolbar(
                title = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    ) {
                        Text(
                            text = stringResource(id = R.string.title_local_favorite),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.h6,
                            maxLines = 1
                        )
                        if (selectMode) {
                            Text(
                                text = stringResource(
                                    R.string.favorite_selected_count,
                                    selectedIds.size
                                ),
                                fontSize = 11.sp,
                                color = ExtendedTheme.colors.textSecondary,
                            )
                        }
                    }
                },
                navigationIcon = {
                    // 没选中时是普通的返回箭头；进入选择模式后才换成 X（= 退出选择）。
                    // 之前不区分，一直显示 X，看着像「这个页面只能关不能退」。
                    if (selectMode) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(
                                id = R.string.title_favorite_clear_select
                            ),
                            tint = ExtendedTheme.colors.text,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable { exitSelectMode() }
                                .padding(8.dp)
                        )
                    } else {
                        BackNavigationIcon(onBackPressed = { navigator.navigateUp() })
                    }
                },
                actions = {
                    // 用文字按钮而不是图标：之前三个「+」/勾/垃圾桶图标用户完全认不出
                    // 之前是两个文字按钮，把居中的标题挤到重叠了，收成图标按钮
                    ActionItem(
                        icon = if (allSelected) Icons.Rounded.Check else Icons.Rounded.Checklist,
                        contentDescription = stringResource(
                            id = if (allSelected) R.string.title_favorite_clear_select
                            else R.string.title_favorite_select_all
                        )
                    ) {
                        selectMode = true
                        if (allSelected) {
                            viewModel.send(LocalFavoriteUiIntent.ClearSelection)
                        } else {
                            viewModel.send(
                                LocalFavoriteUiIntent.SelectAll(data.map { it.threadId })
                            )
                        }
                    }
                    ActionItem(
                        icon = Icons.Rounded.FileUpload,
                        contentDescription = stringResource(id = R.string.title_favorite_import)
                    ) {
                        importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                    }
                    if (selectMode) {
                        ActionItem(
                            icon = Icons.Outlined.Delete,
                            contentDescription = stringResource(id = R.string.title_delete)
                        ) {
                            confirmDelete.show()
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (selectMode) {
                ExportBar(
                    exporting = exporting,
                    exportProgress = exportProgress,
                    onExport = { kind ->
                        viewModel.send(LocalFavoriteUiIntent.Export(kind, selectedIds))
                    },
                    onDelete = { confirmDelete.show() }
                )
            }
        },
    ) { contentPaddings ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPaddings)
        ) {
            // 自己画一个定高的搜索框：SearchBox 那个组件会把自己撑满，
            // 结果整个列表都被顶没了
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)
                    .height(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(ExtendedTheme.colors.chip)
                    .padding(horizontal = 12.dp),
            ) {
                androidx.compose.material.Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    tint = ExtendedTheme.colors.textSecondary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                androidx.compose.foundation.text.BasicTextField(
                    value = keyword,
                    onValueChange = { viewModel.send(LocalFavoriteUiIntent.Search(it)) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 14.sp,
                        color = ExtendedTheme.colors.text
                    ),
                    cursorBrush = SolidColor(ExtendedTheme.colors.primary),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        if (keyword.isEmpty()) {
                            Text(
                                text = stringResource(id = R.string.hint_search_favorite),
                                fontSize = 13.sp,
                                color = ExtendedTheme.colors.textSecondary,
                            )
                        }
                        inner()
                    }
                )
            }
            StateScreen(
                isEmpty = data.isEmpty(),
                isError = isError,
                isLoading = isLoading,
                modifier = Modifier.weight(1f),
                onReload = { viewModel.send(LocalFavoriteUiIntent.Refresh) },
                errorScreen = { error?.let { ErrorScreen(error = it.get()) } },
                emptyScreen = {
                    TipScreen(
                        title = { Text(text = stringResource(R.string.title_local_favorite)) },
                        message = {
                            Text(
                                text = if (keyword.isBlank())
                                    stringResource(R.string.title_favorite_local_tip)
                                else stringResource(R.string.hint_search_favorite),
                                color = ExtendedTheme.colors.textSecondary,
                            )
                        },
                    )
                }
            ) {
                val lazyListState = rememberLazyListState()
                MyLazyColumn(
                    state = lazyListState,
                    // 条目自己管内边距，外层不再加间距——
                    // Card 内部已经有 16.dp 上下，再加 6.dp 每条之间就 22.dp 了
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    items(items = data, key = { it.threadId }) { favorite ->
                        FavoriteItem(
                            favorite = favorite,
                            selected = favorite.threadId in selected,
                            selectionMode = selectMode,
                            keyword = keyword,
                            onClick = {
                                if (selectMode) {
                                    viewModel.send(
                                        LocalFavoriteUiIntent.ToggleSelect(favorite.threadId)
                                    )
                                } else {
                                    navigator.navigate(
                                        ThreadPageDestination(favorite.threadId)
                                    )
                                }
                            },
                            onCopyLink = {
                                TiebaUtil.copyText(
                                    context,
                                    favorite.url.ifBlank {
                                        "https://tieba.baidu.com/p/${favorite.threadId}"
                                    }
                                )
                            },
                            onDelete = {
                                viewModel.send(
                                    LocalFavoriteUiIntent.DeleteSelected(
                                        listOf(favorite.threadId)
                                    )
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FavoriteItem(
    favorite: Favorite,
    selected: Boolean,
    selectionMode: Boolean,
    keyword: String = "",
    onClick: () -> Unit,
    onCopyLink: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val menuState = rememberMenuState()
    // 封面自动刷新：先照常显示旧封面，后台确认它是占位图之后才换新地址。
    // 前段完全不阻塞，后段在后台跑。
    var refreshedCover by remember(favorite.threadId) { mutableStateOf<String?>(null) }
    LaunchedEffect(favorite.threadId, favorite.coverUrl) {
        val fresh = FavoriteCoverRefresher.refreshIfNeeded(favorite)
        if (fresh != null) {
            refreshedCover = fresh
            FavoriteRepository.updateCover(favorite.threadId, fresh)
        }
    }
    LongClickMenu(
        menuContent = {
            DropdownMenuItem(onClick = {
                onCopyLink()
                menuState.expanded = false
            }) {
                Text(text = stringResource(id = R.string.title_favorite_copy_link))
            }
            DropdownMenuItem(onClick = {
                onClick()
                menuState.expanded = false
            }) {
                Text(text = stringResource(id = R.string.title_favorite_toggle_select))
            }
            DropdownMenuItem(onClick = {
                onDelete()
                menuState.expanded = false
            }) {
                Text(text = stringResource(id = R.string.title_favorite_delete_one))
            }
        },
        menuState = menuState,
        onClick = onClick,
    ) {
        Card(
            onClick = onClick,
            // 只留左右，竖向由下面自己控制：要紧凑无缝，
            // 通用 Card 的 16.dp 上下留白在这个列表里太大
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            content = {
                Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // 封面（帖子首图 / 一楼图片）。先照常显示，不等待任何网络请求；
                    // 后台再判断它是不是已经变成 238x238 的占位图，是的话换成新的
                    val cover = favorite.coverUrl?.takeIf { it.isNotBlank() }
                    if (cover != null) {
                        NetworkImage(
                            imageUri = refreshedCover ?: cover,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(96.dp)
                                .clip(RoundedCornerShape(10.dp))
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                    }
                    // 没进入选择态就不画勾选框：平时列表是干净的浏览视图，
                    // 用户主动全选/勾选后才出现
                    if (selectionMode) {
                        SelectBox(
                            selected = selected,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 12.dp)
                    ) {
                        Text(
                            text = favorite.title.ifBlank { "(无标题)" },
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = ExtendedTheme.colors.text,
                        )
                        Text(
                            text = buildString {
                                if (favorite.forumName.isNotBlank()) append(favorite.forumName)
                                if (!favorite.authorName.isNullOrBlank()) {
                                    if (isNotEmpty()) append(" · ")
                                    append(favorite.authorName)
                                }
                                if (favorite.lastPage > 0) {
                                    if (isNotEmpty()) append(" · ")
                                    append("看到第 ${favorite.lastPage} 页")
                                }
                                if (isNotEmpty()) append(" · ")
                                append(getRelativeTimeString(context, favorite.timestamp))
                            },
                            fontSize = 11.sp,
                            color = ExtendedTheme.colors.textSecondary,
                        )
                        // 搜索时把命中楼层展开成一行一条（左边一条竖线 + 楼层/作者 + 片段），
                        // 像 VS Code 的文件树那样单层平铺，不套多层卡片。
                        // 不展开的话用户只能看到一坨正文，得自己在里面找词。
                        // 必须 remember：floorHits 要解析收藏时存的楼层 JSON，
                        // 不缓存的话每次重组都会重新解析一遍，主线程直接卡死/闪退。
                        val hits = remember(favorite.threadId, keyword) {
                            if (keyword.isBlank()) emptyList()
                            else FavoriteSearchHelper.floorHits(favorite, keyword)
                        }
                        if (hits.isNotEmpty()) {
                            hits.forEach { hit ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 6.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    // 竖线只留 2dp，缩进也只留 6dp：
                                    // 之前留 10dp 又没有缩进层级，看着空、
                                    // 正文被挤到屏幕中间，可用宽度白白少了一截
                                    Box(
                                        modifier = Modifier
                                            .width(2.dp)
                                            .height(IntrinsicSize.Min)
                                            .background(
                                                ExtendedTheme.colors.primary.copy(alpha = 0.45f)
                                            )
                                    )
                                    Column(modifier = Modifier.padding(start = 6.dp)) {
                                        Text(
                                            text = "#${hit.floor} · ${hit.author}",
                                            fontSize = 12.sp,
                                            color = ExtendedTheme.colors.primary
                                        )
                                        // 命中词用半透明底色标出来，不然用户得自己在
                                        // 一堆字里找哪几个字是搜的那几个
                                        // clickable = false：这个组件默认会装点击手势
                                        // （点链接跳页），而那条路需要 navigator。
                                        // 本列表项渲染在 MyLazyColumn 的 item 里，
                                        // 那个作用域没有 ProvideNavigator，
                                        // 不关掉的话一渲染就抛异常闪退。
                                        // 搜索片段是纯展示，不需要点。
                                        HighlightText(
                                            text = hit.snippet,
                                            fontSize = 15.sp,
                                            maxLines = 3,
                                            overflow = TextOverflow.Ellipsis,
                                            color = ExtendedTheme.colors.textSecondary,
                                            highlightKeywords = hit.matchedWords,
                                            highlightColor = ExtendedTheme.colors.primary,
                                            clickable = false,
                                        )
                                    }
                                }
                            }
                        } else {
                            val preview =
                                favorite.abstractText?.takeIf { it.isNotBlank() }
                                    ?: favorite.content?.takeIf { it.isNotBlank() }
                                        ?.replace('\n', ' ')
                            Text(
                                text = preview
                                    ?: stringResource(R.string.favorite_content_missing),
                                fontSize = 14.sp,
                                maxLines = 2,
                                color = if (preview != null) ExtendedTheme.colors.textSecondary
                                else ExtendedTheme.colors.textDisabled,
                            )
                        }
                    }
                }
            }
        )
    }
}

@Composable
private fun SelectBox(selected: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(20.dp)
            .clip(RoundedCornerShape(4.dp))
            .then(
                if (selected) Modifier.background(ExtendedTheme.colors.primary)
                else Modifier.border(
                    1.5.dp,
                    ExtendedTheme.colors.textDisabled,
                    RoundedCornerShape(4.dp)
                )
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = ExtendedTheme.colors.onAccent,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
private fun ExportBar(
    exporting: Boolean,
    exportProgress: String?,
    onExport: (FavoriteExportKind) -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 手机底部有系统导航栏（手势条/三键），不避让的话导出和删除会被压住
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (exporting) {
            Text(
                text = exportProgress ?: stringResource(R.string.title_favorite_export),
                fontSize = 12.sp,
                color = ExtendedTheme.colors.textSecondary,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            var menuExpanded by remember { mutableStateOf(false) }
            Box(modifier = Modifier.weight(1f)) {
                Button(
                    onClick = { menuExpanded = true },
                    enabled = !exporting,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Share,
                        contentDescription = null,
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .size(18.dp)
                    )
                    Text(text = stringResource(R.string.title_favorite_export_more))
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(onClick = {
                        menuExpanded = false
                        onExport(FavoriteExportKind.HTML)
                    }) {
                        Text(stringResource(R.string.title_favorite_export_html))
                    }
                    DropdownMenuItem(onClick = {
                        menuExpanded = false
                        onExport(FavoriteExportKind.LINKS)
                    }) {
                        Text(stringResource(R.string.title_favorite_export_links))
                    }
                    DropdownMenuItem(onClick = {
                        menuExpanded = false
                        onExport(FavoriteExportKind.BACKUP)
                    }) {
                        Text(stringResource(R.string.title_favorite_export_backup))
                    }
                }
            }
            TextButton(onClick = onDelete, enabled = !exporting) {
                Text(text = stringResource(R.string.title_delete))
            }
        }
    }
}

private fun writeTextFile(context: android.content.Context, content: String, baseName: String): File {
    val dir = File(
        context.getExternalFilesDir(null) ?: context.filesDir,
        "tieba_favorites"
    )
    if (!dir.exists()) dir.mkdirs()
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
    val extension = if (baseName.endsWith("backup")) "json" else "txt"
    return File(dir, "${baseName}_$stamp.$extension").apply { writeText(content, Charsets.UTF_8) }
}