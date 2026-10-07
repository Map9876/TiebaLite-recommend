package com.huanchengfly.tieba.post.utils

import android.content.Context
import android.util.Base64
import com.huanchengfly.tieba.post.models.database.Favorite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 把收藏导出成一个自包含的 HTML：左侧是全部帖子标题目录（带站内搜索），
 * 正文里的图片抓下来转成 base64 内嵌，所以文件可以离线看、可以整份发给别人。
 */
object FavoriteHtmlExporter {

    private const val MAX_IMAGES_PER_POST = 12
    private const val MAX_IMAGE_BYTES = 3 * 1024 * 1024
    private val CONCURRENCY = 4

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    suspend fun build(
        favorites: List<Favorite>,
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ): String = withContext(Dispatchers.IO) {
        val totalImages = favorites.sumOf { imageCount(it) }
        val doneImages = AtomicInteger(0)
        val semaphore = Semaphore(CONCURRENCY)

        val sections = coroutineScope {
            favorites.map { favorite ->
                async {
                    val urls = favorite.imageUrls
                        ?.lineSequence()
                        ?.filter { it.isNotBlank() }
                        ?.take(MAX_IMAGES_PER_POST)
                        ?.toList()
                        .orEmpty()
                    val dataUri = if (urls.isEmpty()) emptyList() else coroutineScope {
                        urls.map { url ->
                            async {
                                semaphore.withPermit {
                                    fetchAsDataUri(url).also {
                                        onProgress(doneImages.incrementAndGet(), totalImages)
                                    }
                                }
                            }
                        }.awaitAll().filterNotNull()
                    }

                    val sb = StringBuilder()
                    sb.append("<section class=\"post\" id=\"t").append(favorite.threadId)
                        .append("\" data-key=\"").append(searchKey(favorite)).append("\">\n")
                    sb.append("<h2>").append(esc(favorite.title)).append("</h2>\n")
                    sb.append("<div class=\"meta\">")
                    if (favorite.forumName.isNotBlank()) {
                        sb.append("吧：").append(esc(favorite.forumName))
                    }
                    if (!favorite.authorName.isNullOrBlank()) {
                        sb.append(" · 作者：").append(esc(favorite.authorName))
                    }
                    sb.append(" · 收藏于 ").append(esc(formatTime(favorite.timestamp)))
                    if (favorite.lastPage > 0) {
                        sb.append(" · 看到第 ").append(favorite.lastPage).append(" 页")
                    }
                    sb.append("</div>\n")
                    if (!favorite.abstractText.isNullOrBlank()) {
                        sb.append("<div class=\"abs\">").append(esc(favorite.abstractText))
                            .append("</div>\n")
                    }
                    if (dataUri.isNotEmpty()) {
                        sb.append("<div class=\"pics\">\n")
                        dataUri.forEach {
                            sb.append("<img loading=\"lazy\" src=\"").append(it).append("\">\n")
                        }
                        sb.append("</div>\n")
                    }
                    if (!favorite.content.isNullOrBlank()) {
                        sb.append("<pre class=\"content\">")
                            .append(esc(favorite.content!!))
                            .append("</pre>\n")
                    }
                    sb.append("<a class=\"src\" href=\"").append(esc(favorite.url))
                        .append("\">在贴吧打开 →</a>\n")
                    sb.append("</section>\n")
                    sb.toString()
                }
            }.awaitAll()
        }

        val nav = buildString {
            append("<ol class=\"nav\">\n")
            favorites.forEach {
                append("<li><a href=\"#t").append(it.threadId).append("\">")
                    .append(esc(it.title)).append("</a></li>\n")
            }
            append("</ol>\n")
        }

        page(
            title = "贴吧收藏导出",
            count = favorites.size,
            nav = nav,
            sections = sections.joinToString("")
        )
    }

