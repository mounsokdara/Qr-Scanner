package com.localscanner.qr

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

enum class ScanKind { URL, WIFI, CONTACT, EVENT, EMAIL, SMS, PHONE, GEO, TEXT }

data class VCardInfo(
    val name: String,
    val organization: String,
    val phones: List<String>,
    val emails: List<String>,
    val address: String,
    val website: String,
    val note: String
)

data class EventInfo(
    val title: String,
    val location: String,
    val description: String,
    val startMillis: Long?,
    val endMillis: Long?,
    val allDay: Boolean
)

object Parsers {
    fun kindOf(raw: String): ScanKind {
        val t = raw.trim()
        fun starts(prefix: String) = t.startsWith(prefix, ignoreCase = true)
        return when {
            starts("WIFI:") -> ScanKind.WIFI
            starts("BEGIN:VCARD") -> ScanKind.CONTACT
            starts("BEGIN:VCALENDAR") || starts("BEGIN:VEVENT") -> ScanKind.EVENT
            starts("mailto:") -> ScanKind.EMAIL
            starts("smsto:") || starts("sms:") -> ScanKind.SMS
            starts("tel:") -> ScanKind.PHONE
            starts("geo:") -> ScanKind.GEO
            starts("http://") || starts("https://") || starts("www.") -> ScanKind.URL
            else -> ScanKind.TEXT
        }
    }

    fun detail(raw: String, maxLen: Int = 28): String {
        val t = raw.trim()
        val value = when (kindOf(t)) {
            ScanKind.URL -> {
                val full = if (t.startsWith("www.", ignoreCase = true)) "https://$t" else t
                try {
                    java.net.URI(full).host?.removePrefix("www.") ?: t
                } catch (_: Exception) {
                    t
                }
            }
            ScanKind.WIFI -> {
                val match = Regex("""(?:^|;)S:((?:\\.|[^;\\])*)""").find(t.substring(5))
                match?.groupValues?.get(1)?.replace(Regex("""\\(.)"""), "$1").orEmpty()
            }
            ScanKind.CONTACT -> parseVCard(t)?.name.orEmpty()
            ScanKind.EVENT -> parseVEvent(t)?.title.orEmpty()
            ScanKind.EMAIL -> t.substringAfter(':').substringBefore('?')
            ScanKind.SMS -> t.substringAfter(':').substringBefore(':').substringBefore('?')
            ScanKind.PHONE -> t.substringAfter(':')
            ScanKind.GEO -> t.substringAfter(':').substringBefore('?')
            ScanKind.TEXT -> t
        }
        val oneLine = value.replace(Regex("\\s+"), " ").trim()
        return if (oneLine.length > maxLen) oneLine.take(maxLen - 1).trimEnd() + "…" else oneLine
    }

    fun parseVCard(raw: String): VCardInfo? {
        if (!raw.trim().startsWith("BEGIN:VCARD", ignoreCase = true)) return null
        var fn = ""
        var structured = ""
        var org = ""
        var address = ""
        var url = ""
        var note = ""
        val phones = ArrayList<String>()
        val emails = ArrayList<String>()
        for (line in unfold(raw)) {
            val prop = property(line) ?: continue
            when (prop.first) {
                "FN" -> if (fn.isEmpty()) fn = unescape(prop.second).trim()
                "N" -> if (structured.isEmpty()) {
                    val parts = splitUnescaped(prop.second, ';').map { unescape(it).trim() }
                    val family = parts.getOrElse(0) { "" }
                    val given = parts.getOrElse(1) { "" }
                    structured = listOf(given, family).filter { it.isNotEmpty() }.joinToString(" ")
                }
                "ORG" -> if (org.isEmpty()) {
                    org = splitUnescaped(prop.second, ';').map { unescape(it).trim() }
                        .filter { it.isNotEmpty() }.joinToString(", ")
                }
                "TEL" -> unescape(prop.second).trim().takeIf { it.isNotEmpty() }?.let { phones.add(it) }
                "EMAIL" -> unescape(prop.second).trim().takeIf { it.isNotEmpty() }?.let { emails.add(it) }
                "ADR" -> if (address.isEmpty()) {
                    address = splitUnescaped(prop.second, ';').map { unescape(it).trim() }
                        .filter { it.isNotEmpty() }.joinToString(", ")
                }
                "URL" -> if (url.isEmpty()) url = prop.second.trim()
                "NOTE" -> if (note.isEmpty()) note = unescape(prop.second, keepNewlines = true).trim()
            }
        }
        val name = fn.ifEmpty { structured }
        return VCardInfo(name, org, phones, emails, address, url, note)
    }

