package com.huanchengfly.tieba.post.ui.page.main.user

import android.graphics.Typeface
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.Icon
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.huanchengfly.tieba.post.utils.FavoriteExportDir
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.accompanist.placeholder.placeholder
import com.huanchengfly.tieba.post.R
import com.huanchengfly.tieba.post.arch.collectPartialAsState
import com.huanchengfly.tieba.post.arch.pageViewModel
import com.huanchengfly.tieba.post.models.database.Account
import com.huanchengfly.tieba.post.ui.common.theme.compose.ExtendedTheme
import com.huanchengfly.tieba.post.ui.common.theme.compose.pullRefreshIndicator
import com.huanchengfly.tieba.post.ui.page.LocalNavigator
import com.huanchengfly.tieba.post.ui.page.destinations.AboutPageDestination
import com.huanchengfly.tieba.post.ui.page.destinations.AppThemePageDestination
import com.huanchengfly.tieba.post.ui.page.destinations.HistoryPageDestination
import com.huanchengfly.tieba.post.ui.page.destinations.LocalFavoritePageDestination
import com.huanchengfly.tieba.post.ui.page.destinations.SettingsPageDestination
import com.huanchengfly.tieba.post.ui.page.destinations.ThreadStorePageDestination
import com.huanchengfly.tieba.post.ui.page.destinations.UserProfilePageDestination
import com.huanchengfly.tieba.post.ui.page.destinations.WebViewPageDestination
import com.huanchengfly.tieba.post.ui.widgets.compose.Avatar
import com.huanchengfly.tieba.post.ui.widgets.compose.ConfirmDialog
import com.huanchengfly.tieba.post.ui.widgets.compose.HorizontalDivider
import com.huanchengfly.tieba.post.ui.widgets.compose.ListMenuItem
import com.huanchengfly.tieba.post.ui.widgets.compose.Sizes
import com.huanchengfly.tieba.post.ui.widgets.compose.Switch
import com.huanchengfly.tieba.post.ui.widgets.compose.VerticalDivider
import com.huanchengfly.tieba.post.ui.widgets.compose.rememberDialogState
import com.huanchengfly.tieba.post.utils.CuidUtils
import com.huanchengfly.tieba.post.utils.StringUtil
import com.huanchengfly.tieba.post.utils.ThemeUtil
import com.huanchengfly.tieba.post.utils.appPreferences

@Composable
private fun StatCardPlaceholder(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.placeholder(visible = true, color = ExtendedTheme.colors.chip),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatCardItem(
            statNum = 0,
            statText = stringResource(id = R.string.text_stat_follow)
        )
        HorizontalDivider(color = Color(if (ExtendedTheme.colors.isNightMode) 0xFF808080 else 0xFFDEDEDE))
        StatCardItem(
            statNum = 0,
            statText = stringResource(id = R.string.text_stat_fans)
        )
        HorizontalDivider(color = Color(if (ExtendedTheme.colors.isNightMode) 0xFF808080 else 0xFFDEDEDE))
        StatCardItem(
            statNum = 0,
            statText = stringResource(id = R.string.title_stat_posts_num)
        )
    }
}

@Composable
private fun StatCard(
    account: Account,
    modifier: Modifier = Modifier
) {
    val postNum by animateIntAsState(targetValue = account.postNum?.toIntOrNull() ?: 0)
    val fansNum by animateIntAsState(targetValue = account.fansNum?.toIntOrNull() ?: 0)
    val concernNum by animateIntAsState(targetValue = account.concernNum?.toIntOrNull() ?: 0)
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatCardItem(
            statNum = concernNum,
            statText = stringResource(id = R.string.text_stat_follow)
        )
        HorizontalDivider(color = Color(if (ExtendedTheme.colors.isNightMode) 0xFF808080 else 0xFFDEDEDE))
        StatCardItem(
            statNum = fansNum,
            statText = stringResource(id = R.string.text_stat_fans)
        )
        HorizontalDivider(color = Color(if (ExtendedTheme.colors.isNightMode) 0xFF808080 else 0xFFDEDEDE))
        StatCardItem(
            statNum = postNum,
            statText = stringResource(id = R.string.title_stat_posts_num)
        )
    }
}

@Composable
private fun InfoCard(
    modifier: Modifier = Modifier,
    userName: String = "",
    userIntro: String = "",
    avatar: String? = null,
    isPlaceholder: Boolean = false
) {
    Row(
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .align(Alignment.Bottom)
        ) {
            Text(
                text = userName,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = ExtendedTheme.colors.text,
                modifier = Modifier
                    .fillMaxWidth()
                    .placeholder(visible = isPlaceholder, color = ExtendedTheme.colors.chip),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = userIntro,
                fontSize = 12.sp,
                color = ExtendedTheme.colors.textSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .placeholder(visible = isPlaceholder, color = ExtendedTheme.colors.chip),
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        if (avatar != null) {
            Avatar(
                data = avatar,
                size = Sizes.Large,
                contentDescription = stringResource(id = R.string.desc_user_avatar),
                modifier = Modifier
                    .align(Alignment.Bottom)
                    .placeholder(visible = isPlaceholder, color = ExtendedTheme.colors.chip),
            )
        }
    }
}

@Composable
private fun RowScope.StatCardItem(
    statNum: Int,
    statText: String
) {
    Column(
        modifier = Modifier.weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "$statNum",
            fontSize = 20.sp,
            fontFamily = FontFamily(Typeface.createFromAsset(LocalContext.current.assets, "bebas.ttf")),
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = statText,
            fontSize = 12.sp,
            color = ExtendedTheme.colors.textSecondary
        )
    }
}

