package dev.aigw.server

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.gateway.GatewayEngine
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

/**
 * 媒体能力实测（图像生成 / 语音转写）。
 *
 * **走真实网关而不是自己另写一套**：把请求原样转发到本机 `POST /v1/images/generations`
 * 与 `/v1/audio/transcriptions`，于是选号、换号、冷却、调用记录、错误映射全部
 * 与客户端实际调用路径一致。界面上看到的结果就是客户端会拿到的结果——
 * 不会出现「界面能出图、客户端报错」这种两套实现各说各话的情况。
 *
 * 原先这两个方法散在 `UiApiServer` 里，并且自带一套 HTTP 客户端，把"管理面"和
 * "网关下游调用"混成了一个类。抽出来之后这里只做一件事：把录制到的入参
 * 变成一次下游调用，再把下游结果翻译成界面能直接渲染的形状。
 */
internal class MediaProbe(private val engine: GatewayEngine) {

    private data class GwResponse(val ok: Boolean, val body: String)

    /**
     * 图片生成。
     *
     * @return `{ok, count, elapsedMs, images:[{dataUrl,bytes}], message?}`
     */
    fun image(req: JsonObject): UiOut {
        val prompt = req.str("prompt").trim()
        if (prompt.isEmpty()) return UiOut.Json(resultJson(false, "缺少 prompt"))

        val payload = JsonObject().apply {
            addProperty("prompt", prompt)
            req.str("model").trim().takeIf { it.isNotEmpty() }?.let { addProperty("model", it) }
            addProperty("n", req.int("n", 1).coerceIn(1, 4))
            req.str("size").trim().takeIf { it.isNotEmpty() }?.let { addProperty("size", it) }
            // 网关无论客户端要 url 还是 b64_json 都返回 b64_json（上游只给 base64），
            // 这里直接要 b64 便于前端渲染预览
            addProperty("response_format", "b64_json")
        }

        val started = System.currentTimeMillis()
        val r = callGateway("/v1/images/generations", payload.toString().toByteArray(StandardCharsets.UTF_8),
            "application/json")
        val out = JsonObject()
        if (!r.ok) {
            out.addProperty("ok", false)
            out.addProperty("message", gatewayErrorText(r.body))
            out.addProperty("elapsedMs", System.currentTimeMillis() - started)
            return UiOut.Json(out)
        }

        val parsed = runCatching { JsonParser.parseString(r.body).asJsonObject }.getOrNull()
            ?: return UiOut.Json(resultJson(false, "上游响应不是 JSON：${r.body.take(200)}"))
        val images = JsonArray()
        parsed.getAsJsonArray("data")?.forEach { item ->
            val obj = runCatching { item.asJsonObject }.getOrNull() ?: return@forEach
            val b64 = obj.get("b64_json")?.asString.orEmpty()
            val url = obj.get("url")?.asString.orEmpty()
            // 自定义供应商常常只回外链（不自带 base64），antigravity 只回 base64。
            // 两种都要认：只取 b64 时「界面上看不到图」而上游其实成功过。
            val dataUrl = if (b64.isNotEmpty()) "data:image/png;base64,$b64" else url
            if (dataUrl.isEmpty()) return@forEach
            images.add(JsonObject().apply {
                addProperty("dataUrl", dataUrl)
                addProperty("bytes", if (b64.isNotEmpty()) b64.length else 0)
                if (url.isNotEmpty()) addProperty("url", url)
            })
        }
        out.addProperty("ok", images.size() > 0)
        out.addProperty("count", images.size())
        out.addProperty("elapsedMs", System.currentTimeMillis() - started)
        out.add("images", images)
        if (images.size() == 0) out.addProperty("message", "上游返回成功但没有可用图片")
        return UiOut.Json(out)
    }

