package com.localscanner.qr

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class Store(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Any()

    fun getHistory(): List<ScanRecord> = synchronized(lock) { readHistory() }

    fun getSettings(): AppSettings = synchronized(lock) { readSettings() }

    fun saveSettings(settings: AppSettings) = synchronized(lock) {
        prefs.edit().putString(KEY_SETTINGS, settingsToJson(settings).toString()).apply()
    }

    fun updateSettings(block: (AppSettings) -> AppSettings) = synchronized(lock) {
        val updated = block(readSettings())
        prefs.edit().putString(KEY_SETTINGS, settingsToJson(updated).toString()).apply()
    }

    fun getMyQr(): MyQrProfile = synchronized(lock) { readMyQr() }

    fun saveMyQr(profile: MyQrProfile) = synchronized(lock) {
        prefs.edit().putString(KEY_MY_QR, myQrToJson(profile).toString()).apply()
    }

    fun addRecord(
        text: String,
        format: String,
        respectDuplicates: Boolean,
        favorite: Boolean = false
    ): AddOutcome = synchronized(lock) {
        val history = readHistory().toMutableList()
        if (respectDuplicates && !readSettings().keepDuplicates) {
            val existing = history.firstOrNull { it.text == text }
            if (existing != null) {
                return AddOutcome(existing, false)
            }
        }
        val record = ScanRecord(
            id = UUID.randomUUID().toString(),
            text = text,
            format = format,
            timestamp = System.currentTimeMillis(),
            title = Payloads.defaultTitle(text, format),
            favorite = favorite
        )
        history.add(0, record)
        writeHistory(history)
        AddOutcome(record, true)
    }

    fun findById(id: String): ScanRecord? = synchronized(lock) {
        readHistory().firstOrNull { it.id == id }
    }

    fun findByText(text: String): ScanRecord? = synchronized(lock) {
        readHistory().firstOrNull { it.text == text }
    }

    fun saveRecord(updated: ScanRecord) = synchronized(lock) {
        val history = readHistory().toMutableList()
        val index = history.indexOfFirst { it.id == updated.id }
        if (index >= 0) {
            history[index] = updated
            writeHistory(history)
        }
    }

    fun setFavorite(id: String, favorite: Boolean): ScanRecord? = synchronized(lock) {
        val history = readHistory().toMutableList()
        val index = history.indexOfFirst { it.id == id }
        if (index < 0) return null
        val updated = history[index].copy(favorite = favorite)
        history[index] = updated
        writeHistory(history)
        updated
    }

    fun rename(id: String, title: String) = synchronized(lock) {
        val history = readHistory().toMutableList()
        val index = history.indexOfFirst { it.id == id }
        if (index >= 0) {
            history[index] = history[index].copy(title = title)
            writeHistory(history)
        }
    }

    fun delete(id: String) = synchronized(lock) {
        val history = readHistory().filterNot { it.id == id }
        writeHistory(history)
    }

    fun replaceAll(records: List<ScanRecord>) = synchronized(lock) {
        writeHistory(records)
    }

    fun restore(record: ScanRecord) = synchronized(lock) {
        val history = readHistory().toMutableList()
        if (history.none { it.id == record.id }) {
            history.add(record)
            history.sortByDescending { it.timestamp }
            writeHistory(history)
        }
    }

    fun restoreAll(records: List<ScanRecord>) = synchronized(lock) {
        val history = readHistory().toMutableList()
        val ids = history.map { it.id }.toMutableSet()
        for (record in records) {
            if (record.id !in ids) {
                history.add(record)
                ids.add(record.id)
            }
        }
        history.sortByDescending { it.timestamp }
        writeHistory(history)
    }

    fun importRecords(incoming: List<ScanRecord>) = synchronized(lock) {
        val history = readHistory().toMutableList()
        val ids = history.map { it.id }.toMutableSet()
        for (record in incoming) {
            var id = record.id
            if (id.isBlank() || id in ids) {
                id = UUID.randomUUID().toString()
            }
            ids.add(id)
            val title = record.title.ifBlank { Payloads.defaultTitle(record.text, record.format) }
            history.add(record.copy(id = id, title = title))
        }
        history.sortByDescending { it.timestamp }
        writeHistory(history)
    }

    fun toggleFavoriteFor(text: String, format: String, currentId: String?): ScanRecord {
        synchronized(lock) {
            val history = readHistory().toMutableList()
            val index = when {
                currentId != null -> history.indexOfFirst { it.id == currentId }
                else -> -1
            }.let { found ->
                if (found >= 0) found else history.indexOfFirst { it.text == text }
            }
            if (index >= 0) {
                val updated = history[index].copy(favorite = !history[index].favorite)
                history[index] = updated
                writeHistory(history)
                return updated
            }
        }
        return addRecord(text, format, respectDuplicates = false, favorite = true).record
    }

    fun wasCameraRequested(): Boolean = prefs.getBoolean(KEY_CAMERA_ASKED, false)

    fun markCameraRequested() {
        prefs.edit().putBoolean(KEY_CAMERA_ASKED, true).apply()
    }

    private fun readHistory(): List<ScanRecord> {
        val raw = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            val list = ArrayList<ScanRecord>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    ScanRecord(
                        id = obj.optString("id"),
                        text = obj.optString("text"),
                        format = obj.optString("format", "QR_CODE"),
                        timestamp = obj.optLong("timestamp"),
                        title = obj.optString("title"),
                        favorite = obj.optBoolean("favorite", false)
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeHistory(records: List<ScanRecord>) {
        val array = JSONArray()
        for (record in records) {
            array.put(recordToJson(record))
        }
        prefs.edit().putString(KEY_HISTORY, array.toString()).apply()
    }

    private fun recordToJson(record: ScanRecord): JSONObject {
        return JSONObject()
            .put("id", record.id)
            .put("text", record.text)
            .put("format", record.format)
            .put("timestamp", record.timestamp)
            .put("title", record.title)
            .put("favorite", record.favorite)
    }

    private fun readSettings(): AppSettings {
        val raw = prefs.getString(KEY_SETTINGS, null) ?: return AppSettings()
        return try {
            val obj = JSONObject(raw)
            AppSettings(
                batchScanMode = bool(obj, "batchScanMode", false),
                useAutoFocus = bool(obj, "useAutoFocus", true),
                touchFocus = bool(obj, "touchFocus", true),
                keepDuplicates = bool(obj, "keepDuplicates", true),
                customActionUrl = obj.optString("customActionUrl", ""),
                useInAppBrowser = bool(obj, "useInAppBrowser", true),
                addScansToHistory = bool(obj, "addScansToHistory", true),
                autoOpenUrls = bool(obj, "autoOpenUrls", false),
                cameraId = obj.optString("cameraId", ""),
                searchEngine = obj.optString("searchEngine", "device"),
                searchCountry = obj.optString("searchCountry", "US"),
                soundOnScan = bool(obj, "soundOnScan", true),
                vibrateOnScan = bool(obj, "vibrateOnScan", true),
                multiScan = bool(obj, "multiScan", true),
                autoScan = bool(obj, "autoScan", true)
            )
        } catch (_: Exception) {
            AppSettings()
        }
    }

    private fun settingsToJson(settings: AppSettings): JSONObject {
        return JSONObject()
            .put("batchScanMode", settings.batchScanMode)
            .put("useAutoFocus", settings.useAutoFocus)
            .put("touchFocus", settings.touchFocus)
            .put("keepDuplicates", settings.keepDuplicates)
            .put("customActionUrl", settings.customActionUrl)
            .put("useInAppBrowser", settings.useInAppBrowser)
            .put("addScansToHistory", settings.addScansToHistory)
            .put("autoOpenUrls", settings.autoOpenUrls)
            .put("cameraId", settings.cameraId)
            .put("searchEngine", settings.searchEngine)
            .put("searchCountry", settings.searchCountry)
            .put("soundOnScan", settings.soundOnScan)
            .put("vibrateOnScan", settings.vibrateOnScan)
            .put("multiScan", settings.multiScan)
            .put("autoScan", settings.autoScan)
    }

    private fun readMyQr(): MyQrProfile {
        val raw = prefs.getString(KEY_MY_QR, null) ?: return MyQrProfile()
        return try {
            val obj = JSONObject(raw)
            MyQrProfile(
                name = obj.optString("name", ""),
                organization = obj.optString("organization", ""),
                phone = obj.optString("phone", ""),
                email = obj.optString("email", ""),
                address = obj.optString("address", ""),
                website = obj.optString("website", ""),
                note = obj.optString("note", ""),
                saved = bool(obj, "saved", false)
            )
        } catch (_: Exception) {
            MyQrProfile()
        }
    }

    private fun myQrToJson(profile: MyQrProfile): JSONObject {
        return JSONObject()
            .put("name", profile.name)
            .put("organization", profile.organization)
            .put("phone", profile.phone)
            .put("email", profile.email)
            .put("address", profile.address)
            .put("website", profile.website)
            .put("note", profile.note)
            .put("saved", profile.saved)
    }

    private fun bool(obj: JSONObject, key: String, default: Boolean): Boolean {
        return if (obj.has(key)) obj.optBoolean(key) else default
    }

    companion object {
        private const val PREFS = "scanner_store"
        private const val KEY_HISTORY = "history"
        private const val KEY_SETTINGS = "settings"
        private const val KEY_MY_QR = "my_qr"
        private const val KEY_CAMERA_ASKED = "camera_requested"
    }
}
