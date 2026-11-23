package dev.aigw.server

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * 账号信封的压缩层。
 *
 * ## 这里**不裁剪任何字段**，原因是被实测否掉的
 *
 * 第一版试着砍掉 `accessToken`（理由看起来成立：refreshToken 能换出新的，
 * 实测 codebuddy 的 access 有效期 430h、refresh 1150h）。结果一导入就废：
 *
 * - `TraeAccount.parse`：`if (accessToken.isEmpty()) throw IllegalArgumentException("凭证缺少 accessToken")`
 *   —— 硬抛异常，整条账号解析失败
 * - `CodeBuddyProvider.parse`：`credentialOf(obj).takeIf { it.accessToken.isNotEmpty() }`
 *   —— 返回 null，上层报「凭证无法解析」
 *
 * 也就是说**所有供应商都把 accessToken 当必填字段**，没有「空着就先去刷新」的分支。
 * 砍掉它，包是小了 4000 B，但导入后的账号一个都用不了。
 * 体积优化的前提是「导入后直接能用」，前提不成立就不叫优化。
 *
 * 能安全砍的只有元数据（`exportedAt` 这类），省不到 100 字节，不值得为它加复杂度。
 *
 * ## 所以体积问题只靠压缩解决
 *
 * 信封里全是重复结构（13 个账号的字段名、JWT 的 base64 公共段），deflate 吃掉六成：
 * 19.2 KB → 7.2 KB。lvl 6 与 lvl 9 只差 4 字节，用默认级别，不为 4 字节加复杂度。
 *
 * 7.2 KB / 13 账号 ≈ 550 B 每账号，剩下的大头是 JWT 本身（accessToken + refreshToken
 * 原始各 1.5 KB），这已经是不能再压的部分。
 *
 * ## 兼容
 *
 * 老的 `.json` 明文备份照样能读（[decode] 看魔数决定要不要解压）。
 */
internal object AccountPack {

    private const val GZIP_MAGIC_1 = 0x1f.toByte()
    private const val GZIP_MAGIC_2 = 0x8b.toByte()

    /**
     * 标记用：给信封加一个 `packed` 字段，说明这份是压缩存储的。
     *
     * 纯元数据，不解压也能知道；`AccountTransfer.decode` 会忽略未知字段。
     * 不加任何字段裁剪。
     */
    fun mark(envelopeJson: String): String {
        val root = runCatching {
            JsonParser.parseString(envelopeJson).asJsonObject
        }.getOrNull() ?: return envelopeJson
        root.addProperty("packed", true)
        return root.toString()
    }

    // ---------------------------------------------------------------- 压缩

    /** gzip 压缩。已经带 gzip 魔数的原样返回（不重复压）。 */
    fun encode(text: String): ByteArray {
        val raw = text.toByteArray(Charsets.UTF_8)
        if (raw.size >= 2 && raw[0] == GZIP_MAGIC_1 && raw[1] == GZIP_MAGIC_2) return raw
        val out = ByteArrayOutputStream(raw.size / 2 + 64)
        GZIPOutputStream(out).use { it.write(raw) }
        return out.toByteArray()
    }

    /**
     * 解压 + 还原成文本。看魔数决定要不要解——老的 `.json` 明文直接当文本读。
     *
     * 解压失败原样返回原文：宁可让后续的 JSON 解析报「不是合法 JSON」，
     * 也不要在这里把「文件坏了」伪装成「格式不支持」。
     */
    fun decode(bytes: ByteArray): String {
        if (bytes.size < 2 || bytes[0] != GZIP_MAGIC_1 || bytes[1] != GZIP_MAGIC_2) {
            return String(bytes, Charsets.UTF_8)
        }
        return runCatching {
            GZIPInputStream(ByteArrayInputStream(bytes)).use { ins ->
                String(ins.readBytes(), Charsets.UTF_8)
            }
        }.getOrElse { String(bytes, Charsets.UTF_8) }
    }

    /** 文件名后缀判定：`auto-x.json.gz` 与老 `auto-x.json` 都认。 */
    fun isAccountFile(name: String): Boolean =
        name.endsWith(".json.gz") || name.endsWith(".json")
}