    /**
     * 语音转写。
     *
     * 前端把文件读成 base64 传上来（WebView2 里拿不到真实文件路径），
     * 这里重建 multipart 转发给网关——同样走真实链路。
     */
    fun transcribe(req: JsonObject): UiOut {
        val b64 = req.str("base64")
        if (b64.isEmpty()) return UiOut.Json(resultJson(false, "缺少文件内容"))
        val fileName = req.str("fileName").ifBlank { "audio.mp3" }
        val mime = req.str("mimeType").ifBlank { mimeOfAudio(fileName) }
        val bytes = runCatching { Base64.getDecoder().decode(b64) }
            .getOrElse { return UiOut.Json(resultJson(false, "文件内容不是合法 base64")) }
        if (bytes.size > MAX_BODY_BYTES) {
            return UiOut.Json(resultJson(false,
                "音频过大（${bytes.size / 1024 / 1024} MB），上限 ${MAX_BODY_BYTES / 1024 / 1024} MB"))
        }

        val boundary = "----aigw" + UUID.randomUUID().toString().replace("-", "")
        val parts = StringBuilder()
        fun field(name: String, value: String) {
            parts.append("--$boundary\r\n")
            parts.append("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
            parts.append(value).append("\r\n")
        }
        field("model", req.str("model").trim())
        req.str("language").trim().takeIf { it.isNotEmpty() }?.let { field("language", it) }
        req.str("prompt").trim().takeIf { it.isNotEmpty() }?.let { field("prompt", it) }
        parts.append("--$boundary\r\n")
        parts.append("Content-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n")
        parts.append("Content-Type: $mime\r\n\r\n")

        val head = parts.toString().toByteArray(StandardCharsets.UTF_8)
        val tail = "\r\n--$boundary--\r\n".toByteArray(StandardCharsets.UTF_8)
        val multipart = ByteArrayOutputStream(head.size + bytes.size + tail.size).apply {
            write(head); write(bytes); write(tail)
        }.toByteArray()

        val started = System.currentTimeMillis()
        val r = callGateway("/v1/audio/transcriptions", multipart,
            "multipart/form-data; boundary=$boundary")
        val out = JsonObject()
        out.addProperty("elapsedMs", System.currentTimeMillis() - started)
        if (!r.ok) {
            out.addProperty("ok", false)
            out.addProperty("message", gatewayErrorText(r.body))
            return UiOut.Json(out)
        }
        val text = runCatching {
            JsonParser.parseString(r.body).asJsonObject.get("text")?.asString.orEmpty()
        }.getOrDefault("")
        out.addProperty("ok", text.isNotEmpty())
        out.addProperty("text", text)
        out.addProperty("bytes", bytes.size)
        out.addProperty("fileName", fileName)
        if (text.isEmpty()) out.addProperty("message", "上游返回成功但文本为空")
        return UiOut.Json(out)
    }

    /**
     * 向本机网关发一次真实调用。
     *
     * 鉴权走与客户端相同的 Bearer：`allowNoKey` 开着就不带头，否则用全局 apiKey。
     * 自己带客户端密钥反而会把「界面能用、客户端不能用」的差异藏起来。
     *
     * 读超时给到 3 分钟：图片生成与长音频转写都可能慢，超时本身也是真实结果的一部分。
     */
    private fun callGateway(path: String, body: ByteArray, contentType: String): GwResponse {
        val cfg = engine.settings()
        val url = "http://127.0.0.1:${cfg.port}$path"
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 180_000
            conn.setRequestProperty("Content-Type", contentType)
            if (!cfg.allowNoKey && cfg.apiKey.isNotEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer ${cfg.apiKey}")
            }
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            GwResponse(code in 200..299, text)
        } catch (e: Exception) {
            GwResponse(false, """{"error":{"message":"连不上网关（${e.message}）：先确认网关已启动"}}""")
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /** 把网关的错误响应压成一句人话（客户端形状是 error.message）。 */
    private fun gatewayErrorText(body: String): String {
        val msg = runCatching {
            JsonParser.parseString(body).asJsonObject.getAsJsonObject("error")?.get("message")?.asString
        }.getOrNull()
        return msg?.takeIf { it.isNotBlank() } ?: body.take(300).ifBlank { "无响应内容" }
    }

    private fun mimeOfAudio(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "m4a" -> "audio/mp4"
        "ogg" -> "audio/ogg"
        "flac" -> "audio/flac"
        "webm" -> "audio/webm"
        else -> "application/octet-stream"
    }
}
