package com.localscanner.qr

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Transfer {
    fun toCsv(records: List<ScanRecord>): String {
        val builder = StringBuilder()
        builder.append("id,timestamp,format,title,favorite,text\n")
        for (record in records) {
            builder.append(csvEscape(record.id)).append(',')
            builder.append(record.timestamp).append(',')
            builder.append(csvEscape(record.format)).append(',')
            builder.append(csvEscape(record.title)).append(',')
            builder.append(if (record.favorite) "1" else "0").append(',')
            builder.append(csvEscape(record.text)).append('\n')
        }
        return builder.toString()
    }

    fun toTxt(records: List<ScanRecord>): String {
        val format = SimpleDateFormat("M/d/yy h:mm:ss a", Locale.getDefault())
        return records.joinToString(separator = "\n-----\n") { record ->
            buildString {
                append(record.title).append('\n')
                append(format.format(Date(record.timestamp)))
                append(", ")
                append(record.format)
                append('\n')
                append(record.text)
                append('\n')
            }
        }
    }

    fun parseCsv(text: String): List<ScanRecord> {
        val rows = parseRows(text)
        if (rows.isEmpty()) return emptyList()
        val header = rows.first().map { it.trim().lowercase(Locale.US) }
        val hasHeader = header.contains("text") || header.contains("format") || header.contains("title")
        val start = if (hasHeader) 1 else 0
        val records = ArrayList<ScanRecord>()
        for (index in start until rows.size) {
            val cols = rows[index]
            if (cols.all { it.isBlank() }) continue
            if (hasHeader) {
                fun col(name: String): String {
                    val at = header.indexOf(name)
                    return if (at in cols.indices) cols[at] else ""
                }
                val body = col("text").ifBlank { cols.lastOrNull().orEmpty() }
                if (body.isBlank()) continue
                val format = col("format").ifBlank { "QR_CODE" }
                val timestamp = col("timestamp").toLongOrNull() ?: System.currentTimeMillis()
                val title = col("title").ifBlank { Payloads.defaultTitle(body, format) }
                val favorite = col("favorite").equals("1", true) || col("favorite").equals("true", true)
                records.add(
                    ScanRecord(
                        id = col("id"),
                        text = body,
                        format = format,
                        timestamp = timestamp,
                        title = title,
                        favorite = favorite
                    )
                )
            } else {
                val body = cols.getOrNull(0).orEmpty()
                if (body.isBlank()) continue
                val format = cols.getOrNull(1).orEmpty().ifBlank { "QR_CODE" }
                records.add(
                    ScanRecord(
                        id = "",
                        text = body,
                        format = format,
                        timestamp = System.currentTimeMillis(),
                        title = Payloads.defaultTitle(body, format),
                        favorite = false
                    )
                )
            }
        }
        return records
    }

    private fun parseRows(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var index = 0
        val length = text.length
        while (index < length) {
            val row = ArrayList<String>()
            val cell = StringBuilder()
            var quoted = false
            var rowEnded = false
            while (index < length && !rowEnded) {
                val ch = text[index]
                if (quoted) {
                    if (ch == '"') {
                        if (index + 1 < length && text[index + 1] == '"') {
                            cell.append('"')
                            index += 2
                        } else {
                            quoted = false
                            index++
                        }
                    } else {
                        cell.append(ch)
                        index++
                    }
                } else {
                    when (ch) {
                        '"' -> {
                            quoted = true
                            index++
                        }
                        ',' -> {
                            row.add(cell.toString())
                            cell.setLength(0)
                            index++
                        }
                        '\n' -> {
                            index++
                            rowEnded = true
                        }
                        '\r' -> {
                            index++
                            if (index < length && text[index] == '\n') index++
                            rowEnded = true
                        }
                        else -> {
                            cell.append(ch)
                            index++
                        }
                    }
                }
            }
            row.add(cell.toString())
            if (!(row.size == 1 && row[0].isEmpty())) {
                rows.add(row)
            }
        }
        return rows
    }

    private fun csvEscape(value: String): String {
        val escaped = value.replace("\"", "\"\"")
        val needsQuotes = escaped.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuotes) "\"$escaped\"" else escaped
    }
}
