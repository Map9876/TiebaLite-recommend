# <p align="center">Tieba Lite</p>
<p align="center">
    <a href="https://github.com/Map9876/TiebaLite-recommend/actions/workflows/build-apk.yml">
        <img alt="Build APK" src="https://github.com/Map9876/TiebaLite-recommend/actions/workflows/build-apk.yml/badge.svg">
    </a>
    <a href="https://github.com/HuanCheng65/TiebaLite">
        <img alt="基于 HuanCheng65/TiebaLite" src="https://img.shields.io/badge/基于-HuanCheng65%2FTiebaLite-blue">
    </a>
</p>

贴吧 Lite 是一个**非官方**的贴吧客户端。本仓库是 [HuanCheng65/TiebaLite](https://github.com/HuanCheng65/TiebaLite) 的分支，完整保留了它的提交历史（865 个 commit），改动集中在下面这几件事。

## 和其他维护版的区别

社区里还有 [0ranko0P/TiebaLite](https://github.com/0ranko0P/TiebaLite)、[zzc10086/TiebaLite](https://github.com/zzc10086/TiebaLite) 等基于上游继续维护的分支，它们和上游一样保留了**底栏的「动态」跨吧随机推荐流**（以及精华/热门的多级分类标签）。

**本分支默认不显示「动态」栏**（`hideExplore` 默认改成 `true`，设置里可以再打开）。理由是跨吧随机推荐很容易把时间吃掉，而且它跟「想好好看某个吧」这件事是冲突的。

我们换来的东西是**吧内热门**：直接接百度贴吧小程序的免登录接口，取真正属于这个吧的热门帖，而不是全站随机。

<p align="center">
    <img src="docs/images/home-history-forums.jpg" alt="首页最近逛的吧" width="320">
    <img src="docs/images/forum-hot-tab.jpg" alt="吧内热门 tab" width="320">
</p>

## 新增功能

| 需求 | 实现 | 位置 |
| --- | --- | --- |
| 吧内热门 tab | 小程序 `tiebaswan.baidu.com/c/f/frs/page` 免登录接口，`tab_id=2` 取热门；参数按字典序拼串 + 固定 SECRET 做 MD5 签名。放在「精华」右边，卡片复用原 `FeedCard`。数据来源就是百度贴吧网页版：[贴吧移动版](https://mbd.baidu.com/ma/s/pS8jRhi9) 和 [小程序的吧内页](https://byokpg.smartapps.baidu.com/pages/frs/frs?aladdin_src_id=61952&kw=%E6%96%B9%E4%BE%BF%E9%9D%A2&_swebfr=26&_swebFromHost=bdhonorbrowser) | `api/swan/SwanTiebaApi.kt`、`ui/page/forum/hotthread/`、`ForumPage` |
| 免登录收藏 | 帖子卡片右下角的爱心就是本地收藏，**图标不变**，已收藏显示实心；点击有 toast 提示 | `ui/widgets/compose/FeedCard.kt` 的 `ThreadAgreeBtn` |
| 收藏存哪儿 | LitePal 新表 `Favorite`（`litepal.xml` 37→38），只落本机，不上传 | `models/database/Favorite.kt`、`repository/FavoriteRepository.kt` |
| 浏览缓冲区 | 详情页翻过的页/正文/图片地址记在**纯内存** LRU（10 分钟 TTL，上划清进程即失效）；不发额外请求、不卡 UI。收藏瞬间把快照一起落库，所以收藏里能搜正文、导出才有内容 | `utils/ThreadViewCache.kt`、`ThreadPage` 的 `LaunchedEffect` |
| 收藏页 | 关键词搜索（标题/吧名/作者/摘要/**正文**）、多选全选、长按复制链接或删除 | `ui/page/favorite/` |
| 导出 | ① 单文件 HTML：左侧全部帖子标题目录 + 站内搜索框，图片抓下来转 base64 内嵌，离线可看；② 链接+标题 txt；③ 可再导入的 json 备份 | `utils/FavoriteHtmlExporter.kt` |
| 导入 | 系统文件选择器选 json，按 threadId 去重合并 | `FavoriteRepository.importPayload` |
| 免登录看吧历史 | 首页「最近逛的吧」不再因为未登录而整块消失；推荐接口失败不再拖垮整个首页 | `HomePage.kt`、`HomeViewModel.kt` |
| 首页历史样式 | 图标放大到 52dp、图上文下、一行 4 个自动换行，逛得再多也不用左右滑 | `HomePage.kt` |

入口：「我」页的**我的收藏** —— 未登录进本地收藏页（深链 `tblite://favorite_local`），已登录进原来的服务端收藏夹。

### 数据来源

吧内热门不是官方 App 那个pb 接口，而是抓的**百度贴吧网页版/小程序**的免登录接口：

- 网页入口：[mbd.baidu.com/ma/s/pS8jRhi9](https://mbd.baidu.com/ma/s/pS8jRhi9)
- 小程序入口：[byokpg.smartapps.baidu.com/pages/frs/frs?kw=方便面](https://byokpg.smartapps.baidu.com/pages/frs/frs?aladdin_src_id=61952&kw=%E6%96%B9%E4%BE%BF%E9%9D%A2&_swebfr=26&_swebFromHost=bdhonorbrowser)
- 实际请求：`GET https://tiebaswan.baidu.com/c/f/frs/page?kw=<吧名>&tab_id=2&...`，鉴权只有一个 `sign`（参数按key 字典序拼串+ 固定 SECRET 做 MD5），不需要 BDUSS

### 发帖时间是从哪个接口来的？

**和热门是同一个接口，一次请求，不用额外调。** 就是
`GET https://tiebaswan.baidu.com/c/f/frs/page?kw=<吧名>&tab_id=2&...`，
时间戳就在 `page_data.feed_list[].feed.components[]` 里 `component == "feed_head"`
的那个对象的 `extra_data` 数组中：

```json
{
  "component": "feed_head",
  "feed_head": {
    "image_data": { "img_url": "http://tb.himg.baidu.com/...", "...": "..." },
    "main_data": [ { "type": 1, "text": { "text": "资深体育泡面迷", "type": 0 } } ],
    "extra_data": [
      { "type": 1, "text": { "text": "1790815105", "type": 3 } },
      { "type": 1, "text": { "text": "戴森球计划吧吧主", "type": 0 } }
    ]
  }
}
```

规则：

- `extra_data` 里`text.type == 3` 的那一项，`text.text` 就是**秒级 Unix 时间戳**
- 同一数组里还混着吧主徽章之类的纯文本（`text.type` 为 0 或其他值），必须按 `type == 3` 筛
- **这个坑很容易踩**：字段名就叫 `text`，时间语义藏在**兄弟字段** `type` 上。
  用「递归遍历找 key 里含 time/date/create」的写法是扫不到的，
  最初就是这么漏掉的（连续两次断言"接口不返回发帖时间"是错的）

对应实现 `SwanTiebaApi.parseCreateTime()`。

### 怎么抓包 / 怎么验证接口

网页入口（都会302 到小程序 frs 页）：

- [贴吧移动版mbd.baidu.com/ma/s/pS8jRhi9](https://mbd.baidu.com/ma/s/pS8jRhi9)
- [小程序吧内页 byokpg.smartapps.baidu.com/pages/frs/frs](https://byokpg.smartapps.baidu.com/pages/frs/frs?aladdin_src_id=61952&kw=%E6%96%B9%E4%BE%BF%E9%9D%A2&_swebfr=26&_swebFromHost=bdhonorbrowser)

小程序页面把业务请求统一包在 `https://byokpg.smartapps.baidu.com/webmapp/api/v1/proxy?u=<编码后的目标>` 里，
`u` 是混淆过的，从 URL 看不出真实端点，而且 headless 浏览器会触发百度验证码导致帖子列表不渲染。
**所以别从页面拦包，直接请求真实接口更快**：

```bash
# 签名：参数按 key 字典序拼成 "k1=v1k2=v2"（无分隔符），末尾加 SECRET，MD5 32 位小写
SECRET=0039d79dc3cc2075129745a30237a3c4
python3 - <<'PY'
import hashlib, time, urllib.parse, urllib.request, json
SECRET = "0039d79dc3cc2075129745a30237a3c4"
CUID = "A63C81D31E855BD294D9E3F56305E925|VXU2U2B2I"
p = {"subapp_type": "smallapp", "tab_id": "2", "tab_type": "all", "fr": "smallapp",
     "kw": "方便面", "pn": "1", "rn": "20", "r": "2", "is_newfrs": "1", "is_newfeed": "1",
     "timestamp": str(int(time.time() * 1000)), "source_platform": "baidu",
     "obj_param2": "flyflow", "browser": "flyflow", "_client_type": "2",
     "_client_version": "12.77.0", "call_from": "baidu", "cuid": CUID, "swan_cuid": CUID}
p["sign"] = hashlib.md5(("".join(f"{k}={p[k]}" for k in sorted(p)) + SECRET).encode()).hexdigest()
req = urllib.request.Request(
    "https://tiebaswan.baidu.com/c/f/frs/page?" + urllib.parse.urlencode(p),
    headers={"User-Agent": "Mozilla/5.0"})
print(json.load(urllib.request.urlopen(req, timeout=25))["error_code"])   # 0 即成功
PY
```

`tab_id` 取值：`0` 看贴（全部）、`1` 看贴、`2` 热门、`3` 成员、`4` 群组。

需要看页面自己怎么渲染某个字段时，再去扒小程序包里的页面脚本，比拦包靠谱：

```
https://spwebbj.cdn.bcebos.com/web0/20260922/flFqXclepWs7RdugAszy9eERL7G5dS0I/v33731_1790040654//pages/uni-frs/uni-frs.swan.js
```

（版本号会变，从页面资源列表里拿。「回复于」这个词就在这个文件里，
顺着模板变量就能追到 `feed_head.extra_data`。）

### 关于热门接口的已知局限

- 每次刷新热门榜都会重排，而且会出现只有一两条评论的帖子，因为它按的是吧内热度而不是回复数
- 想要「按时间或回复数排序的精华」，还是得用需要登录的 pb 接口

## 两种包

每次构建出两个包，可与官方版同时安装：

| 包 | applicationId | 应用名 |
| --- | --- | --- |
| 正式版 `official` | `com.huanchengfly.tieba.post` | 贴吧 Lite |
| 共存版 `coexist` | `com.huanchengfly.tieba.post.coexist` | 贴吧 Lite 共存 |

靠 AGP productFlavor 实现：只改 `applicationIdSuffix` 和应用名，`namespace` 不动（它决定 `BuildConfig`/`R` 的包路径）。FileProvider 和 androidx-startup 的 authority 都改成 `${applicationId}.xxx`，否则两个 App 会抢同一个 authority、manifest merger 会直接冲突。

## 直接下载

每次 Release 都出两个包，文件名固定，下面这两个链接永远指向最新版本：

| 版本 | 包名 | 下载 |
| --- | --- | --- |
| 正式版 | `com.huanchengfly.tieba.post` | [tieba-lite-official.apk](https://github.com/Map9876/TiebaLite-recommend/releases/latest/download/tieba-lite-official.apk) |
| 共存版 | `com.huanchengfly.tieba.post.coexist` | [tieba-lite-coexist.apk](https://github.com/Map9876/TiebaLite-recommend/releases/latest/download/tieba-lite-coexist.apk) |

## 自动构建

`.github/workflows/build-apk.yml` 在 push / PR / 手动触发时跑：

1. JDK 17 + runner 自带 Android SDK + Gradle 缓存
2. `./gradlew assembleRelease` —— 两个 flavor 一起出（仓库默认没有 `keystore.properties`，会自动退回 debug 签名，保证任何 fork 都能直接出包）
3. APK 收集到 `dist/` 上传成 Actions Artifact；打 `v*` tag 时额外挂到 Release，说明用 `.github/RELEASE_NOTES.md`

想让产物用你自己的正式签名，在仓库 **Settings → Secrets and variables → Actions** 里加：

| Secret | 说明 |
| --- | --- |
| `KEYSTORE_BASE64` | keystore 文件的 base64（`base64 -w0 release.keystore`） |
| `KEYSTORE_PASSWORD` | keystore 密码 |
| `KEYSTORE_ALIAS` | 密钥别名 |
| `KEYSTORE_KEY_PASSWORD` | 密钥密码 |

## 说明

**本软件及源码仅供学习交流使用，严禁用于商业用途。**

## 友情链接

+ [Starry-OvO/aiotieba: Asynchronous I/O Client for Baidu Tieba](https://github.com/Starry-OvO/aiotieba)
+ [n0099/tbclient.protobuf: 百度贴吧客户端 Protocol Buffers 定义文件合集](https://github.com/n0099/tbclient.protobuf)