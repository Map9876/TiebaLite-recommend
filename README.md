# <p align="center">Tieba Lite</p>
<p align="center">
    <a href="https://github.com/HuanCheng65/TiebaLite/actions/workflows/build-apk.yml">
        <img alt="Build APK" src="https://github.com/HuanCheng65/TiebaLite/actions/workflows/build-apk.yml/badge.svg">
    </a>
    <a href="https://github.com/HuanCheng65/TiebaLite/actions/workflows/build.yml">
        <img alt="Build Status" src="https://github.com/HuanCheng65/TiebaLite/actions/workflows/build.yml/badge.svg?branch=4.0-dev">
    </a>
    <a href="https://t.me/tblite_discuss">
        <img alt="Status" src="https://img.shields.io/badge/-Telegram-blue?logo=telegram&style=flat">
    </a>
</p>

贴吧 Lite 是一个**非官方**的贴吧客户端。

## 说明

**本软件及源码仅供学习交流使用，严禁用于商业用途。**

## 本分支新增功能

在原版 UI 上叠加的一组「免登录也能用」的功能，界面和交互沿用原有组件，不改原有视觉。

| 需求 | 实现 | 位置 |
| --- | --- | --- |
| 吧内热门帖子（放到「精华」右边） | 百度贴吧小程序 `tiebaswan.baidu.com/c/f/frs/page` 免登录接口，`tab_id=2`取热门；参数按字典序拼串 + 固定 SECRET 做 MD5 签名 | `api/swan/SwanTiebaApi.kt`、`ui/page/forum/hotthread/`、`ForumPage` (pagerState 2→3) |
| 免登录收藏（爱心） | 帖子卡片右下角爱心在**未登录**时改为「本地收藏」，**图标不变**，已收藏显示实心高亮；登录后仍是原来的点赞 | `ui/widgets/compose/FeedCard.kt` 的 `ThreadAgreeBtn` |
| 收藏存哪儿 | LitePal 新表 `Favorite`（`litepal.xml` version 37→38），只落本机，不上传 | `models/database/Favorite.kt`、`repository/FavoriteRepository.kt` |
| 浏览缓冲区 | 详情页翻过的页/正文/图片地址记在**纯内存** LRU 里，10 分钟过期、上划清理进程即清空；不发额外请求、不卡UI；收藏瞬间把快照一起落库，所以收藏里能搜正文 | `utils/ThreadViewCache.kt`、`ThreadPage` 里的 `LaunchedEffect` |
| 收藏页 | 列表 + 关键词搜索（标题/吧名/作者/摘要/**正文**）+ 多选/全选 + 长按复制链接或删除 | `ui/page/favorite/` |
| 导出 | ① 单文件 HTML：左侧全部帖子标题目录 + 站内搜索框，图片抓下来转 base64 内嵌，离线可看；② 链接+标题 txt；③ 可再导入的 json 备份 | `utils/FavoriteHtmlExporter.kt` |
| 导入 | 系统文件选择器选 json，按 threadId 去重合并 | `FavoriteRepository.importPayload` |
| 免登录看吧历史 | 历史本来就存在本地，但首页推荐列表为空时会把历史一起挡住；改成「推荐为空且历史也为空才显示空态」，并且推荐接口失败不再拖垮整个首页 | `HomePage.kt`、`HomeViewModel.kt` |

入口：「我」页的**我的收藏**——已登录进原来的服务端收藏夹，未登录进本地收藏页（深链 `tblite://favorite_local`）。

## 两种包

每次构建出两个包，可与官方版同时安装：

| 包 | applicationId | 应用名 |
| --- | --- | --- |
| 正式版 `official` | `com.huanchengfly.tieba.post` | 贴吧 Lite |
| 共存版 `coexist` | `com.huanchengfly.tieba.post.coexist` | 贴吧 Lite 共存 |

靠 AGP productFlavor 实现：只改 `applicationIdSuffix` 和应用名，`namespace` 不动（它决定 `BuildConfig`/`R` 的包路径，源码 import 依赖它）。FileProvider 的 authority 改成 `${applicationId}.share.FileProvider`，否则两个 App 会抢同一个 authority。

## 自动构建

`.github/workflows/build-apk.yml` 在 push / PR / 手动触发时跑：

1. JDK 17 + Android SDK + Gradle 缓存
2. `./gradlew assembleRelease` —— 两个 flavor 一起出（仓库默认没有 `keystore.properties`，会自动退回debug 签名，保证任何 fork 都能直接出包）
3. APK 收集到 `dist/` 上传成 Actions Artifact；打 `v*` tag 时额外挂到 Release，Release 说明用 `.github/RELEASE_NOTES.md`

想让产物用你自己的正式签名，在仓库 **Settings → Secrets and variables → Actions** 里加：

| Secret | 说明 |
| --- | --- |
| `KEYSTORE_BASE64` | keystore 文件的 base64（`base64 -w0 release.keystore`） |
| `KEYSTORE_PASSWORD` | keystore 密码 |
| `KEYSTORE_ALIAS` | 密钥别名 |
| `KEYSTORE_KEY_PASSWORD` | 密钥密码 |

## 友情链接

+ [Starry-OvO/aiotieba: Asynchronous I/O Client for Baidu Tieba](https://github.com/Starry-OvO/aiotieba)
+ [n0099/tbclient.protobuf: 百度贴吧客户端 Protocol Buffers 定义文件合集](https://github.com/n0099/tbclient.protobuf)