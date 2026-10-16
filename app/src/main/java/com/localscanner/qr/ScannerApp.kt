package com.localscanner.qr

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class ScannerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
    }
}
