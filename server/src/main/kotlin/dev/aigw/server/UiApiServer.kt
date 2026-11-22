package dev.aigw.server

import com.google.gson.JsonObject
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * WebView2 前端的本地 HTTP 服务（只绑 127.0.0.1）。
 *
 * 与网关自身的 `/v1/…`（对客户端提供 OpenAI 兼容接口）**完全分开**：这里叫 `/ui/…`，
 * 是「给界面用的管理面」，不做模型推理。分开的好处是换壳不影响客户端接入地址，
 * 客户端仍然只用 `http://127.0.0.1:8790/v1`。
 *
 * 本类只做四件事——解析请求、分派、把 [UiOut] 写成 HTTP、发静态资源。
 * 业务在 [UiRoutes]，JSON 助手在 `Json.kt`，媒体实测在 [MediaProbe]。
 * 这样拆是因为原来 716 行的单文件里，"怎么读 body"和"怎么保存策略"混在一起，
 * 改一个端点要先翻半屏才知道它到底碰了哪几个 core 方法。
 *
 * 关键实现细节（都是实测踩出来的，改动前先看注释）：
 * 1. **静态资源用固定长度 + 读失败兜底**，不能用 chunked：NanoHTTPD 的
 *    chunked 路径在连接复用下会把残留字节留给下一个请求，
 *    表现是偶发的 `400 BAD REQUEST: Syntax error`，且只在连续刷新时才出现。
 * 2. **请求体按 Content-Length 读满**，读不满就当作客户端断开——
 *    半截 JSON 解析失败会变成 500，掩盖真正的原因。
 * 3. **路径穿越防护**必须做：服务监听在本机，但浏览器里的任何页面都能发请求过来。
 */