    fun writeToFile(context: Context, html: String, baseName: String = "tieba_favorites"): File {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "tieba_favorites")
        if (!dir.exists()) dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val file = File(dir, "${baseName}_$stamp.html")
        file.writeText(html, Charsets.UTF_8)
        return file
    }

    // ---------------- internals ----------------

    private fun imageCount(favorite: Favorite): Int =
        favorite.imageUrls?.lineSequence()?.filter { it.isNotBlank() }?.count() ?: 0

    private fun fetchAsDataUri(url: String): String? = runCatching {
        val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body ?: return null
            val bytes = body.bytes()
            if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) return null
            val mime = body.contentType()?.type()?.let { "${it}/${body.contentType()?.subtype()}" }
                ?: "image/jpeg"
            "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        }
    }.getOrNull()

    private fun searchKey(favorite: Favorite): String =
        listOfNotNull(
            favorite.title.takeIf { it.isNotBlank() },
            favorite.forumName.takeIf { it.isNotBlank() },
            favorite.authorName?.takeIf { it.isNotBlank() },
            favorite.abstractText?.takeIf { it.isNotBlank() },
            favorite.content?.takeIf { it.isNotBlank() },
        ).joinToString(" ").lowercase()

    private fun esc(raw: String): String = buildString(raw.length + 32) {
        raw.forEach { c ->
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }

    private fun formatTime(timestamp: Long): String =
        if (timestamp <= 0) "-" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            .format(Date(timestamp))

    private fun page(title: String, count: Int, nav: String, sections: String): String = """
<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>$title</title>
<style>
:root{color-scheme:light}
*{box-sizing:border-box}
body{margin:0;font:15px/1.75 -apple-system,"PingFang SC","Microsoft YaHei",sans-serif;background:#faf8f4;color:#2b2b2b}
.wrap{display:flex;align-items:flex-start}
aside{position:sticky;top:0;width:290px;max-height:100vh;overflow:auto;padding:18px;background:#f2ede4;border-right:1px solid #e3ddd2}
aside h1{font-size:15px;margin:0 0 4px}
aside .cnt{font-size:12px;color:#8a8378;margin-bottom:12px}
#q{width:100%;padding:8px 10px;border:1px solid #ded6c8;border-radius:8px;background:#fff;font-size:13px}
.nav{list-style:none;margin:14px 0 0;padding:0}
.nav li{margin:0 0 2px}
.nav a{display:block;padding:7px 8px;border-radius:6px;color:#4a4a4a;text-decoration:none;font-size:13px}
.nav a:hover{background:#e6dfd2}
.nav li.hit a{background:#ffe9a8;color:#5a4300}
main{flex:1;padding:22px 26px 60px;min-width:0}
.post{background:#fff;border:1px solid #eae3d6;border-radius:12px;padding:18px 20px;margin-bottom:18px}
.post h2{margin:0 0 8px;font-size:17px;line-height:1.5}
.meta{font-size:12px;color:#8a8378}
.abs{margin:10px 0;color:#5a5a5a;font-size:14px}
.pics{margin:12px 0}
.pics img{max-width:100%;border-radius:8px;margin:0 8px 8px 0;vertical-align:top}
pre.content{white-space:pre-wrap;word-break:break-word;background:#faf8f4;border:1px solid #eee7d9;border-radius:8px;padding:12px;font:13px/1.7 ui-monospace,Menlo,Consolas,monospace;color:#3a3a3a;margin:12px 0 0}
.src{display:inline-block;margin-top:12px;font-size:13px;color:#2f6fbf}
.empty{color:#8a8378;font-size:13px;padding:8px}
@media (max-width:820px){.wrap{flex-direction:column}aside{position:static;width:auto;max-height:none;border-right:0;border-bottom:1px solid #e3ddd2}}
</style></head><body>
<div class="wrap">
<aside>
  <h1>$title</h1>
  <div class="cnt">共 $count 篇 · 导出于 ${formatTime(System.currentTimeMillis())}</div>
  <input id="q" type="search" placeholder="搜索标题 / 吧名 / 作者 / 正文…">
  <div id="hits"></div>
  $nav
</aside>
<main>$sections</main>
</div>
<script>
(function(){
  var q=document.getElementById('q'),hits=document.getElementById('hits'),
      items=[].slice.call(document.querySelectorAll('.nav li')),
      posts=[].slice.call(document.querySelectorAll('.post'));
  function run(){
    var k=q.value.trim().toLowerCase(),n=0;
    posts.forEach(function(p){var hit=!k||p.dataset.key.indexOf(k)>-1;p.style.display=hit?'':'none';if(hit)n++;});
    items.forEach(function(li){var a=li.querySelector('a'),hit=!k||a.textContent.toLowerCase().indexOf(k)>-1;li.style.display=hit?'':'none';li.className=hit&&k?'hit':'';});
    hits.innerHTML=k?'<div class="empty">命中 '+n+' 篇</div>':'';
  }
  q.addEventListener('input',run);run();
})();
</script>
</body></html>
""".trimIndent()
}