package dev.aigw.server

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Gson 取值助手。
 *
 * 为什么统一在顶层做：`UiRoutes` 里每个端点都要从请求体取十来个字段，
 * 原先把这些扩展函数写在 `UiApiServer` 类内部，导致它们与 HTTP 骨架混在一起，
 * 类长到 700 行时已经看不出哪些是协议、哪些是业务。
 *
 * 语义约定（与原实现完全一致，前端不需要改）：
 * - 取值失败一律回落默认值，不抛异常——前端漏传字段不该变成 500；
 * - 只有 [requireStr] 会抛 [UiException]（缺必填参数 → 400）。
 */
internal fun JsonObject.str(key: String): String =
    runCatching { get(key)?.takeIf { !it.isJsonNull }?.asString }.getOrNull() ?: ""

internal fun JsonObject.strOr(key: String, fallback: String): String =
    runCatching { get(key)?.takeIf { !it.isJsonNull }?.asString }.getOrNull() ?: fallback

/** 必填字符串：缺省或空串都算缺参数。 */
internal fun JsonObject.requireStr(key: String, label: String = key): String =
    str(key).ifBlank { throw UiException.badRequest("缺少参数 $label") }

internal fun JsonObject.int(key: String, fallback: Int): Int =
    runCatching { get(key)?.asInt }.getOrNull() ?: fallback

internal fun JsonObject.long(key: String, fallback: Long): Long =
    runCatching { get(key)?.asLong }.getOrNull() ?: fallback

internal fun JsonObject.double(key: String, fallback: Double): Double =
    runCatching { get(key)?.asDouble }.getOrNull() ?: fallback

internal fun JsonObject.bool(key: String): Boolean =
    runCatching { get(key)?.asBoolean }.getOrNull() ?: false

internal fun JsonObject.boolOr(key: String, fallback: Boolean): Boolean =
    runCatching { get(key)?.asBoolean }.getOrNull() ?: fallback

/** 取数组；缺字段或类型不对返回 null，调用方据此报「缺少 xxx」。 */
internal fun JsonObject.array(key: String): JsonArray? =
    runCatching { get(key)?.takeIf { it.isJsonArray }?.asJsonArray }.getOrNull()

/** 取子对象；缺字段或类型不对返回 null，调用方据此回落到当前值。 */
internal fun JsonObject.child(key: String): JsonObject? =
    runCatching { get(key)?.takeIf { it.isJsonObject }?.asJsonObject }.getOrNull()

/** 解析请求体；空串按空对象处理（POST 不带体是合法调用）。 */
internal fun parseJsonObject(raw: String): JsonObject =
    runCatching { JsonParser.parseString(raw.ifBlank { "{}" }).asJsonObject }
        .getOrElse { JsonObject() }

/** 成功响应。 */
internal fun okJson(): JsonObject = JsonObject().apply { addProperty("ok", true) }

/** 统一的小结果对象：`ok` + 一句给人看的话，可再挂附加字段。 */
internal fun resultJson(ok: Boolean, message: String = "", extra: JsonObject? = null): JsonObject =
    JsonObject().apply {
        addProperty("ok", ok)
        addProperty("message", message)
        extra?.entrySet()?.forEach { (k, v) -> add(k, v) }
    }
