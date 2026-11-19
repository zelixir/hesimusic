package com.zjr.hesimusic.debug

import org.json.JSONArray
import org.json.JSONObject

/** 业务错误 → HTTP 状态 + {"error": message}。 */
internal class HttpError(val status: Int, message: String) : Exception(message)

/**
 * 播放器控制尚未连接(懒建连未就绪/已断开)→ 503。
 *
 * reason 采用 DebugPlayerLink.REASON_* 口径,写进 503 响应体,
 * 避免"空 body 503"无法区分 controller 缺失、断连还是主线程读超时。
 */
internal class PlayerUnavailable(
    message: String = "player connecting",
    val reason: String = DebugPlayerLink.REASON_NOT_CONNECTED,
) : Exception(message)

/** 主线程调用超时(主线程被 UI/解码占住)→ 同样按 503 快速失败。 */
internal class PlayerTimeout(message: String = "player call timeout") : Exception(message)

/**
 * 把任意 Kotlin 值递归转换为 org.json 可接受的值。
 * 关键点:null 必须变成 JSONObject.NULL(直接 put null 会删键);NaN/Infinity 也归 null。
 */
internal fun toJson(value: Any?): Any = when (value) {
    null -> JSONObject.NULL
    is JSONObject, is JSONArray -> value
    is String, is Boolean, is Byte, is Short, is Int, is Long -> value
    is Float -> if (value.isFinite()) value.toDouble() else JSONObject.NULL
    is Double -> if (value.isFinite()) value else JSONObject.NULL
    is Number -> value
    is Map<*, *> -> JSONObject().also { obj ->
        value.forEach { (k, v) ->
            if (k != null) obj.put(k.toString(), toJson(v))
        }
    }
    is Iterable<*> -> JSONArray().also { arr -> value.forEach { arr.put(toJson(it)) } }
    is Array<*> -> JSONArray().also { arr -> value.forEach { arr.put(toJson(it)) } }
    is FloatArray -> JSONArray().also { arr -> value.forEach { arr.put(toJson(it)) } }
    is DoubleArray -> JSONArray().also { arr -> value.forEach { arr.put(toJson(it)) } }
    is IntArray -> JSONArray().also { arr -> value.forEach { arr.put(toJson(it)) } }
    is LongArray -> JSONArray().also { arr -> value.forEach { arr.put(toJson(it)) } }
    is BooleanArray -> JSONArray().also { arr -> value.forEach { arr.put(toJson(it)) } }
    is CharArray -> JSONArray().also { arr -> value.forEach { arr.put(toJson(it)) } }
    else -> value.toString()
}

/** 响应体序列化:根节点通常是 Map/List(JSONObject/JSONArray)。 */
internal fun serializeJson(value: Any?): String = when (val json = toJson(value)) {
    is JSONObject -> json.toString()
    is JSONArray -> json.toString()
    is String -> JSONObject.quote(json)
    else -> JSONObject().put("value", json).toString()
}

/** 错误响应体。 */
internal fun errorJson(message: String): String =
    JSONObject().put("error", message).toString()
