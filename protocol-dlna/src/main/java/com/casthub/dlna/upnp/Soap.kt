package com.casthub.dlna.upnp

/**
 * UPnP 的 SOAP 报文编解码。
 *
 * DLNA 的控制面全部走 SOAP over HTTP：
 * 请求体是 `<u:ActionName xmlns:u="serviceType">` 包裹的入参，
 * 响应体是 `ActionNameResponse` 包裹的出参。
 */
object Soap {

    private val ACTION_NAME = Regex("#(\\w+)\"")
    private val SERVICE_TYPE = Regex("\"(urn:schemas-upnp-org:service:[\\w]+:\\d+)#")
    private val TAGS = Regex("<(\\w+)>(.*?)</\\1>", RegexOption.DOT_MATCHES_ALL)

    /** 从 `SOAPACTION: "urn:...:service:AVTransport:1#Play"` 中取出动作名。 */
    fun parseActionName(soapActionHeader: String?): String? =
        soapActionHeader?.let { ACTION_NAME.find(it)?.groupValues?.getOrNull(1) }

    /** 取出动作所属的服务类型。 */
    fun parseServiceType(soapActionHeader: String?): String? =
        soapActionHeader?.let { SERVICE_TYPE.find(it)?.groupValues?.getOrNull(1) }

    /**
     * 从服务类型 URN 中取出服务名。
     *
     * `urn:schemas-upnp-org:service:AVTransport:1` -> `AVTransport`。
     *
     * ⚠️ 用**固定的下标 3**，不是 `substringAfterLast(':')`（那是版本号 `1`）也不是
     * `split(':').getOrNull(4)`（同样是版本号）—— 曾写错成后者，日志于是打成
     * `SOAP 1:Play`，排查时完全看不出是哪个服务。
     */
    fun serviceNameOf(serviceType: String?): String? =
        serviceType?.split(':')?.getOrNull(3)?.takeIf { it.isNotBlank() }

    /** 解析 SOAP Body 中的入参。值会被反转义回原始文本。 */
    fun parseArguments(body: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        TAGS.findAll(body).forEach { match ->
            val key = match.groupValues[1]
            // 跳过 XML 声明与命名空间前缀节点
            if (key.equals("xml", ignoreCase = true)) return@forEach
            result.putIfAbsent(key, unescape(match.groupValues[2]))
        }
        return result
    }

    /** 构造动作响应。出参文本会被转义（其中 DIDL-Lite 作为字符串传出）。 */
    fun response(serviceType: String, action: String, outputs: Map<String, String>): String {
        val payload = outputs.entries.joinToString(separator = "") { (key, value) ->
            "<$key>${escape(value)}</$key>"
        }
        return """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<u:${action}Response xmlns:u="$serviceType">
$payload
</u:${action}Response>
</s:Body>
</s:Envelope>"""
    }

    /** 构造 SOAP Fault（用于不支持的动作或不合法参数）。 */
    fun fault(code: Int, description: String): String = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<s:Fault>
<faultcode>s:Client</faultcode>
<faultstring>UPnPError</faultstring>
<detail>
<UPnPError xmlns="urn:schemas-upnp-org:control-1-0">
<errorCode>$code</errorCode>
<errorDescription>${escape(description)}</errorDescription>
</UPnPError>
</detail>
</s:Fault>
</s:Body>
</s:Envelope>"""

    fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    fun unescape(value: String): String = value
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}
