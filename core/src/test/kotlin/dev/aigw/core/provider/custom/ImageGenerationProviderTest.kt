package dev.aigw.core.provider.custom

import com.sun.net.httpserver.HttpServer
import dev.aigw.core.gateway.CustomProviderConfig
import dev.aigw.core.provider.ImageGenerationRequest
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.failureOrNull
import dev.aigw.core.provider.valueOrNull
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 自定义供应商出图：客户端原始 JSON 体必须转发到 `/images/generations`，只补 model。
 *
 * 三条必须守住的口径：
 * 1. 没填 `imageModels` 就**不支持**出图（不猜目录里的文本模型）；
 * 2. 客户端体里的 `response_format` / `quality` 不能被改写（中转站按它决定回 b64 还是外链）；
 * 3. 只回 `url` 的上游也要能解析出图，不能只认 `b64_json`。
 */
class ImageGenerationProviderTest {

    private class Captured {
        var path: String = ""
        var authorization: String = ""
        var contentType: String = ""
        var body: String = ""
    }

    private fun withUpstream(
        status: Int = 200,
        responseBody: String = """{"created":1700000000,"data":[{"b64_json":"AAAA"}]}""",
        block: (baseUrl: String, captured: Captured) -> Unit,
    ) {
        val captured = Captured()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            captured.path = exchange.requestURI.path
            captured.authorization = exchange.requestHeaders.getFirst("Authorization").orEmpty()
            captured.contentType = exchange.requestHeaders.getFirst("Content-Type").orEmpty()
            captured.body = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
            val bytes = responseBody.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1L else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}/v1", captured)
        } finally {
            server.stop(0)
        }
    }

    private fun provider(baseUrl: String, imageModels: List<String> = listOf("gpt-image-1")) =
        CustomProvider(
            CustomProviderConfig(
                key = "t",
                name = "测试",
                baseUrl = baseUrl,
                apiKeys = listOf("sk-1"),
                imageModels = imageModels,
            ),
        )

    private fun account() = ProviderAccount("custom:t", "u1", "测试", CustomProvider.secretOf("sk-1"))

    @Test
    fun `没有图像模型时不声明出图能力`() {
        val p = provider("https://api.example.com", imageModels = emptyList())
        assertFalse(p.supportsImageGeneration)
        val outcome = p.generateImage(account(), ImageGenerationRequest(model = "", prompt = "猫"))
        val failure = outcome.failureOrNull()
        assertNotNull(failure)
        // 400 而不是 501：是配置缺项，不是这家永远不支持（用户点一下就能修好）
        assertEquals(400, failure.status)
        assertTrue(failure.errorBody.contains("图像模型"), "提示必须指向该改的字段：${failure.errorBody}")
    }

    @Test
    fun `声明图像模型后请求打到 images-generations 且保留原始参数`() {
        withUpstream { baseUrl, captured ->
            val p = provider(baseUrl)
            assertTrue(p.supportsImageGeneration)
            val raw = """{"model":"dall-e-3","prompt":"一只猫","n":2,"size":"1024x1024","response_format":"url","quality":"hd"}"""
            val outcome = p.generateImage(
                account(),
                ImageGenerationRequest(model = "", prompt = "一只猫", rawBody = raw),
            )
            val result = outcome.valueOrNull()
            assertNotNull(result)
            assertEquals(1, result.images.size)
            assertEquals("AAAA", result.images.first().b64Json)

            assertEquals("/v1/images/generations", captured.path)
            assertEquals("Bearer sk-1", captured.authorization)
            // 原始体里的 model 优先于配置：这是上游真名，不能被配置里的第一个覆盖
            assertTrue(captured.body.contains("\"model\":\"dall-e-3\""), captured.body)
            // 这些参数网关一个都不许改写
            assertTrue(captured.body.contains("\"quality\":\"hd\""), captured.body)
            assertTrue(captured.body.contains("\"response_format\":\"url\""), captured.body)
            assertTrue(captured.body.contains("\"n\":2"), captured.body)
        }
    }

    @Test
    fun `客户端没给模型时用配置里第一个图像模型`() {
        withUpstream { baseUrl, captured ->
            val p = provider(baseUrl, imageModels = listOf("flux-pro", "flux-dev"))
            val outcome = p.generateImage(
                account(),
                ImageGenerationRequest(model = "", prompt = "山", rawBody = """{"prompt":"山"}""" ),
            )
            assertNotNull(outcome.valueOrNull())
            assertTrue(captured.body.contains("\"model\":\"flux-pro\""), captured.body)
        }
    }

    @Test
    fun `auto 视为没给模型`() {
        withUpstream { baseUrl, captured ->
            val p = provider(baseUrl, imageModels = listOf("imagen-3.0"))
            val outcome = p.generateImage(
                account(),
                ImageGenerationRequest(model = "auto", prompt = "海", rawBody = """{"model":"auto","prompt":"海"}""" ),
            )
            assertNotNull(outcome.valueOrNull())
            assertTrue(captured.body.contains("\"model\":\"imagen-3.0\""), captured.body)
            assertFalse(captured.body.contains("\"model\":\"auto\""), captured.body)
        }
    }

    @Test
    fun `只回 url 的上游也能解析`() {
        withUpstream(
            responseBody = """{"data":[{"url":"https://cdn.example.com/a.png","revised_prompt":"改了"}]}""",
        ) { baseUrl, _ ->
            val p = provider(baseUrl)
            val result = p.generateImage(account(), ImageGenerationRequest(model = "gpt-image-1", prompt = "图")).valueOrNull()
            assertNotNull(result)
            assertEquals("https://cdn.example.com/a.png", result.images.first().url)
            assertEquals("改了", result.images.first().revisedPrompt)
            assertTrue(result.images.first().isUsable)
        }
    }

    @Test
    fun `非图像响应要当失败而不是空成功`() {
        withUpstream(responseBody = """{"choices":[{"message":{"content":"你好"}}]}""") { baseUrl, _ ->
            val p = provider(baseUrl)
            val failure = p.generateImage(
                account(), ImageGenerationRequest(model = "gpt-image-1", prompt = "图"),
            ).failureOrNull()
            assertNotNull(failure)
            assertEquals(502, failure.status)
            assertTrue(failure.errorBody.contains("不是 OpenAI 图像格式"), failure.errorBody)
        }
    }

    @Test
    fun `上游报错原样带回`() {
        withUpstream(status = 400, responseBody = """{"error":{"message":"model not found"}}""") { baseUrl, _ ->
            val p = provider(baseUrl)
            val failure = p.generateImage(
                account(), ImageGenerationRequest(model = "bad", prompt = "图"),
            ).failureOrNull()
            assertNotNull(failure)
            assertEquals(400, failure.status)
            assertTrue(failure.errorBody.contains("model not found"), failure.errorBody)
        }
    }

    @Test
    fun `基础地址规范化会剥掉 images 尾巴`() {
        assertEquals(
            "https://api.example.com/v1/images/generations",
            CustomProvider.imageEndpointOf("https://api.example.com/v1/images/generations"),
        )
        assertEquals(
            "https://api.example.com/v1/images/generations",
            CustomProvider.imageEndpointOf("https://api.example.com"),
        )
        assertEquals(
            "https://api.example.com/api/v4/images/generations",
            CustomProvider.imageEndpointOf("https://api.example.com/api/v4"),
        )
    }

    @Test
    fun `配置往返保留图像模型`() {
        val config = CustomProviderConfig(
            key = "t", name = "测试", baseUrl = "https://api.example.com/v1",
            apiKeys = emptyList(), imageModels = listOf("gpt-image-1", "dall-e-3"),
        )
        val back = CustomProviderConfig.fromJson(config.toJson())
        assertNotNull(back)
        assertEquals(listOf("gpt-image-1", "dall-e-3"), back.imageModels)
        assertTrue(back.supportsImage)
    }

    @Test
    fun `老配置没有 imageModels 字段时读到空表`() {
        val legacy = CustomProviderConfig(
            key = "t", name = "测试", baseUrl = "https://api.example.com/v1", apiKeys = emptyList(),
        )
        val json = legacy.toJson()
        json.remove("imageModels")
        val back = CustomProviderConfig.fromJson(json)
        assertNotNull(back)
        assertNull(back.imageModels.firstOrNull())
        assertFalse(back.supportsImage)
    }
}