class UiApiServer(
    private val host: EngineHost,
    private val webRoot: File,
    port: Int,
) : NanoHTTPD("127.0.0.1", port) {

    private val routes = UiRoutes(host)    // ---------------------------------------------------------------- 入口

    override fun serve(session: IHTTPSession): Response {
        val started = System.currentTimeMillis()
        val uri = normalizeUri(session.uri)
        return try {
            when {
                uri == "/" || uri == "/index.html" -> serveStatic("index.html")
                isStaticPath(uri) -> serveStatic(uri.trimStart('/'))
                routes.has(uri) -> dispatch(uri, session)
                else -> notFound(uri)
            }
        } catch (e: UiException) {
            jsonResponse(resultJson(false, e.message), e.status)
        } catch (e: Exception) {
            // 未知异常一律 500，但把类型带上：只给 message 时，NPE 之类的会显示成空串。
            // 同时落一条日志——500 不该只在界面上闪一下，排查时得能翻到。
            host.log("UI 500 ${session.method} $uri：${e.javaClass.simpleName}: ${e.message}")
            jsonResponse(resultJson(false, e.message ?: e.javaClass.simpleName),
                Response.Status.INTERNAL_ERROR)
        } finally {
            val ms = System.currentTimeMillis() - started
            // 慢请求单独记一行：界面上「点一下卡住」只能靠这条定位
            if (ms > SLOW_REQUEST_MS) host.log("UI 慢请求 ${session.method} $uri ${ms}ms")
        }
    }

    private fun dispatch(uri: String, session: IHTTPSession): Response {
        val body = readBody(session)
        val req = UiRequest(
            uri = uri,
            method = session.method?.name ?: "GET",
            query = normalizeQuery(session.parameters),
            rawBody = body,
        )
        return when (val out = routes.handle(req)) {
            is UiOut.Json -> jsonResponse(out.body)
            is UiOut.Text -> textResponse(out.status, out.text, out.mime)
            is UiOut.FileBody -> serveFile(out.file, out.mime)
        }
    }

    // ---------------------------------------------------------------- 请求解析

    private fun normalizeUri(raw: String?): String {
        val noQuery = (raw ?: "/").substringBefore('?').trimEnd('/')
        return noQuery.ifEmpty { "/" }
    }

    /** NanoHTTPD 的 parameters 是 `List<String>`，这里只取首值（本服务没有重复参数）。 */
    private fun normalizeQuery(params: Map<String, List<String>>?): Map<String, String> =
        params?.mapValues { (_, v) -> v.firstOrNull().orEmpty() } ?: emptyMap()

    /**
     * 按 Content-Length 读满请求体。
     *
     * 三种失败都要翻译成 4xx 而不是 500：
     * - 超过上限 → 413（转写接口会把音频 base64 整个读进内存，没有上限时
     *   一个畸形请求就能把堆打爆）；
     * - 读不满 → 400（客户端中途断开，半截 JSON 解析失败会掩盖真正原因）；
     * - 读超时 → 400（NanoHTTPD 的 SOCKET_READ_TIMEOUT 到了；把它当 500
     *   会在日志里刷出一堆看似可怕的「服务器内部错误」，其实只是客户端发了一半）。
     */
    private fun readBody(session: IHTTPSession): String {
        val len = session.headers["content-length"]?.toLongOrNull() ?: 0L
        if (len <= 0L) return ""
        if (len > MAX_BODY_BYTES) throw UiException.tooLarge(
            "请求体过大（${len / 1024 / 1024} MB），上限 ${MAX_BODY_BYTES / 1024 / 1024} MB")

        val buf = ByteArray(len.toInt())
        var read = 0
        try {
            val ins = session.inputStream
            while (read < buf.size) {
                val n = ins.read(buf, read, buf.size - read)
                if (n <= 0) break
                read += n
            }
        } catch (e: java.io.IOException) {
            throw UiException.badRequest("读取请求体失败（${e.javaClass.simpleName}：${e.message}）")
        }
        if (read < buf.size) throw UiException.badRequest("请求体不完整（收到 $read / ${buf.size} 字节）")
        return String(buf, 0, read, StandardCharsets.UTF_8)
    }

    // ---------------------------------------------------------------- 静态资源

    private fun isStaticPath(uri: String): Boolean {
        if (uri.startsWith("/assets/")) return true
        val ext = uri.substringAfterLast('.', "")
        return ext in STATIC_EXTS
    }

    private fun mimeOf(ext: String): String = when (ext.lowercase()) {
        "html", "htm" -> "text/html; charset=utf-8"
        "js", "mjs" -> "application/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "ico" -> "image/x-icon"
        "woff2" -> "font/woff2"
        "json" -> "application/json; charset=utf-8"
        "map" -> "application/json; charset=utf-8"
        else -> "application/octet-stream"
    }

    /**
     * 静态资源：从 webRoot 读，**路径穿越防护**是必须的。
     *
     * 用 fixed-length 而不是 chunked 的原因见类注释：chunked 在 keep-alive 下
     * 会污染下一个请求。静态资源本就有确定大小，没有理由分块。
     */
    private fun serveStatic(rel: String): Response {
        val base = runCatching { webRoot.canonicalFile }.getOrElse { webRoot.absoluteFile }
        val target = runCatching { File(base, rel).canonicalFile }.getOrElse {
            return textResponse(Response.Status.NOT_FOUND, "not found")
        }
        if (!target.path.startsWith(base.path) || !target.isFile) {
            return textResponse(Response.Status.NOT_FOUND, "not found")
        }
        return serveFile(target, mimeOf(target.extension))
    }

    private fun serveFile(file: File, mime: String): Response {
        val stream: InputStream = runCatching { file.inputStream() }.getOrElse {
            return textResponse(Response.Status.NOT_FOUND, "not found")
        }
        return newFixedLengthResponse(Response.Status.OK, mime, stream, file.length())
    }

    // ---------------------------------------------------------------- 错误响应

    private fun notFound(uri: String): Response =
        jsonResponse(resultJson(false, "未知接口：$uri"), Response.Status.NOT_FOUND)

    companion object {
        /** 超过这个耗时就落一条日志，用于定位「点一下界面卡住」。 */
        private const val SLOW_REQUEST_MS = 1500L

        private val STATIC_EXTS = setOf(
            "js", "mjs", "css", "html", "htm", "svg", "png", "jpg", "jpeg",
            "webp", "ico", "woff2", "json", "map",
        )
    }
}
