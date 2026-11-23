package dev.aigw.core.pool

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.util.intOrNull
import dev.aigw.core.util.stringOrNull

/** 账号导出文件的格式标识：只在「导入」时用来挡住不相干的 JSON。 */
const val ACCOUNT_TRANSFER_FORMAT = "aigw-accounts"

/** 解析出来的一份账号导出内容。 */
data class AccountTransferEnvelope(
    val version: Int,
    val exportedAt: Long,
    val accounts: List<ProviderAccount>,
    /**
     * 账号所属的自定义供应商定义（只有 `custom:*` 才有）。
     *
     * v1 文件没有这一段，读出来是空表——老备份照样能导，只是那些 `custom:*`
     * 账号在目标机上仍会「未识别」。所以这里是**向后兼容的空缺省**，不是必填。
     */
    val providers: List<TransferProvider> = emptyList(),
)

/**
 * 自定义供应商的可搬运定义。
 *
 * 只带「重建一个能用的供应商」所必需的东西：短名、显示名、接口地址、
 * 协议、模型列表（含出图模型）。
 *
 * **不带 `apiKeys`**。理由有两层：
 * 1. 明文 API Key 已经在账号条目的 `secret` 里（每个 key 一个账号），
 *    再抄一份等于同一份密钥在包里出现两次。
 * 2. `GatewayEngine.syncCustomAccounts(config)` 一旦看到 `apiKeys` 非空，
 *    就会按它增删账号池——跨机导入时那会把「用户自己另加的 key」当成多余项删掉。
 *    带空 apiKeys 则 `syncCustomAccounts` 直接 early-return，账号池只受本次导入影响。
 */
data class TransferProvider(
    val providerId: String,
    val key: String,
    val name: String,
    val baseUrl: String,
    val protocol: String,
    val models: List<String>,
    val imageModels: List<String>,
)

/**
 * 账号导入导出的信封编解码。
 *
 * 只做「一段 JSON ↔ 一批账号」的纯转换，不认识账号池，也不碰存储；
 * 粒度（导出/导入哪些账号）由调用方先选好再传进来。
 *
 * 凭证是明文：格式里不做加密，靠界面明确警告「含 token，勿外传」。
 */
object AccountTransfer {

    /** 当前版本；导入时高于它的文件一律拒绝（宁可报错也不猜字段）。 */
    const val VERSION = 1

    /**
     * 编码。**版本号仍是 1**——`providers` 是新增的可选段，
     * 老版本读它会被忽略（decode 只挑它认识的键），所以不必升版本把老客户端挡在外面。
     * 反过来：老文件没有这一段，[decode] 会给出空表，导入行为与升级前一致。
     */
    fun encode(
        accounts: List<ProviderAccount>,
        exportedAt: Long,
        providers: List<TransferProvider> = emptyList(),
    ): String {
        val array = JsonArray()
        for (account in accounts) {
            array.add(JsonObject().apply {
                addProperty("providerId", account.providerId)
                addProperty("uid", account.uid)
                addProperty("nickname", account.nickname)
                addProperty("secret", account.secret)
            })
        }
        return JsonObject().apply {
            addProperty("format", ACCOUNT_TRANSFER_FORMAT)
            addProperty("version", VERSION)
            addProperty("exportedAt", exportedAt)
            add("accounts", array)
            if (providers.isNotEmpty()) {
                add("providers", JsonArray().apply {
                    providers.forEach { p ->
                        add(JsonObject().apply {
                            addProperty("providerId", p.providerId)
                            addProperty("key", p.key)
                            addProperty("name", p.name)
                            addProperty("baseUrl", p.baseUrl)
                            addProperty("protocol", p.protocol)
                            add("models", JsonArray().apply { p.models.forEach { add(it) } })
                            add("imageModels", JsonArray().apply { p.imageModels.forEach { add(it) } })
                        })
                    }
                })
            }
        }.toString()
    }

