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
| 吧内热门 tab | 首次进入才加载，从帖子返回**不会自动刷新**（列表顶部有手动刷新按钮，下拉也能刷）。小程序 `tiebaswan.baidu.com/c/f/frs/page` 免登录接口，`tab_id=2` 取热门；参数按字典序拼串 + 固定 SECRET 做 MD5 签名。放在「精华」右边，卡片复用原 `FeedCard`。数据来源就是百度贴吧网页版：[贴吧移动版](https://mbd.baidu.com/ma/s/pS8jRhi9) 和 [小程序的吧内页](https://byokpg.smartapps.baidu.com/pages/frs/frs?aladdin_src_id=61952&kw=%E6%96%B9%E4%BE%BF%E9%9D%A2&_swebfr=26&_swebFromHost=bdhonorbrowser) | `api/swan/SwanTiebaApi.kt`、`ui/page/forum/hotthread/`、`ForumPage` |
| 免登录收藏 | 爱心即本地收藏，**图标不变**，已收藏显示实心；点击有toast 提示。三个位置：
 ① 列表卡片右下角；② **帖子详情页主楼底栏（三点省略号左边那个）**；
 ③ 搜索结果页。楼层内部的爱心仍然是原来的楼层点赞 | `FeedCard.kt` 的 `ThreadAgreeBtn`、`ThreadPage.kt` 的 `BottomBarAgreeBtn` |
| 收藏页封面 | 收藏时记下帖子首图 / 主楼首图，收藏列表里显示 76dp 封面 | `ThreadFavoriteInfo.coverUrl`、`Favorite.coverUrl` |
| 收藏存哪儿 | LitePal 新表 `Favorite`（`litepal.xml` 37→38），只落本机，不上传 | `models/database/Favorite.kt`、`repository/FavoriteRepository.kt` |
| 浏览缓冲区 | 详情页翻过的页/正文/图片地址记在**纯内存** LRU（2 小时 TTL，上划清进程即失效）；不发额外请求、不卡 UI。收藏瞬间把快照一起落库，所以收藏里能搜正文、导出才有内容 | `utils/ThreadViewCache.kt`、`ThreadPage` 的 `LaunchedEffect` |
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

#### 排查记录：这个结论错在哪

留档给以后改这块的人：

> 时间和热门是同一个接口，一次请求就带回来了，不用额外调。
>
> 就是 `GET https://tiebaswan.baidu.com/c/f/frs/page?kw=<吧名>&tab_id=2&...`，
> 时间戳在返回的 `page_data.feed_list[].feed.components[]` 里，
> `component == "feed_head"` 那个对象的 `extra_data` 数组中：
>
> ```json
> "extra_data": [
>   { "type": 1, "text": { "text": "1790815105", "type": 3 } },
>   { "type": 1, "text": { "text": "戴森球计划吧吧主", "type": 0 } }
> ]
> ```
>
> `text.type == 3` 那项的 `text.text` 就是秒级 Unix 时间戳。同一数组里混着
> 吧主徽章之类的纯文本（`type` 为 0），必须按 type 筛。
>
> 这个坑值得记一下：**字段名就叫 `text`，时间语义藏在兄弟字段 `type` 上。**
> 连续两次断言「接口不返回发帖时间」都是错的，原因是用了
> 「递归遍历找 key 含 time/date/create」的扫法——这种写法对
> 字段名和值语义不一致的数据天然无效。
>
> 是靠「直接打开网页就能看到『回复于 xx 前』」这个观察推翻的，然后
> Playwright 扒小程序页面脚本，在 `uni-frs.swan.js` 里搜到「回复于」，
> 顺着模板变量追到 `feed_head.extra_data` 才定位到。
>
> **教训：下「某字段不存在」的结论之前，先 dump 一条完整样本出来逐字段看，
> 别指望正则扫字段名。**


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

### 为什么没有「翻页找 8 月的帖子」

试过两条路，都走不通，结论记在这儿免得以后重做：

**① 小程序 `frs/page` 的 `pn` 不是时间序，是热度榜页码。** 实测「方便面」吧：

```
看贴 pn=1    09-20 ~ 09-20
     pn=5    09-19 ~ 09-20
     pn=10   09-19 ~ 10-09   ← 逆序
     pn=30   10-08 ~ 10-09   ← 比 pn=10 还新
     pn=100  09-30 ~ 10-08

热门 pn=1    09-28 ~ 10-08
     pn=10   08-03 ~ 10-09
     pn=30   10-08 ~ 10-08   ← 又变新了
```

`f(pn)` 不是单调函数，所以「后台轮询每日发帖量 → 推断页码 → 建页码↔日期表 →
二分定位」这套**前提不成立**，二分没有意义。

**② 贴吧网页版 `tieba.baidu.com/f?kw=<吧名>&pn=450` 的 `pn` 确实是时间序**
（这也是贴吧的老分页规则），但：

- 直接 curl 会403 /撞「百度安全验证」，得先访问首页拿 cookie
- 用真实浏览器（Playwright）能打开，标题是「XX吧-百度贴吧」，但**页面里一个帖子
  链接都没有**——现在是 Vue SPA，帖子列表靠 JS 异步拉
- 那些 `2026-10-20` 全是头像图片 URL 里的 `tbpicau=` CDN 参数，不是发帖时间

也就是说网页版内部调的那套接口同样需要鉴权，绕回「必须登录」。

**③ 但「翻100 页」确实有效——这一点我一开始也判断错了。**

只抽样 7 个页（1/2/5/10/30/60/100）会得出"日期没有趋势"的结论，因为那几个页
恰好都落在抖动区。把前 100 页全扫完，「方便面」吧的真实趋势是：

```
页  1   2026-10-08 ~ 2026-10-09
页 25   2026-09-23 ~ 2026-09-29
页 50   2026-09-22 ~ 2026-09-23
页100   2026-09-25 ~ 2026-09-29
最老的一条：2024-11-21
```

**页码越大整体越老，100 页约推进 14 天**，而且能一路翻到 2024 年。
前 30 页里有 13 次局部抖动，所以不是严格单调，但抖幅远小于页码跨度——
**二分/线性定位是可用的，只是不能当成精确值**。

**④ 榜会漂移，所以不能只记页码——用帖子 id 当游标。**

热门榜是**会重排**的：今天「第 50 页」这周再翻，可能已经不是同一批帖子了。
只记绝对页码，一周后回来就接不上。

解法和 GitHub commits 的 `?after=<sha>+34` 一样：**不锚绝对位置，锚具体对象。**

- 每次翻页把`maxPage` + 时间范围 + **一个锚点帖（`anchorTid` + `anchorTime`）**
  记进 `ForumBrowse` 表。锚点取这批里最老的那条
- 下次跳页时先看锚点帖还在不在目标页里：
  - **在** → 榜面没怎么动，这个页码还是那个位置
  - **不在** → 它被新帖挤到后面去了。用锚点的发帖时间跟这一页最新的帖子比，
    算出大概往后漂了几页（经验值 4 页/天），在列表顶部显示
    「热门榜已重排，往后翻约 N 页能接上之前的进度」
- 热门列表顶部常驻「· 上次看到 09-25」，点它跳回上次那页；旁边有「继续往后翻」
  直接跳 `maxPage + 1`

**为什么不后台二分：**
① 日期非严格单调（前 30 页有 13 次抖动），二分容易落错区间；
② 更根本的是**映射关系本身会随时间失效**，后台建出来的页码↔日期表过几天就是错的。
锚点方案是零额外请求的（跳页本来就发一次请求，只是顺便校验一下），
二分/后台轮询都要额外请求，而且省不掉。

**结论：免登录 + 任意精确翻页做不到**（要做到得登录，走
`TiebaApi.frsPage(forumName, page, ...)` 那个登录态 pb 接口）；
但免登录 + 顺序往后翻是可行的，配合「记住上次位置」体验就够用了。

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