@Composable
private fun LoginTipCard(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .align(Alignment.Bottom)
        ) {
            Text(
                text = stringResource(id = R.string.tip_login),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = ExtendedTheme.colors.text,
                modifier = Modifier
                    .fillMaxWidth(),
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Icon(
            imageVector = Icons.Rounded.AccountCircle,
            contentDescription = null,
            tint = ExtendedTheme.colors.onChip,
            modifier = Modifier
                .clip(CircleShape)
                .size(Sizes.Large)
                .background(color = ExtendedTheme.colors.chip)
                .padding(16.dp),
        )
    }
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun UserPage(
    viewModel: UserViewModel = pageViewModel<UserUiIntent, UserViewModel>(
        listOf(
            UserUiIntent.Refresh
        )
    )
) {
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val isLoading by viewModel.uiState.collectPartialAsState(
        prop1 = UserUiState::isLoading,
        initial = false
    )
    val account by viewModel.uiState.collectPartialAsState(
        prop1 = UserUiState::account,
        initial = null
    )

    // 收藏夹存档开关：已授权就锁死，不给关（关掉只会让用户以为收藏丢了）
    var favoriteDirOn by remember {
        mutableStateOf(
            context.appPreferences.exportDirUri.orEmpty().isNotBlank() ||
                FavoriteExportDir.legacyPublicDir() != null
        )
    }
    val favoriteDirLocked = remember {
        // 一进来就已经能用公共目录（老系统免授权 / 之前授权过），就不再让用户管
        context.appPreferences.exportDirUri.orEmpty().isNotBlank() ||
            FavoriteExportDir.legacyPublicDir() != null
    }
    fun prefDirPath(): String {
        val dir = FavoriteExportDir.legacyPublicDir()
        return dir?.absolutePath
            ?: context.appPreferences.exportDirUri.orEmpty()
                .replace(Regex("^content://[^/]+/tree/"), "")
                .replace(Regex("^document/[^%]+%3A"), "")
                .replace("primary:", "内部存储/")
    }
    fun enableFavoriteDir(path: String) {
        context.appPreferences.exportDirUri =
            if (FavoriteExportDir.legacyPublicDir() != null) "" else path
        favoriteDirOn = true
        // 私有目录里已有的导出文件顺手挪过去（静默）
        FavoriteExportDir.migrateToPublicDir(context)
    }

    // Android 11+ 公共目录受作用域存储限制，只能让用户在系统选择器里授权一次。
    // 只需要选到「下载」这一层，App 自己会建 TiebaLite 子目录，用户不用起名字。
    val favoriteDirLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri == null) return@rememberLauncherForActivityResult
        val granted = runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        if (granted.isFailure) return@rememberLauncherForActivityResult
        runCatching {
            val sub = FavoriteExportDir.resolveUserDir(context, treeUri)
            context.contentResolver.takePersistableUriPermission(
                sub,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            context.appPreferences.exportDirUri = sub.toString()
        }.onSuccess {
            favoriteDirOn = true
        }
    }

    val switchToNightDialogState = rememberDialogState()
    ConfirmDialog(
        dialogState = switchToNightDialogState,
        onConfirm = {},
        onCancel = {
            context.appPreferences.followSystemNight = false
            ThemeUtil.switchNightMode()
        },
        confirmText = stringResource(id = R.string.btn_keep_following),
        cancelText = stringResource(id = R.string.btn_close_following)
    ) {
        Text(text = stringResource(id = R.string.message_dialog_follow_system_night))
    }

    Scaffold(
        backgroundColor = Color.Transparent,
        modifier = Modifier
            .statusBarsPadding()
            .fillMaxSize()
    ) { contentPaddings ->
        val pullRefreshState = rememberPullRefreshState(
            refreshing = isLoading,
            onRefresh = { viewModel.send(UserUiIntent.Refresh) })
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPaddings)
                .pullRefresh(pullRefreshState),
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(state = rememberScrollState())
                    .fillMaxSize()
            ) {
                if (account != null) {
                    InfoCard(
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .clickable {
                                navigator.navigate(UserProfilePageDestination(account!!.uid.toLong()))
                            }
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        userName = account!!.nameShow ?: account!!.name,
                        userIntro = account!!.intro ?: stringResource(id = R.string.tip_no_intro),
                        avatar = StringUtil.getAvatarUrl(account!!.portrait),
                    )
                    StatCard(
                        account = account!!,
                        modifier = Modifier
                            .padding(16.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(color = ExtendedTheme.colors.chip)
                            .padding(vertical = 18.dp)
                    )
                } else if (isLoading) {
                    InfoCard(
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 16.dp)
                            .padding(top = 8.dp),
                        isPlaceholder = true,
                    )
                    StatCardPlaceholder(
                        modifier = Modifier
                            .padding(16.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(color = ExtendedTheme.colors.chip)
                            .padding(vertical = 18.dp)
                    )
                } else {
                    LoginTipCard(
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 16.dp)
                            .padding(top = 8.dp),
                    )
                }
                // 未登录时进本地收藏（免登录收藏），已登录保持原来的服务端收藏夹
                //
                // 右侧的开关控制「收藏夹实时存一份到公共目录的 TiebaLite 文件夹」。
                // 放在这一行而不是另开一页，是因为它就是「我的本地收藏」这件事的一个属性，
                // 单独放一页反而要用户来回跳。授权成功后开关锁死不可关——
                // 关掉等于让用户以为收藏会丢，实际上只是又回到私有目录。
                ListMenuItem(
                    icon = ImageVector.vectorResource(id = R.drawable.ic_favorite),
                    text = stringResource(id = R.string.title_my_collect),
                    onClick = {
                        if (account != null) {
                            navigator.navigate(ThreadStorePageDestination)
                        } else {
                            navigator.navigate(LocalFavoritePageDestination)
                        }
                    }
                ) {
                    if (favoriteDirOn) {
                        // 授权后不再显示开关——开关摆在行末又小又滑，
                        // 用户想点「我的本地收藏」很容易误触到它，然后弹出一堆系统选择器。
                        // 改成一条不可点的状态：点不到，也不给人「这里能点」的暗示。
                        Text(
                            text = stringResource(R.string.my_favorite_dir_on),
                            fontSize = 12.sp,
                            color = ExtendedTheme.colors.primary,
                            maxLines = 1
                        )
                    } else {
                        Switch(
                            checked = false,
                            onCheckedChange = {
                                // Android 10 及以下能直接写公共目录，不用打扰用户
                                if (FavoriteExportDir.legacyPublicDir() != null) {
                                    enableFavoriteDir(prefDirPath())
                                } else {
                                    favoriteDirLauncher.launch(null)
                                }
                            },
                        )
                    }
                }
                ListMenuItem(
                    icon = ImageVector.vectorResource(id = R.drawable.ic_outline_watch_later_24),
                    text = stringResource(id = R.string.title_history),
                    onClick = {
                        navigator.navigate(HistoryPageDestination)
                    }
                )
                ListMenuItem(
                    icon = ImageVector.vectorResource(id = R.drawable.ic_brush_24),
                    text = stringResource(id = R.string.title_theme),
                    onClick = {
                        navigator.navigate(AppThemePageDestination)
                    }
                ) {
                    Text(
                        text = stringResource(id = R.string.my_info_night),
                        color = ExtendedTheme.colors.textSecondary,
                        fontSize = 12.sp,
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Switch(
                        checked = ThemeUtil.isNightMode(ThemeUtil.themeState.value),
                        onCheckedChange = {
                            if (context.appPreferences.followSystemNight) {
                                switchToNightDialogState.show()
                            } else {
                                ThemeUtil.switchNightMode()
                            }
                        }
                    )
                }
                if (account != null) {
                    ListMenuItem(
                        icon = ImageVector.vectorResource(id = R.drawable.ic_help_outline_black_24),
                        text = stringResource(id = R.string.my_info_service_center),
                        onClick = {
                            navigator.navigate(
                                WebViewPageDestination(
                                    initialUrl = "https://tieba.baidu.com/mo/q/hybrid-main-service/uegServiceCenter?cuid=${CuidUtils.getNewCuid()}&cuid_galaxy2=${CuidUtils.getNewCuid()}&cuid_gid=&timestamp=${System.currentTimeMillis()}&_client_version=12.52.1.0&nohead=1"
                                )
                            )
                        },
                    )
                }
                VerticalDivider(
                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 16.dp)
                )
                ListMenuItem(
                    icon = ImageVector.vectorResource(id = R.drawable.ic_settings_24),
                    text = stringResource(id = R.string.my_info_settings),
                    onClick = { navigator.navigate(SettingsPageDestination) },
                )
                ListMenuItem(
                    icon = ImageVector.vectorResource(id = R.drawable.ic_info_black_24),
                    text = stringResource(id = R.string.my_info_about),
                    onClick = { navigator.navigate(AboutPageDestination) },
                )
            }

            PullRefreshIndicator(
                refreshing = isLoading,
                state = pullRefreshState,
                modifier = Modifier.align(Alignment.TopCenter),
                backgroundColor = ExtendedTheme.colors.pullRefreshIndicator,
                contentColor = ExtendedTheme.colors.primary,
            )
        }
    }
}
