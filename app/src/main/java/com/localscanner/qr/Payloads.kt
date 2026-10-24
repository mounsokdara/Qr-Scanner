package com.localscanner.qr

import android.net.Uri
import com.google.zxing.BarcodeFormat
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

data class BuiltCode(val text: String, val format: BarcodeFormat)

data class WifiInfo(
    val type: String,
    val ssid: String,
    val password: String,
    val hidden: Boolean
)

object Payloads {
    val COUNTRIES: List<Pair<String, String>> = listOf(
        "US" to "United States",
        "GB" to "United Kingdom",
        "CA" to "Canada",
        "AU" to "Australia",
        "DE" to "Germany",
        "FR" to "France",
        "ES" to "Spain",
        "IT" to "Italy",
        "NL" to "Netherlands",
        "SE" to "Sweden",
        "BR" to "Brazil",
        "MX" to "Mexico",
        "IN" to "India",
        "JP" to "Japan",
        "KR" to "South Korea",
        "CN" to "China",
        "ID" to "Indonesia",
        "TH" to "Thailand",
        "VN" to "Vietnam",
        "RU" to "Russia"
    )

    fun checkDigit(data: String): Char {
        var sum = 0
        data.reversed().forEachIndexed { index, c ->
            val digit = c - '0'
            sum += digit * if (index % 2 == 0) 3 else 1
        }
        val mod = sum % 10
        val check = if (mod == 0) 0 else 10 - mod
        return '0' + check
    }

    fun normalizeGtin(raw: String, bodyLength: Int): String? {
        val digits = raw.filter { !it.isWhitespace() }
        if (digits.isEmpty() || digits.any { !it.isDigit() }) return null
        return when (digits.length) {
            bodyLength -> digits + checkDigit(digits)
            bodyLength + 1 -> {
                val body = digits.dropLast(1)
                if (checkDigit(body) == digits.last()) digits else null
            }
            else -> null
        }
    }

    fun build(kind: CreateKind, fields: Map<String, String>): BuiltCode? {
        return when (kind) {
            CreateKind.CLIPBOARD, CreateKind.TEXT -> textPayload(fields["v1"].orEmpty())
            CreateKind.URL -> urlPayload(fields["v1"].orEmpty())
            CreateKind.CONTACT -> contactPayload(fields)
            CreateKind.EMAIL -> emailPayload(fields)
            CreateKind.SMS -> smsPayload(fields)
            CreateKind.GEO -> geoPayload(fields)
            CreateKind.PHONE -> phonePayload(fields["v1"].orEmpty())
            CreateKind.CALENDAR -> calendarPayload(fields)
            CreateKind.WIFI -> wifiPayload(fields)
            CreateKind.EAN_8 -> gtinPayload(fields["v1"].orEmpty(), 7, BarcodeFormat.EAN_8)
            CreateKind.EAN_13 -> gtinPayload(fields["v1"].orEmpty(), 12, BarcodeFormat.EAN_13)
            CreateKind.UPC_A -> gtinPayload(fields["v1"].orEmpty(), 11, BarcodeFormat.UPC_A)
            CreateKind.UPC_E -> upcEPayload(fields["v1"].orEmpty())
            CreateKind.CODE_39 -> symbolPayload(fields["v1"].orEmpty(), BarcodeFormat.CODE_39)
            CreateKind.CODE_93 -> symbolPayload(fields["v1"].orEmpty(), BarcodeFormat.CODE_93)
            CreateKind.CODE_128 -> symbolPayload(fields["v1"].orEmpty(), BarcodeFormat.CODE_128)
            CreateKind.ITF -> itfPayload(fields["v1"].orEmpty())
            CreateKind.CODABAR -> symbolPayload(fields["v1"].orEmpty(), BarcodeFormat.CODABAR)
            CreateKind.DATA_MATRIX -> symbolPayload(fields["v1"].orEmpty(), BarcodeFormat.DATA_MATRIX)
            CreateKind.PDF_417 -> symbolPayload(fields["v1"].orEmpty(), BarcodeFormat.PDF_417)
            CreateKind.AZTEC -> symbolPayload(fields["v1"].orEmpty(), BarcodeFormat.AZTEC)
        }
    }