    fun parseVEvent(raw: String, zone: TimeZone = TimeZone.getDefault()): EventInfo? {
        val text = raw.trim()
        if (!text.startsWith("BEGIN:VCALENDAR", ignoreCase = true) &&
            !text.startsWith("BEGIN:VEVENT", ignoreCase = true)
        ) {
            return null
        }
        var inEvent = !text.contains("BEGIN:VEVENT", ignoreCase = true)
        var title = ""
        var location = ""
        var description = ""
        var start: Long? = null
        var end: Long? = null
        var allDay = false
        for (line in unfold(text)) {
            val upper = line.trim().uppercase(Locale.US)
            if (upper == "BEGIN:VEVENT") {
                inEvent = true
                continue
            }
            if (upper == "END:VEVENT") break
            if (!inEvent) continue
            val prop = property(line) ?: continue
            when (prop.first) {
                "SUMMARY" -> title = unescape(prop.second).trim()
                "LOCATION" -> location = unescape(prop.second).trim()
                "DESCRIPTION" -> description = unescape(prop.second, keepNewlines = true).trim()
                "DTSTART" -> parseIcsDate(prop.second, zone)?.let {
                    start = it.first
                    allDay = it.second
                }
                "DTEND" -> parseIcsDate(prop.second, zone)?.let { end = it.first }
            }
        }
        if (title.isEmpty() && start == null) return null
        return EventInfo(title, location, description, start, end, allDay)
    }

    fun parseIcsDate(value: String, zone: TimeZone = TimeZone.getDefault()): Pair<Long, Boolean>? {
        val v = value.trim()
        return try {
            when {
                v.length == 8 -> {
                    val f = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
                        isLenient = false
                        timeZone = zone
                    }
                    f.parse(v)?.let { it.time to true }
                }
                v.length == 16 && v.endsWith("Z", ignoreCase = true) -> {
                    val f = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).apply {
                        isLenient = false
                        timeZone = TimeZone.getTimeZone("UTC")
                    }
                    f.parse(v.dropLast(1))?.let { it.time to false }
                }
                v.length == 15 -> {
                    val f = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).apply {
                        isLenient = false
                        timeZone = zone
                    }
                    f.parse(v)?.let { it.time to false }
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun unfold(raw: String): List<String> {
        val lines = ArrayList<String>()
        for (line in raw.split(Regex("\r\n|\n|\r"))) {
            if ((line.startsWith(" ") || line.startsWith("\t")) && lines.isNotEmpty()) {
                lines[lines.size - 1] = lines.last() + line.substring(1)
            } else {
                lines.add(line)
            }
        }
        return lines
    }

    private fun property(line: String): Pair<String, String>? {
        val colon = line.indexOf(':')
        if (colon <= 0) return null
        var key = line.substring(0, colon).substringBefore(';')
        key = key.substringAfterLast('.')
        return key.trim().uppercase(Locale.US) to line.substring(colon + 1)
    }

    private fun splitUnescaped(value: String, separator: Char): List<String> {
        val parts = ArrayList<String>()
        val current = StringBuilder()
        var escaped = false
        for (ch in value) {
            when {
                escaped -> {
                    current.append('\\').append(ch)
                    escaped = false
                }
                ch == '\\' -> escaped = true
                ch == separator -> {
                    parts.add(current.toString())
                    current.setLength(0)
                }
                else -> current.append(ch)
            }
        }
        if (escaped) current.append('\\')
        parts.add(current.toString())
        return parts
    }

    private fun unescape(value: String, keepNewlines: Boolean = false): String {
        val out = StringBuilder()
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (ch == '\\' && i + 1 < value.length) {
                when (val next = value[i + 1]) {
                    'n', 'N' -> out.append(if (keepNewlines) '\n' else ' ')
                    else -> out.append(next)
                }
                i += 2
            } else {
                out.append(ch)
                i++
            }
        }
        return out.toString()
    }
}
