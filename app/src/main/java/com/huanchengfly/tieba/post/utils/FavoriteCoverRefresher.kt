package com.huanchengfly.tieba.post.utils

import com.huanchengfly.tieba.post.api.TiebaApi
import com.huanchengfly.tieba.post.models.database.Favorite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 收藏封面的自动刷新。
 *
 * 背景：贴吧图片 URL 上的 `?tbpicau=<token>` 会过期，过期后服务端不报错，
 * 而是静默返回一张 238x238 的默认图标。所以「封面还在不在」没法用
 * 状态码判断，只能把图拿下来看尺寸。
 *
 * 流程（刻意做成两段，前段不阻塞显示）：
 * 1. 列表先按现有 coverUrl 照常显示，不等待任何网络请求
 * 2. 后台用 Range 头只取前 64KB 判断是不是那张 238x238 占位图
 * 3. 是的话再请求一次帖子详情，拿接口当前签发的、带新 token 的图片地址
 *
 * 只有确认失效的图才会发第二次请求，没失效的不动——省请求也是省时间。
 */
object FavoriteCoverRefresher {

    private const val PLACEHOLDER_MIN = 220
    private const val PLACEHOLDER_MAX = 260

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 需要刷新就返回新的图片地址，不需要返回 null。
     * 出错一律返回 null——封面是锦上添花，不能因为它把列表搞崩。
     */
    suspend fun refreshIfNeeded(favorite: Favorite): String? = withContext(Dispatchers.IO) {
        val url = favorite.coverUrl?.takeIf { it.isNotBlank() } ?: return@withContext null
        if (!isPlaceholder(url)) return@withContext null
        runCatching { fetchFresh(favorite.threadId) }.getOrNull()
    }

    /**
     * 用 Range 头只取前 64KB 判断是不是占位图。
     * 解析尺寸只需要文件头，没必要下整张图。
     */
    private fun isPlaceholder(url: String): Boolean {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0")
            .header("Range", "bytes=0-65535")
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return false
                val bytes = response.body?.bytes() ?: return false
                val (w, h) = readImageSize(bytes) ?: return false
                w in PLACEHOLDER_MIN..PLACEHOLDER_MAX && h in PLACEHOLDER_MIN..PLACEHOLDER_MAX
            }
        }.getOrDefault(false)
    }

    /** 请求帖子详情，取一楼第一张图的地址（接口返回的就是带新 token 的） */
    private suspend fun fetchFresh(threadId: Long): String? {
        if (threadId == 0L) return null
        val first = TiebaApi.getInstance()
            .pbPageFlow(threadId = threadId, page = 1)
            .first()
            .data_
            ?.post_list
            ?.firstOrNull()
            ?: return null
        // type == 3 是图片；几个候选字段依次取第一个非空
        return first.content
            ?.asSequence()
            ?.filter { it.type == 3 }
            ?.map {
                it.originSrc.ifBlank { it.bigSrc.ifBlank { it.src.ifBlank { it.cdnSrc } } }
            }
            ?.map { it.trim() }
            ?.firstOrNull { it.isNotBlank() }
    }

    /**
     * 只读文件头解析图片宽高，不解码整张图。覆盖 JPEG / PNG / GIF / WebP。
     */
    private fun readImageSize(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 24) return null
        fun be16(i: Int) = ((bytes[i].toInt() and 0xff) shl 8) or (bytes[i + 1].toInt() and 0xff)
        // PNG
        if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte()) {
            return be16(18) to be16(22)
        }
        // GIF
        if (bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == '8'.code.toByte()
        ) {
            return ((bytes[6].toInt() and 0xff) + ((bytes[7].toInt() and 0xff) shl 8)) to
                ((bytes[8].toInt() and 0xff) + ((bytes[9].toInt() and 0xff) shl 8))
        }
        // WebP
        if (bytes[0] == 'R'.code.toByte() && bytes[8] == 'W'.code.toByte() &&
            bytes[9] == 'E'.code.toByte() && bytes[12] == 'V'.code.toByte() &&
            bytes[13] == 'P'.code.toByte()
        ) {
            when (String(bytes, 12, 4, Charsets.US_ASCII)) {
                "VP8 " -> return (be16(26) and 0x3fff) to (be16(28) and 0x3fff)
                "VP8L" -> {
                    val b = (bytes[21].toInt() and 0xff) or
                        ((bytes[22].toInt() and 0xff) shl 8) or
                        ((bytes[23].toInt() and 0xff) shl 16) or
                        ((bytes[24].toInt() and 0xff) shl 24)
                    return ((b and 0x3FFF) + 1) to (((b shr 14) and 0x3FFF) + 1)
                }

                "VP8X" -> {
                    val w = (bytes[24].toInt() and 0xff) or
                        ((bytes[25].toInt() and 0xff) shl 8) or
                        ((bytes[26].toInt() and 0xff) shl 16)
                    val h = (bytes[27].toInt() and 0xff) or
                        ((bytes[28].toInt() and 0xff) shl 8) or
                        ((bytes[29].toInt() and 0xff) shl 16)
                    return (w + 1) to (h + 1)
                }
            }
            return null
        }
        // JPEG：FF D8 ... FF C0/C1/C2 后跟 段长(2) 精度(1) 高(2) 宽(2)
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) {
            var i = 2
            while (i + 9 < bytes.size) {
                if (bytes[i] != 0xFF.toByte()) {
                    i++
                    continue
                }
                val marker = bytes[i + 1].toInt() and 0xff
                if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                    return be16(i + 7) to be16(i + 5)
                }
                i += 2 + be16(i + 2)
            }
        }
        return null
    }
}
