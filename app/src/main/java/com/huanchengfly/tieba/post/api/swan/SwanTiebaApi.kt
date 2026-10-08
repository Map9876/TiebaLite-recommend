package com.huanchengfly.tieba.post.api.swan

import com.huanchengfly.tieba.post.models.SwanThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 百度贴吧小程序的 frs/page 接口（免登录）。
 *
 * 鉴权只有一个 sign：参数按 key 字典序拼成 `k=v` 直接相连、末尾加 SECRET、MD5 32 位小写。
 * 改任何一个参数都必须重算，否则返回 error_code=110001。
 */
object SwanTiebaApi {

    private const val BASE_URL = "https://tiebaswan.baidu.com/c/f/frs/page"
    private const val SIGN_SECRET = "0039d79dc3cc2075129745a30237a3c4"
    private const val CUID = "A63C81D31E855BD294D9E3F56305E925|VXU2U2B2I"
    private const val UA = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Version/4.0 Chrome/97.0.4692.98 Mobile Safari/537.36"

    /** 小程序 frs_tab_info 里的 tab_id */
    const val TAB_ALL = 0
    const val TAB_HOT = 2

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private fun sign(params: Map<String, String>): String {
        val raw = params.keys.sorted().joinToString("") { "$it=${params[it]}" } + SIGN_SECRET
        return MessageDigest.getInstance("MD5").digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /**
     * 拉某个吧的帖子列表。
     * @param tabId [TAB_ALL] 看贴 / [TAB_HOT] 热门
     */
    suspend fun threads(
        forumName: String,
        page: Int = 1,
        pageSize: Int = 30,
        tabId: Int = TAB_HOT,
    ): List<SwanThread> = withContext(Dispatchers.IO) {
        val params = buildMap {
            put("subapp_type", "smallapp")
            put("tab_id", tabId.toString())
            put("tab_type", "all")
            put("fr", "smallapp")
            put("kw", forumName)
            put("pn", page.toString())
            put("rn", pageSize.toString())
            put("r", "2")
            put("is_newfrs", "1")
            put("is_newfeed", "1")
            put("timestamp", System.currentTimeMillis().toString())
            put("source_platform", "baidu")
            put("obj_param2", "flyflow")
            put("browser", "flyflow")
            put("_client_type", "2")
            put("_client_version", "12.77.0")
            put("call_from", "baidu")
            put("cuid", CUID)
            put("swan_cuid", CUID)
        }
        val url = BASE_URL.toHttpUrl().newBuilder().apply {
            (params + ("sign" to sign(params))).forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()

        val response = client.newCall(
            Request.Builder().url(url).header("User-Agent", UA).build()
        ).execute()

        response.use {
            val body = it.body?.string().orEmpty()
            if (!it.isSuccessful) error("HTTP ${it.code}")
            parse(body)
        }
    }

    private fun parse(raw: String): List<SwanThread> {
        val root = json.parseToJsonElement(raw).jsonObject
        val errorCode = root["error_code"]?.jsonPrimitive?.intOrNull ?: 0
        if (errorCode != 0) {
            error(root["error_msg"]?.jsonPrimitive?.contentOrNull ?: "error_code=$errorCode")
        }
        val feeds = root["page_data"]?.jsonObject?.get("feed_list")?.jsonArray
            ?: return emptyList()
        return feeds.mapNotNull { feed ->
            val components = feed.jsonObject["feed"]?.jsonObject?.get("components")?.jsonArray
                ?: return@mapNotNull null
            parseFeed(components)
        }
    }

    private fun parseFeed(components: JsonArray): SwanThread? {
        var title = ""
        var abstract = ""
        var authorName = ""
        var authorAvatar = ""
        var userSchema = ""
        val pics = mutableListOf<String>()
        var tid = 0L
        var fid = 0L
        var replyNum = 0
        var agreeNum = 0
        var shareNum = 0
        var isLive = false
        var createTime = 0L

        components.forEach { element ->
            val component = element.jsonObject
            val name = component["component"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val payload = component[name] as? JsonObject ?: return@forEach
            when (name) {
                "feed_title" -> title = payload.joinTexts("data")
                "feed_abstract" -> abstract = payload.joinTexts("data").replace('\n', ' ')
                "feed_head" -> {
                    authorAvatar = payload.str("image_data", "img_url")
                    createTime = parseCreateTime(payload["extra_data"])
                    userSchema = payload.str("image_data", "schema")
                        .ifBlank { payload.str("schema") }
                    authorName = (payload["main_data"] as? JsonArray)
                        ?.mapNotNull { it.jsonObject.str("text", "text") }
                        ?.firstOrNull { it.isNotBlank() }
                        .orEmpty()
                }

                "feed_pic" -> (payload["pics"] as? JsonArray)?.forEach { pic ->
                    val obj = pic.jsonObject
                    val url = obj.str("big_pic_url").ifBlank {
                        obj.str("middle_pic_url").ifBlank { obj.str("small_pic_url") }
                    }
                    if (url.isNotBlank()) pics.add(url)
                }

                "feed_social" -> {
                    tid = payload.long("tid")
                    fid = payload.long("fid")
                    replyNum = payload.int("comment_num")
                    shareNum = payload.int("share_num")
                    agreeNum = (payload["agree"] as? JsonObject)?.int("agree_num") ?: 0
                }

                "feed_live" -> isLive = true
            }
        }
        if (tid == 0L) return null
        return SwanThread(
            tid = tid,
            forumId = fid,
            title = title.trim(),
            abstractText = abstract.trim(),
            authorName = authorName.trim(),
            authorAvatar = authorAvatar,
            pics = pics,
            replyNum = replyNum,
            agreeNum = agreeNum,
            shareNum = shareNum,
            isLive = isLive,
            createTime = createTime,
            userSchema = userSchema,
        )
    }

    /**
     * 发帖时间藏在 feed_head.extra_data 里：形如
     *   [{"type":1,"text":{"text":"1790815105","type":3}}]
     * 同一段还可能带吧主徽章之类的纯文本（text.type 不为 3），
     * 所以按 type==3 筛，拿到的是秒级时间戳。
     */
    private fun parseCreateTime(extra: JsonElement?): Long {
        val array = extra as? JsonArray ?: return 0L
        for (element in array) {
            val text = (element as? JsonObject)?.get("text") as? JsonObject ?: continue
            if ((text["type"] as? JsonPrimitive)?.intOrNull != 3) continue
            val value = (text["text"] as? JsonPrimitive)?.contentOrNull ?: continue
            value.toLongOrNull()?.let { if (it > 1_000_000_000L) return it }
        }
        return 0L
    }

    // -------- JsonObject 小工具 --------

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun JsonObject.str(parent: String, key: String): String =
        (this[parent] as? JsonObject)?.str(key).orEmpty()

    private fun JsonObject.int(key: String): Int =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toInt() ?: 0

    private fun JsonObject.long(key: String): Long =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L

    private fun JsonObject.joinTexts(key: String): String {
        val array = this[key] as? JsonArray ?: return ""
        return buildString {
            array.forEach { element ->
                val text = (element.jsonObject["text_info"] as? JsonObject)
                    ?.get("text")?.jsonPrimitive?.contentOrNull
                if (!text.isNullOrEmpty()) append(text)
            }
        }
    }
}