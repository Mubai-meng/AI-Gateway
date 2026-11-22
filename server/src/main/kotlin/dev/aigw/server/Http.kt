package dev.aigw.server

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.StandardCharsets

/** 全局 pretty-print Gson：仅供调试导出，不参与接口序列化。 */
internal val GSON = GsonBuilder().setPrettyPrinting().create()

/**
 * 一个管理面请求。
 *
 * 只保留路由需要的东西：路径、方法、query、原始体。
 * 体解析成 JSON 是懒加载的——`GET /ui/state` 这类请求根本不需要解析体，
 * 提前解析等于每个心跳都白跑一次 JsonParser。
 */
internal class UiRequest(
    val uri: String,
    val method: String,
    val query: Map<String, String>,
    private val rawBody: String,
) {
    /** 写操作一律 POST；其余方法（GET/HEAD）只允许读端点。 */
    val isPost: Boolean get() = method.equals("POST", ignoreCase = true)

    val body: String get() = rawBody

    val json: JsonObject by lazy { parseJsonObject(rawBody) }

    /** 便于在路由里直接 `req.str("providerId")`。 */
    fun str(key: String): String = json.str(key)
    fun requireStr(key: String, label: String = key): String = json.requireStr(key, label)
}

/**
 * 路由处理结果。
 *
 * 业务层（[UiRoutes]）不认识 NanoHTTPD，只产出这三种；把「怎么写成 HTTP」全部
 * 留在 [UiApiServer]。好处是路由可以纯粹按「入参 → JSON」来写和测。
 */
internal sealed class UiOut {
    class Json(val body: JsonObject) : UiOut()
    class Text(val text: String, val mime: String, val status: Response.Status = Response.Status.OK) : UiOut()
    class FileBody(val file: File, val mime: String) : UiOut()
}

/**
 * 业务错误 → 对应的 HTTP 语义。
 *
 * 超出预期的异常才走 500；参数缺失、参数非法走 400——原来这些一律 500，
 * 浏览器控制台里全是红的，排查真实故障时反而要逐个点开看 message。
 */
internal class UiException(
    val status: Response.Status,
    override val message: String,
    val cause2: Throwable? = null,
) : RuntimeException(message) {

    companion object {
        fun badRequest(message: String): UiException = UiException(Response.Status.BAD_REQUEST, message)
        fun notFound(message: String): UiException = UiException(Response.Status.NOT_FOUND, message)
        fun conflict(message: String): UiException = UiException(Response.Status.CONFLICT, message)
        fun tooLarge(message: String): UiException =
            UiException(Response.Status.PAYLOAD_TOO_LARGE, message)
    }
}

/**
 * JSON 响应：显式 Content-Length。
 *
 * 不用 chunked：界面心跳是每 2 秒一次的小响应，chunked 只是多一层分块编码；
 * 而且固定长度能让浏览器直接把连接复用下去，省掉重复握手。
 */
internal fun jsonResponse(body: JsonObject, status: Response.Status = Response.Status.OK): Response {
    val bytes = body.toString().toByteArray(StandardCharsets.UTF_8)
    return NanoHTTPD.newFixedLengthResponse(
        status, "application/json; charset=utf-8", ByteArrayInputStream(bytes), bytes.size.toLong(),
    )
}

/** 文本响应（错误页、403、413 等）。 */
internal fun textResponse(
    status: Response.Status,
    text: String,
    mime: String = "text/plain; charset=utf-8",
): Response {
    val bytes = text.toByteArray(StandardCharsets.UTF_8)
    return NanoHTTPD.newFixedLengthResponse(
        status, mime, ByteArrayInputStream(bytes), bytes.size.toLong(),
    )
}

/**
 * 把与请求方法相关的前缀检查集中在这里。
 *
 * 为什么要显式判方法：`serve` 原本对 GET/POST 一视同仁，于是
 * `GET /ui/account/delete?providerId=x&uid=y` 真的能删账号——
 * 浏览器里的任意页面都能构造这个 GET（CSRF 面）。现在写端点只认 POST。
 */
internal fun ensurePost(req: UiRequest) {
    if (!req.isPost) throw UiException.badRequest("该接口只接受 POST：${req.uri}")
}

/** 请求体上限：转写接口要把音频读成 base64，40 MB 是够用的上限。 */
internal const val MAX_BODY_BYTES = 40L * 1024 * 1024