    fun vCard(
        name: String,
        organization: String,
        phone: String,
        email: String,
        address: String,
        website: String,
        note: String
    ): String? {
        if (listOf(name, organization, phone, email, address, website, note).all { it.isBlank() }) {
            return null
        }
        return buildString {
            append("BEGIN:VCARD\n")
            append("VERSION:3.0\n")
            val (given, family) = splitName(name)
            append("N:").append(escapeVcard(family)).append(';').append(escapeVcard(given)).append(";;;\n")
            append("FN:").append(escapeVcard(name)).append('\n')
            if (organization.isNotBlank()) append("ORG:").append(escapeVcard(organization)).append('\n')
            if (phone.isNotBlank()) append("TEL:").append(escapeVcard(phone)).append('\n')
            if (email.isNotBlank()) append("EMAIL:").append(escapeVcard(email)).append('\n')
            if (address.isNotBlank()) append("ADR:;;").append(escapeVcard(address)).append(";;;;\n")
            if (website.isNotBlank()) append("URL:").append(website.trim().replace(Regex("\\s+"), "")).append('\n')
            if (note.isNotBlank()) append("NOTE:").append(escapeVcard(note)).append('\n')
            append("END:VCARD")
        }
    }

    fun isHttpUrl(text: String): Boolean {
        val scheme = Uri.parse(text.trim()).scheme?.lowercase(Locale.US) ?: return false
        return scheme == "http" || scheme == "https"
    }

