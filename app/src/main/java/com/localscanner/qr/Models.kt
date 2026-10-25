package com.localscanner.qr

data class ScanRecord(
    val id: String,
    val text: String,
    val format: String,
    val timestamp: Long,
    val title: String,
    val favorite: Boolean
)

data class AppSettings(
    val batchScanMode: Boolean = false,
    val useAutoFocus: Boolean = true,
    val touchFocus: Boolean = true,
    val keepDuplicates: Boolean = true,
    val customActionUrl: String = "",
    val useInAppBrowser: Boolean = true,
    val addScansToHistory: Boolean = true,
    val autoOpenUrls: Boolean = false,
    val cameraId: String = "",
    val searchEngine: String = "device",
    val searchCountry: String = "US",
    val soundOnScan: Boolean = true,
    val vibrateOnScan: Boolean = true,
    val multiScan: Boolean = true,
    val showBoxes: Boolean = true,
    val showLabels: Boolean = true,
    val linkWarnings: Boolean = true,
    val pinchZoom: Boolean = true
)

data class MyQrProfile(
    val name: String = "",
    val organization: String = "",
    val phone: String = "",
    val email: String = "",
    val address: String = "",
    val website: String = "",
    val note: String = "",
    val saved: Boolean = false
)

enum class CreateKind {
    CLIPBOARD,
    URL,
    TEXT,
    CONTACT,
    EMAIL,
    SMS,
    GEO,
    PHONE,
    CALENDAR,
    WIFI,
    EAN_8,
    EAN_13,
    UPC_E,
    UPC_A,
    CODE_39,
    CODE_93,
    CODE_128,
    ITF,
    CODABAR,
    DATA_MATRIX,
    PDF_417,
    AZTEC
}

data class AddOutcome(val record: ScanRecord, val created: Boolean)
