package com.casthub.airplay.plist

import com.casthub.core.CastLogger
import com.dd.plist.BinaryPropertyListWriter
import com.dd.plist.NSArray
import com.dd.plist.NSData
import com.dd.plist.NSDictionary
import com.dd.plist.NSNumber
import com.dd.plist.NSObject
import com.dd.plist.NSString
import com.dd.plist.PropertyListParser

/**
 * Apple property list 的编解码 —— **薄封装 dd-plist**，不做任何格式解析。
 *
 * ── 为什么不再自己解析 ──────────────────────────────────────────────
 * 这里原本是一份手写的 bplist00 解析器（约 200 行）。它把 trailer 里
 * `numObjects / topObject / offsetTableOffset` 的偏移写成了 26/27/28/36/44
 * （正确是 6/7/8/16/24），一解析就数组越界 —— 表现是 iPhone 点了投屏没反应，
 * 日志里只有一条 `length=145; index=145`，很难往 plist 上想。
 *
 * plist 是公开且稳定的格式，社区有 MIT 许可的成熟实现（dd-plist，
 * 其它 AirPlay 开源接收端也用它）。自己解析一个成熟格式，等于把
 * "别人已经踩过的坑"重新踩一遍，且没有测试覆盖来兜底。
 *
 * 这里只保留两件事：NSObject ↔ Kotlin 普通类型的转换，以及解析失败时
 * 返回 null 而不是抛异常（协议层拿到 null 会回 400，不会让连接崩掉）。
 */
internal object Plist {

    /** 解析二进制或 XML plist。失败返回 null。 */
    fun parse(data: ByteArray): Any? {
        if (data.isEmpty()) return null
        return runCatching { fromNs(PropertyListParser.parse(data)) }
            .onFailure { CastLogger.w(TAG, "plist 解析失败：${it.message}") }
            .getOrNull()
    }

    /** 生成 XML plist（`text/x-apple-plist+xml`）。iOS 的 /playback-info 要这个格式。 */
    fun toXml(value: Map<String, Any?>): String = runCatching {
        toNs(value).toXMLPropertyList()
    }.getOrElse {
        CastLogger.w(TAG, "plist 生成失败：${it.message}")
        ""
    }

    /**
     * 生成二进制 plist（`application/x-apple-binary-plist`）。失败返回 null，
     * 由调用方决定退回 XML —— 这里不抛异常，避免把 /info 这类关键端点搞挂。
     *
     * 注意用 [BinaryPropertyListWriter.writeToArray]：dd-plist 1.30 的 NSObject
     * **没有**公开的 toBinaryPropertyList()（那是 String/File 那套重载的名字习惯），
     * 写成 toBinaryPropertyList() 会编译不过。
     */
    fun toBinary(value: Map<String, Any?>): ByteArray? = runCatching {
        BinaryPropertyListWriter.writeToArray(toNs(value))
    }.onFailure {
        CastLogger.w(TAG, "二进制 plist 生成失败：${it.message}")
    }.getOrNull()

    // ─────────────────── NSObject ↔ Kotlin ───────────────────

    private fun toNs(value: Any?): NSObject = when (value) {
        null -> NSString("")
        is NSObject -> value
        is String -> NSString(value)
        is Boolean -> NSNumber(value)
        is Int -> NSNumber(value.toLong())
        is Long -> NSNumber(value)
        is Float -> NSNumber(value.toDouble())
        is Double -> NSNumber(value)
        is ByteArray -> NSData(value)
        is Map<*, *> -> NSDictionary().apply {
            value.forEach { (k, v) -> put(k.toString(), toNs(v)) }
        }
        is List<*> -> NSArray(*value.map { toNs(it) }.toTypedArray())
        is IntArray -> NSArray(*value.map { NSNumber(it.toLong()) }.toTypedArray())
        else -> NSString(value.toString())
    }

    private fun fromNs(obj: NSObject?): Any? = when (obj) {
        null -> null
        is NSDictionary -> obj.allKeys().associateWith { fromNs(obj.objectForKey(it)) }
        is NSArray -> obj.array.map { fromNs(it) }
        is NSData -> obj.bytes()
        is NSNumber -> when {
            obj.isBoolean -> obj.boolValue()
            obj.isInteger -> obj.longValue()
            else -> obj.doubleValue()
        }
        is NSString -> obj.content
        else -> obj.toString()
    }

    private const val TAG = "Plist"
}