    fun actionLabelIsOpen(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.startsWith("WIFI:", ignoreCase = true)) return true
        if (trimmed.startsWith("www.", ignoreCase = true)) return true
        if (isHttpUrl(trimmed)) return true
        val scheme = Uri.parse(trimmed).scheme?.lowercase(Locale.US) ?: return false
        return scheme in OPEN_SCHEMES
    }

    fun customActionUrl(template: String, code: String): String? {
        val trimmed = template.trim()
        if (trimmed.isEmpty()) return null
        val encoded = URLEncoder.encode(code, Charsets.UTF_8.name())
        val url = when {
            trimmed.contains("{code}") -> trimmed.replace("{code}", encoded)
            trimmed.contains("%s") -> trimmed.replace("%s", encoded)
            else -> trimmed + encoded
        }
        val scheme = Uri.parse(url).scheme?.lowercase(Locale.US) ?: return null
        if (scheme != "http" && scheme != "https") return null
        return url
    }

    fun searchUrl(query: String, engine: String, country: String): String? {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val cc = country.lowercase(Locale.US).ifBlank { "us" }
        return when (engine) {
            "google" -> "https://www.google.com/search?q=$encoded&gl=$cc"
            "bing" -> "https://www.bing.com/search?q=$encoded&cc=$cc"
            "duckduckgo" -> "https://duckduckgo.com/?q=$encoded&kl=$cc-en"
            else -> null
        }
    }

    fun parseWifi(raw: String): WifiInfo? {
        val text = raw.trim()
        if (!text.startsWith("WIFI:", ignoreCase = true)) return null
        var body = text.substring(5)
        if (body.endsWith(";;")) {
            body = body.dropLast(2)
        } else if (body.endsWith(";")) {
            body = body.dropLast(1)
        }
        val parts = ArrayList<String>()
        val current = StringBuilder()
        var escaped = false
        for (ch in body) {
            if (escaped) {
                current.append(ch)
                escaped = false
            } else if (ch == '\\') {
                escaped = true
            } else if (ch == ';') {
                parts.add(current.toString())
                current.setLength(0)
            } else {
                current.append(ch)
            }
        }
        if (current.isNotEmpty()) parts.add(current.toString())
        var type = ""
        var ssid = ""
        var password = ""
        var hidden = false
        for (part in parts) {
            val index = part.indexOf(':')
            if (index <= 0) continue
            val key = part.substring(0, index)
            val value = part.substring(index + 1)
            when (key) {
                "T" -> type = value
                "S" -> ssid = value
                "P" -> password = value
                "H" -> hidden = value.equals("true", ignoreCase = true)
            }
        }
        return WifiInfo(type, ssid, password, hidden)
    }

    fun defaultTitle(text: String, format: String): String {
        val trimmed = text.trim()
        val wifi = parseWifi(trimmed)
        if (wifi != null) {
            return if (wifi.ssid.isNotBlank()) wifi.ssid else "Wi-Fi"
        }
        if (trimmed.startsWith("BEGIN:VCARD", ignoreCase = true)) {
            val name = Regex("(?m)^FN:(.*)$").find(trimmed)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            return if (name.isNotEmpty()) unescapeVcard(name) else "Contact"
        }
        if (trimmed.startsWith("BEGIN:VCALENDAR", ignoreCase = true) ||
            trimmed.startsWith("BEGIN:VEVENT", ignoreCase = true)
        ) {
            val summary = Regex("(?m)^SUMMARY:(.*)$").find(trimmed)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            return if (summary.isNotEmpty()) unescapeVcard(summary) else "Event"
        }
        if (trimmed.startsWith("mailto:", ignoreCase = true)) return "Email"
        if (trimmed.startsWith("sms:", ignoreCase = true) || trimmed.startsWith("smsto:", ignoreCase = true)) {
            return "SMS"
        }
        if (trimmed.startsWith("tel:", ignoreCase = true)) {
            return trimmed.substringAfter(":").ifBlank { "Phone" }
        }
        if (trimmed.startsWith("geo:", ignoreCase = true)) return "Location"
        if (isHttpUrl(trimmed)) {
            return Uri.parse(trimmed).host?.ifBlank { null } ?: "URL"
        }
        return formatLabel(format)
    }

    fun formatLabel(format: String): String {
        return when (format) {
            "QR_CODE" -> "QR Code"
            "EAN_8" -> "EAN-8"
            "EAN_13" -> "EAN-13"
            "UPC_A" -> "UPC-A"
            "UPC_E" -> "UPC-E"
            "CODE_39" -> "Code 39"
            "CODE_93" -> "Code 93"
            "CODE_128" -> "Code 128"
            "ITF" -> "ITF"
            "CODABAR" -> "Codabar"
            "DATA_MATRIX" -> "Data Matrix"
            "PDF_417" -> "PDF417"
            "AZTEC" -> "Aztec"
            else -> format
        }
    }

    private fun gtinPayload(raw: String, bodyLength: Int, format: BarcodeFormat): BuiltCode? {
        val normalized = normalizeGtin(raw, bodyLength) ?: return null
        if (!Encoder.canEncode(normalized, format)) return null
        return BuiltCode(normalized, format)
    }

    private fun upcEPayload(raw: String): BuiltCode? {
        var digits = raw.filter { !it.isWhitespace() }
        if (digits.isEmpty() || digits.any { !it.isDigit() }) return null
        if (digits.length == 6) digits = "0$digits"
        if (digits.length != 7 && digits.length != 8) return null
        if (!Encoder.canEncode(digits, BarcodeFormat.UPC_E)) return null
        return BuiltCode(digits, BarcodeFormat.UPC_E)
    }

    private fun itfPayload(raw: String): BuiltCode? {
        val digits = raw.filter { !it.isWhitespace() }
        if (digits.isEmpty() || digits.any { !it.isDigit() } || digits.length % 2 != 0) return null
        if (!Encoder.canEncode(digits, BarcodeFormat.ITF)) return null
        return BuiltCode(digits, BarcodeFormat.ITF)
    }

    private fun symbolPayload(raw: String, format: BarcodeFormat): BuiltCode? {
        var value = raw.trim()
        if (value.isEmpty()) return null
        if (format == BarcodeFormat.CODE_39 || format == BarcodeFormat.CODE_93 || format == BarcodeFormat.CODABAR) {
            value = value.uppercase(Locale.US)
        }
        if (format == BarcodeFormat.CODE_39) {
            if (value.startsWith("*") && value.endsWith("*") && value.length >= 2) {
                value = value.substring(1, value.length - 1)
            }
            if (value.isEmpty() || value.any { it !in CODE39 }) return null
        }
        if (!Encoder.canEncode(value, format)) return null
        return BuiltCode(value, format)
    }

    private fun textPayload(raw: String): BuiltCode? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        if (!Encoder.canEncode(value, BarcodeFormat.QR_CODE)) return null
        return BuiltCode(value, BarcodeFormat.QR_CODE)
    }

    private fun urlPayload(raw: String): BuiltCode? {
        var value = raw.trim()
        if (value.isEmpty() || value.any { it.isWhitespace() }) return null
        if (!value.contains("://")) value = "https://$value"
        val uri = Uri.parse(value)
        val scheme = uri.scheme?.lowercase(Locale.US)
        if (scheme != "http" && scheme != "https") return null
        if (uri.host.isNullOrBlank()) return null
        return BuiltCode(value, BarcodeFormat.QR_CODE)
    }

    private fun contactPayload(fields: Map<String, String>): BuiltCode? {
        val card = vCard(
            name = fields["v1"].orEmpty(),
            organization = fields["v2"].orEmpty(),
            phone = fields["v3"].orEmpty(),
            email = fields["v4"].orEmpty(),
            address = fields["v5"].orEmpty(),
            website = fields["v6"].orEmpty(),
            note = ""
        ) ?: return null
        if (!Encoder.canEncode(card, BarcodeFormat.QR_CODE)) return null
        return BuiltCode(card, BarcodeFormat.QR_CODE)
    }

    private fun emailPayload(fields: Map<String, String>): BuiltCode? {
        val to = fields["v1"].orEmpty().trim()
        if (to.isEmpty() || !to.contains("@") || to.any { it.isWhitespace() }) return null
        val subject = URLEncoder.encode(fields["v2"].orEmpty().trim(), Charsets.UTF_8.name())
        val body = URLEncoder.encode(fields["v3"].orEmpty().trim(), Charsets.UTF_8.name())
        return BuiltCode("mailto:$to?subject=$subject&body=$body", BarcodeFormat.QR_CODE)
    }

    private fun smsPayload(fields: Map<String, String>): BuiltCode? {
        val number = fields["v1"].orEmpty().trim()
        if (number.isEmpty() || number.none { it.isDigit() }) return null
        val body = URLEncoder.encode(fields["v2"].orEmpty(), Charsets.UTF_8.name())
        return BuiltCode("sms:$number?body=$body", BarcodeFormat.QR_CODE)
    }

    private fun geoPayload(fields: Map<String, String>): BuiltCode? {
        val lat = fields["v1"].orEmpty().trim().toDoubleOrNull() ?: return null
        val lon = fields["v2"].orEmpty().trim().toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        val query = fields["v3"].orEmpty().trim()
        val text = if (query.isEmpty()) {
            "geo:$lat,$lon"
        } else {
            "geo:$lat,$lon?q=" + URLEncoder.encode(query, Charsets.UTF_8.name())
        }
        return BuiltCode(text, BarcodeFormat.QR_CODE)
    }

    private fun phonePayload(raw: String): BuiltCode? {
        val number = raw.trim()
        if (number.isEmpty() || number.none { it.isDigit() }) return null
        val allowed = number.all { it.isDigit() || it == '+' || it == '-' || it == ' ' || it == '(' || it == ')' }
        if (!allowed) return null
        return BuiltCode("tel:$number", BarcodeFormat.QR_CODE)
    }

    private fun calendarPayload(fields: Map<String, String>): BuiltCode? {
        val title = fields["v1"].orEmpty().trim()
        val location = fields["v2"].orEmpty().trim()
        val startRaw = fields["v3"].orEmpty().trim()
        val endRaw = fields["v4"].orEmpty().trim()
        val description = fields["v5"].orEmpty().trim()
        if (title.isEmpty() || startRaw.isEmpty()) return null
        val parser = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { isLenient = false }
        val start = try {
            parser.parse(startRaw)
        } catch (_: Exception) {
            null
        } ?: return null
        val end = if (endRaw.isEmpty()) {
            null
        } else {
            try {
                parser.parse(endRaw)
            } catch (_: Exception) {
                null
            } ?: return null
        }
        if (end != null && end.before(start)) return null
        val formatter = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US)
        val text = buildString {
            append("BEGIN:VCALENDAR\n")
            append("VERSION:2.0\n")
            append("BEGIN:VEVENT\n")
            append("SUMMARY:").append(escapeVcard(title)).append('\n')
            append("DTSTART:").append(formatter.format(start)).append('\n')
            if (end != null) append("DTEND:").append(formatter.format(end)).append('\n')
            if (location.isNotEmpty()) append("LOCATION:").append(escapeVcard(location)).append('\n')
            if (description.isNotEmpty()) append("DESCRIPTION:").append(escapeVcard(description)).append('\n')
            append("END:VEVENT\n")
            append("END:VCALENDAR")
        }
        if (!Encoder.canEncode(text, BarcodeFormat.QR_CODE)) return null
        return BuiltCode(text, BarcodeFormat.QR_CODE)
    }

    private fun wifiPayload(fields: Map<String, String>): BuiltCode? {
        val ssid = fields["v1"].orEmpty()
        val password = fields["v2"].orEmpty()
        val security = fields["security"].orEmpty().ifBlank { "WPA" }
        val hidden = fields["hidden"] == "true"
        if (ssid.isBlank()) return null
        val type = when (security.uppercase(Locale.US)) {
            "WEP" -> "WEP"
            "NONE", "NOPASS", "OPEN" -> "nopass"
            else -> "WPA"
        }
        if (type != "nopass" && password.isEmpty()) return null
        val text = "WIFI:T:$type;S:${escapeWifi(ssid)};P:${escapeWifi(password)};H:${if (hidden) "true" else "false"};;"
        if (!Encoder.canEncode(text, BarcodeFormat.QR_CODE)) return null
        return BuiltCode(text, BarcodeFormat.QR_CODE)
    }

    private fun splitName(name: String): Pair<String, String> {
        val trimmed = name.trim()
        val index = trimmed.lastIndexOf(' ')
        if (index <= 0) return "" to trimmed
        return trimmed.substring(0, index).trim() to trimmed.substring(index + 1).trim()
    }

    private fun escapeVcard(value: String): String {
        return value.trim()
            .replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace(";", "\\;")
            .replace(",", "\\,")
    }

    private fun unescapeVcard(value: String): String {
        return value.replace("\\n", " ").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")
    }

    private fun escapeWifi(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace(";", "\\;")
            .replace(",", "\\,")
            .replace(":", "\\:")
            .replace("\"", "\\\"")
    }

    private val CODE39 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ-. $/+%"
    private val OPEN_SCHEMES = setOf("tel", "mailto", "sms", "smsto", "geo", "market")
}