    /**
     * 解析导入内容。
     *
     * 抛出的 [IllegalArgumentException] 消息是给用户看的原话，界面可以直接显示。
     */
    fun decode(json: String): AccountTransferEnvelope {
        val text = json.trim()
        if (text.isEmpty()) throw IllegalArgumentException("内容为空，先粘贴导出内容或选择一个文件")
        val obj = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
            ?: throw IllegalArgumentException("不是合法的 JSON")
        if (obj.stringOrNull("format") != ACCOUNT_TRANSFER_FORMAT) {
            throw IllegalArgumentException("不是本应用导出的账号文件")
        }
        val version = obj.intOrNull("version") ?: 0
        if (version <= 0 || version > VERSION) {
            throw IllegalArgumentException("文件版本 $version 不支持（当前支持到 $VERSION）")
        }
        val array = obj.get("accounts")
        if (array == null || !array.isJsonArray) throw IllegalArgumentException("文件里没有账号列表")

        val accounts = ArrayList<ProviderAccount>(array.asJsonArray.size())
        for (element in array.asJsonArray) {
            val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val providerId = item.stringOrNull("providerId").orEmpty()
            val uid = item.stringOrNull("uid").orEmpty()
            // 凭证缺失的条目直接丢掉，不要用空串造出一个「能登录但必然失败」的账号
            val secret = item.stringOrNull("secret").orEmpty()
            if (providerId.isEmpty() || uid.isEmpty() || secret.isEmpty()) continue
            accounts.add(
                ProviderAccount(
                    providerId = providerId,
                    uid = uid,
                    nickname = item.stringOrNull("nickname").orEmpty(),
                    secret = secret,
                ),
            )
        }
        if (accounts.isEmpty()) throw IllegalArgumentException("文件里没有可用的账号（凭证为空或字段缺失）")
        return AccountTransferEnvelope(
            version = version,
            exportedAt = obj.get("exportedAt")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
            accounts = accounts,
            providers = decodeProviders(obj),
        )
    }

    /**
     * 解析 `providers` 段。缺失或格式不对一律给空表，**不抛异常**——
     * 老备份没有这一段是正常情况（v1 文件本来就只有 accounts），
     * 为一段可选内容把整份备份判成非法，等于让老用户的备份全部作废。
     */
    private fun decodeProviders(root: JsonObject): List<TransferProvider> {
        val arr = root.get("providers")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        val out = ArrayList<TransferProvider>(arr.size())
        for (element in arr) {
            val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val providerId = item.stringOrNull("providerId").orEmpty()
            val key = item.stringOrNull("key").orEmpty()
            val baseUrl = item.stringOrNull("baseUrl").orEmpty()
            // 三条缺一条就没法重建一个能用的供应商：短名拼 id、baseUrl 决定打到哪。
            if (providerId.isEmpty() || key.isEmpty() || baseUrl.isEmpty()) continue
            out.add(
                TransferProvider(
                    providerId = providerId,
                    key = key,
                    // 显示名缺失时退回短名，不要留空——空名在供应商页是一行空白，用户认不出来
                    name = item.stringOrNull("name").orEmpty().ifEmpty { key },
                    baseUrl = baseUrl,
                    protocol = item.stringOrNull("protocol").orEmpty().ifEmpty { "openai" },
                    models = strings(item.get("models")),
                    imageModels = strings(item.get("imageModels")),
                ),
            )
        }
        return out
    }

    /** JSON 字符串数组 → List；非数组或元素不是字符串一律跳过。 */
    private fun strings(node: com.google.gson.JsonElement?): List<String> {
        val arr = node?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            runCatching { el.asString }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        }
    }
}

/** 一次导入的逐条结果，供界面汇总展示。 */
data class AccountImportResult(
    val added: List<String>,
    val updated: List<String>,
    val skipped: List<String>,
    /** providerId 未注册且包里也没带定义——真正导不进去的那些。 */
    val unknownProviders: List<String>,
    /** 本次导入顺带补齐的自定义供应商（`custom:*`）。 */
    val adoptedProviders: List<String> = emptyList(),
) {
    val total: Int get() = added.size + updated.size + skipped.size + unknownProviders.size
}
