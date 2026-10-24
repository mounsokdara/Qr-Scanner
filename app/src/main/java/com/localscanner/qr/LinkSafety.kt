package com.localscanner.qr

import java.net.URI
import java.util.Locale

enum class LinkRisk {
    INSECURE,
    IP_HOST,
    PUNYCODE,
    NON_ASCII_HOST,
    SHORTENER,
    CREDENTIALS
}

object LinkSafety {
    private val SHORTENERS = setOf(
        "bit.ly", "tinyurl.com", "t.co", "goo.gl", "ow.ly", "is.gd", "buff.ly",
        "rebrand.ly", "cutt.ly", "shorturl.at", "tiny.cc", "rb.gy", "t.ly"
    )
    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    private val DECIMAL_HOST = Regex("^\\d{6,}$")

    fun check(raw: String): Set<LinkRisk> {
        var text = raw.trim()
        if (text.startsWith("www.", ignoreCase = true)) text = "https://$text"
        val uri = try {
            URI(text)
        } catch (_: Exception) {
            return emptySet()
        }
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return emptySet()
        if (scheme != "http" && scheme != "https") return emptySet()
        val authority = uri.rawAuthority ?: return emptySet()

        val risks = LinkedHashSet<LinkRisk>()
        if (scheme == "http") risks.add(LinkRisk.INSECURE)

        val at = authority.lastIndexOf('@')
        if (at >= 0) risks.add(LinkRisk.CREDENTIALS)
        val hostPort = if (at >= 0) authority.substring(at + 1) else authority
        val host = if (hostPort.startsWith("[")) {
            hostPort.substringBefore("]") + "]"
        } else {
            hostPort.substringBefore(":")
        }.lowercase(Locale.US)

        if (host.startsWith("[") || IPV4.matches(host) || DECIMAL_HOST.matches(host)) {
            risks.add(LinkRisk.IP_HOST)
        }
        if (host.split('.').any { it.startsWith("xn--") }) risks.add(LinkRisk.PUNYCODE)
        if (host.any { it.code > 127 }) risks.add(LinkRisk.NON_ASCII_HOST)
        if (SHORTENERS.any { host == it || host.endsWith(".$it") }) risks.add(LinkRisk.SHORTENER)
        return risks
    }
}
