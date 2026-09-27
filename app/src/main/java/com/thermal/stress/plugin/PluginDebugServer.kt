package com.thermal.stress.plugin

import android.content.Context
import android.text.TextUtils
import android.util.Log
import com.thermal.stress.Backend
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 插件端口 HTTP 服务（绑定 0.0.0.0，端口由 LineOS 在注册结果中动态分配）。
 *
 * 这是 PC 端访问本 APP 的正式入口（动态端口，不再占用固定 8091）：
 *  - GET  /            完整 PC 控制台（assets/web/index.html）
 *  - GET  /debug       LineOS 调试入口，同样直接出完整控制台（不跳独立调试页）
 *  - *   /api/...      透明代理到进程内 WebServer（127.0.0.1:Backend.port，系统动态分配），
 *                      控制台所有 fetch 走同源相对路径，无跨域问题
 *
 * 插件协议调试 API（必须携带正确 sessionToken，否则 403）：
 *  - GET /api/status?sessionToken=...
 *  - GET /api/invoke?sessionToken=...&action=...
 */
class PluginDebugServer(
    private val port: Int,
    private val bridge: ThermalPluginBridge,
    context: Context
) : NanoHTTPD(port) {

    private val appContext: Context = context.applicationContext

    /** 控制台页面字节，首次访问时从 assets 读取并缓存 */
    private val consoleHtml: ByteArray by lazy {
        appContext.assets.open("web/index.html").use { it.readBytes() }
    }

    fun stopSafe() {
        try { stop() } catch (t: Throwable) { Log.w(TAG, "stop failed", t) }
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"

        // 插件协议调试 API：强制 sessionToken 校验
        if (uri == "/api/status" || uri == "/api/invoke") {
            if (!isTokenValid(session.parms["sessionToken"])) {
                Log.w(TAG, "Rejected $uri — invalid/missing sessionToken")
                return json(Response.Status.FORBIDDEN,
                    "{\"error\":\"forbidden: invalid sessionToken\"}")
            }
            return try {
                if (uri == "/api/status")
                    newFixedLengthResponse(Response.Status.OK, "application/json",
                        bridge.debugStateJson())
                else handleInvoke(session.parms)
            } catch (e: Exception) {
                Log.e(TAG, "serve error", e)
                json(Response.Status.INTERNAL_ERROR,
                    "{\"error\":\"${escape(e.message ?: "")}\"}")
            }
        }

        // /debug（LineOS 调试入口）与 / 一样，直接打开完整 PC 控制台
        if (session.method == Method.GET &&
            (uri == "/" || uri == "/index.html" || uri == "/debug")) {
            return newFixedLengthResponse(
                Response.Status.OK, "text/html; charset=utf-8",
                ByteArrayInputStream(consoleHtml), consoleHtml.size.toLong())
        }

        // 网页图标（浏览器标签页）
        if (session.method == Method.GET &&
            (uri == "/favicon.png" || uri == "/favicon64.png")) {
            val bytes = bridge.appContext.assets.open("web$uri").use { it.readBytes() }
            return newFixedLengthResponse(Response.Status.OK, "image/png",
                ByteArrayInputStream(bytes), bytes.size.toLong())
        }

        // 其余 /api/... 全部代理给进程内 WebServer
        if (uri.startsWith("/api/")) return proxyToBackend(session)

        return json(Response.Status.NOT_FOUND, "{\"error\":\"not found\"}")
    }

    // ------------------------------------------------------------------
    //  反向代理：转发到 127.0.0.1:Backend.HTTP_PORT（进程内控制台）
    // ------------------------------------------------------------------

    private fun proxyToBackend(session: IHTTPSession): Response {
        var conn: HttpURLConnection? = null
        try {
            val qs = session.queryParameterString
            val target = "http://127.0.0.1:${Backend.port}${session.uri}" +
                if (qs.isNullOrEmpty()) "" else "?$qs"
            conn = (URL(target).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 15_000
                instanceFollowRedirects = false
                requestMethod = session.method.name
                // 透传请求体（控制台全部是 application/x-www-form-urlencoded）
                if (session.method == Method.POST || session.method == Method.PUT) {
                    // parseBody 前 parms 只含 query；parseBody 后 POST 字段被合并进 parms
                    val queryKeys = session.parms.keys.toHashSet()
                    val files = HashMap<String, String>()
                    session.parseBody(files)
                    // body 不含 '=' 时原文在 files["postData"]；否则用差集重建表单
                    val rawPost = files["postData"]
                    val rawBody = rawPost ?: session.parms.entries
                        .filter { it.key !in queryKeys }
                        .joinToString("&") { urlEnc(it.key) + "=" + urlEnc(it.value) }
                    if (rawBody.isNotEmpty()) {
                        doOutput = true
                        setRequestProperty("Content-Type",
                            session.headers["content-type"]
                                ?: "application/x-www-form-urlencoded")
                        outputStream.use { it.write(rawBody.toByteArray(Charsets.UTF_8)) }
                    }
                }
            }
            val code = conn.responseCode
            val ctype = conn.contentType ?: "application/json"
            val stream = if (code in 200..399) conn.inputStream else conn.errorStream
            val body = stream?.use { it.readBytes() } ?: ByteArray(0)
            val status = Response.Status.values().firstOrNull { it.requestStatus == code }
                ?: Response.Status.OK
            return newFixedLengthResponse(status, ctype, ByteArrayInputStream(body), body.size.toLong())
        } catch (e: Exception) {
            Log.e(TAG, "proxy error: ${session.uri}", e)
            return json(Response.Status.INTERNAL_ERROR,
                "{\"error\":\"backend proxy failed: ${escape(e.message ?: "")}\"}")
        } finally {
            conn?.disconnect()
        }
    }

    private fun json(status: Response.Status, body: String): Response =
        newFixedLengthResponse(status, "application/json", body)

    private fun urlEnc(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8")

    private fun handleInvoke(parms: Map<String, String>): Response {
        val action = parms["action"]
            ?: return json(Response.Status.BAD_REQUEST, "{\"error\":\"missing action\"}")
        // 两个带参 action 各自只有一个参数，统一用 param 接收
        val param = parms["param"] ?: parms["level"] ?: parms["profile"]
        val data = try {
            bridge.invokeForDebug(action, param)
        } catch (e: Exception) {
            return json(Response.Status.OK,
                "{\"error\":${ThermalPluginBridge.json(e.message ?: "")}}")
        }
        val sb = StringBuilder(256)
        sb.append('{')
        var first = true
        for ((k, v) in data) {
            if (!first) sb.append(',')
            first = false
            sb.append(ThermalPluginBridge.json(k)).append(':')
            appendValue(sb, v)
        }
        sb.append('}')
        return json(Response.Status.OK, sb.toString())
    }

    @Suppress("UNCHECKED_CAST")
    private fun appendValue(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is Number, is Boolean -> sb.append(v.toString())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, vv) in v as Map<String, Any?>) {
                    if (!first) sb.append(',')
                    first = false
                    sb.append(ThermalPluginBridge.json(k)).append(':')
                    appendValue(sb, vv)
                }
                sb.append('}')
            }
            is List<*> -> {
                sb.append('[')
                var first = true
                for (item in v) {
                    if (!first) sb.append(',')
                    first = false
                    appendValue(sb, item)
                }
                sb.append(']')
            }
            else -> sb.append(ThermalPluginBridge.json(v.toString()))
        }
    }

    private fun isTokenValid(token: String?): Boolean {
        val expected = PluginState.sessionToken
        return !TextUtils.isEmpty(expected) && expected == token
    }

    private fun escape(s: String?): String {
        if (s == null) return ""
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")
    }

    companion object {
        private const val TAG = "ThermalDebugSrv"
    }
}
