package com.localscanner.qr

import android.content.Context

object Labels {
    fun kindName(context: Context, kind: ScanKind): String? {
        return when (kind) {
            ScanKind.URL -> context.getString(R.string.type_url)
            ScanKind.WIFI -> context.getString(R.string.type_wifi)
            ScanKind.CONTACT -> context.getString(R.string.type_contact)
            ScanKind.EVENT -> context.getString(R.string.type_calendar)
            ScanKind.EMAIL -> context.getString(R.string.type_email)
            ScanKind.SMS -> context.getString(R.string.type_sms)
            ScanKind.PHONE -> context.getString(R.string.type_phone)
            ScanKind.GEO -> context.getString(R.string.type_geo)
            ScanKind.TEXT -> null
        }
    }

    fun headline(context: Context, text: String, format: String): String {
        Labels.kindName(context, Parsers.kindOf(text))?.let { return it }
        return if (format == "QR_CODE") {
            context.getString(R.string.type_text)
        } else {
            Payloads.formatLabel(format)
        }
    }

    fun chip(context: Context, text: String, format: String, risky: Boolean): String {
        val head = headline(context, text, format)
        val detail = Parsers.detail(text)
        val base = if (detail.isEmpty()) head else "$head · $detail"
        return if (risky) "⚠ $base" else base
    }
}
