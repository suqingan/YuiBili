package com.yuilittle.bili

import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Bilibili web login endpoints (scan QR code + SMS) and the authenticated
 * profile endpoint (nav). Only official endpoints are used; nothing is
 * automated or imitated beyond what the public web flow does.
 */
object BiliLoginApi {

    private const val LOGIN_UA =
        "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
    private const val REFERER = "https://www.bilibili.com/"

    /** 复用 BiliHttp 的共享连接池与 HTTP/2 复用，缩短冷启动 TLS/建连耗时。 */
    private val client: OkHttpClient by lazy {
        BiliHttp.client.newBuilder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /** QR scan status from poll(). */
    const val QR_PENDING = 0
    const val QR_SCANNED = 1
    const val QR_CONFIRMED = 2
    const val QR_EXPIRED = 3
    const val QR_FAILED = 4

    /** Profile of the logged-in account (from /x/web-interface/nav). */
    data class UserInfo(
        val uid: Long,
        val uname: String,
        val face: String,
        val coins: Double,
        val vipStatus: Int,
        val vipType: Int,
        val level: Int,
        val following: Int,
        val follower: Int
    )

    /** Async GET returning the parsed JSON object, or error text. */
    private fun getJson(
        url: String,
        cookie: String = "",
        headerSink: ((List<Pair<String, String>>) -> Unit)? = null,
        callback: (JSONObject?, String?) -> Unit
    ) {
        val request = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", LOGIN_UA)
            .header("Referer", REFERER)
            .header("Accept", "application/json, text/plain, */*")
            .apply { if (cookie.isNotBlank()) header("Cookie", cookie) }
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(null, e.message ?: "网络错误")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { res ->
                    try {
                        headerSink?.invoke(res.headers.toList())
                        val text = res.body?.string().orEmpty()
                        if (text.isBlank()) {
                            callback(null, "网络错误（HTTP ${res.code}）")
                        } else {
                            callback(JSONObject(text), null)
                        }
                    } catch (error: Exception) {
                        callback(null, error.message ?: "网络错误")
                    }
                }
            }
        })
    }

    /** Async POST form-encoded returning the parsed JSON object, or error text. */
    private fun postForm(
        url: String,
        fields: Map<String, String>,
        cookie: String = "",
        callback: (JSONObject?, String?) -> Unit
    ) {
        val body = FormBody.Builder().apply {
            fields.forEach { (k, v) -> add(k, v) }
        }.build()
        val request = Request.Builder()
            .url(url)
            .post(body)
            .header("User-Agent", LOGIN_UA)
            .header("Referer", REFERER)
            .header("Origin", "https://www.bilibili.com")
            .apply { if (cookie.isNotBlank()) header("Cookie", cookie) }
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(null, e.message ?: "网络错误")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { res ->
                    try {
                        val text = res.body?.string().orEmpty()
                        if (text.isBlank()) {
                            callback(null, "网络错误（HTTP ${res.code}）")
                        } else {
                            callback(JSONObject(text), null)
                        }
                    } catch (error: Exception) {
                        callback(null, error.message ?: "网络错误")
                    }
                }
            }
        })
    }

    // ── QR code login ──────────────────────────────────────────────

    /** Requests a fresh QR login payload: (loginUrl, qrcodeKey) or error. */
    fun generateQrCode(callback: (String?, String?, String?) -> Unit) {
        getJson("https://passport.bilibili.com/x/passport-login/web/qrcode/generate") { root, error ->
            if (error != null || root == null) { callback(null, null, error ?: "网络错误"); return@getJson }
            val data = root.optJSONObject("data")
            callback(data?.optString("url"), data?.optString("qrcode_key"), null)
        }
    }

    /**
     * Polls the QR scan state.
     * On success the returned cookie string (SESSDATA etc.) is non-blank.
     */
    fun pollQrCode(key: String, callback: (state: Int, cookie: String?) -> Unit) {
        val url = "https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key=" +
            URLEncoder.encode(key, "UTF-8")
        // Bilibili hands out the session cookies via Set-Cookie response headers
        // on a successful poll; capture them as the primary source (the data.url
        // cross-domain link can be empty for some accounts).
        val headerPairs = ArrayList<String>()
        getJson(url, headerSink = { headers ->
            headers.forEach { (key, raw) ->
                if (key.equals("Set-Cookie", ignoreCase = true)) {
                    val pair = raw.substringBefore(';').trim()
                    val name = pair.substringBefore('=')
                    val value = pair.substringAfter('=', "")
                    if (name in SESSION_NAMES && value.isNotBlank() &&
                        headerPairs.none { it.startsWith("$name=") }
                    ) {
                        headerPairs += "$name=$value"
                    }
                }
            }
        }) { root, error ->
            if (error != null || root == null) { callback(QR_FAILED, null); return@getJson }
            val data = root.optJSONObject("data") ?: run { callback(QR_FAILED, null); return@getJson }
            when (data.optInt("code", -1)) {
                0 -> {
                    val pairs = ArrayList<String>()
                    // 1) 响应头 Set-Cookie（主路径）
                    pairs += headerPairs
                    // 2) JSON body 里的 cookies 对象
                    val cookies = data.optJSONObject("cookies")
                    if (cookies != null) {
                        val names = cookies.keys()
                        while (names.hasNext()) {
                            val name = names.next()
                            val value = cookies.optString(name)
                            if (name in SESSION_NAMES && value.isNotBlank() &&
                                pairs.none { it.startsWith("$name=") }
                            ) {
                                pairs += "$name=$value"
                            }
                        }
                    }
                    // 3) 旧版 redirect url 查询参数
                    val redirect = data.optString("url")
                    if (redirect.isNotBlank()) {
                        redirect.substringAfter('?', "").split('&').forEach { param ->
                            val name = param.substringBefore('=')
                            val value = try {
                                java.net.URLDecoder.decode(param.substringAfter('=', ""), "UTF-8")
                            } catch (_: Exception) {
                                param.substringAfter('=', "")
                            }
                            if (name in SESSION_NAMES && value.isNotBlank() &&
                                pairs.none { it.startsWith("$name=") }
                            ) {
                                pairs += "$name=$value"
                            }
                        }
                    }
                    // 4) 部分账号会给 refresh_token，本身不能当 cookie，但至少保证 code=0 被识别
                    callback(QR_CONFIRMED, if (pairs.isEmpty()) null else pairs.joinToString("; "))
                }
                86101 -> callback(QR_PENDING, null)      // not scanned yet
                86090 -> callback(QR_SCANNED, null)      // scanned, waiting for confirm
                86038 -> callback(QR_EXPIRED, null)      // expired
                else -> callback(QR_PENDING, null)
            }
        }
    }

    // ── Profile ────────────────────────────────────────────────────

    fun fetchVipStatus(cookie: String, callback: (Int?, Int) -> Unit) {
        getJson("https://api.bilibili.com/x/web-interface/nav", cookie) { root, _ ->
            val data = root?.optJSONObject("data")
            when {
                root?.optInt("code", -1) == 0 && data?.optBoolean("isLogin") == true ->
                    callback(data.optInt("vipStatus"), data.optInt("vipType"))
                root?.optInt("code", -1) == -101 -> callback(0, 0)
                else -> callback(null, 0)
            }
        }
    }

    /**
     * 并发拉取 /x/web-interface/nav 与 /x/web-interface/nav/stat 并汇聚结果，
     * 仅在 nav/stat 失败时再回退 /x/relation/stat?vmid=，减少一次完整 RTT。
     */
    fun fetchProfile(
        cookie: String,
        onBaseReady: ((UserInfo) -> Unit)? = null,
        callback: (UserInfo?, String?) -> Unit
    ) {
        val navRef = AtomicReference<Pair<UserInfo?, String?>?>(null)
        val statRef = AtomicReference<Pair<Int, Int>?>(null)
        val pending = AtomicInteger(2)

        fun tryFinish() {
            if (pending.get() > 0) return
            val navResult = navRef.get()
            val base = navResult?.first
            if (base == null) {
                callback(null, navResult?.second ?: "网络错误")
                return
            }
            val counts = statRef.get()
            if (counts != null) {
                callback(base.copy(following = counts.first, follower = counts.second), null)
                return
            }
            if (base.uid <= 0L) {
                callback(base, null)
                return
            }
            getJson(
                "https://api.bilibili.com/x/relation/stat?vmid=${base.uid}",
                cookie
            ) { relRoot, _ ->
                val rel = if (relRoot != null && relRoot.optInt("code") == 0) {
                    relRoot.optJSONObject("data")
                } else null
                if (rel != null) {
                    callback(
                        base.copy(
                            following = rel.optInt("following", base.following),
                            follower = rel.optInt("follower", base.follower)
                        ),
                        null
                    )
                } else {
                    callback(base, null)
                }
            }
        }

        getJson("https://api.bilibili.com/x/web-interface/nav", cookie) { root, error ->
            val parsed: Pair<UserInfo?, String?> = when {
                error != null || root == null -> null to (error ?: "网络错误")
                root.optInt("code") != 0 -> null to "未登录"
                else -> {
                    val data = root.optJSONObject("data")
                    if (data == null) null to "数据异常"
                    else if (!data.optBoolean("isLogin", false)) null to "未登录"
                    else {
                        val levelInfo = data.optJSONObject("level_info")
                        UserInfo(
                            uid = data.optLong("mid", 0L),
                            uname = data.optString("uname", "未知用户"),
                            face = data.optString("face", ""),
                            coins = data.optDouble("money", 0.0),
                            vipStatus = data.optInt("vipStatus", 0),
                            vipType = data.optInt("vipType", 0),
                            level = levelInfo?.optInt("current_level", 0) ?: 0,
                            following = data.optInt("following", data.optInt("mid_following", 0)),
                            follower = data.optInt("follower", 0)
                        ) to null
                    }
                }
            }
            navRef.set(parsed)
            parsed.first?.let { onBaseReady?.invoke(it) }
            if (pending.decrementAndGet() == 0) tryFinish()
        }

        getJson("https://api.bilibili.com/x/web-interface/nav/stat", cookie) { statRoot, _ ->
            val stat = if (statRoot != null && statRoot.optInt("code") == 0) {
                statRoot.optJSONObject("data")
            } else null
            if (stat != null) {
                statRef.set(stat.optInt("following", -1) to stat.optInt("follower", -1))
            }
            if (pending.decrementAndGet() == 0) tryFinish()
        }
    }

    private val SESSION_NAMES = setOf(
        "SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5", "sid"
    )
}
