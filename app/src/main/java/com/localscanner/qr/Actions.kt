package com.localscanner.qr

import android.app.SearchManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder

object Actions {
    fun feedback(context: Context) {
        try {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 90)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    tone.release()
                } catch (_: Exception) {
                    // Already released.
                }
            }, 250)
        } catch (_: Exception) {
            // Beep is optional.
        }
        try {
            val effect = VibrationEffect.createOneShot(70, VibrationEffect.DEFAULT_AMPLITUDE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = context.getSystemService(VibratorManager::class.java)
                manager?.defaultVibrator?.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                vibrator.vibrate(effect)
            }
        } catch (_: Exception) {
            // Vibration is optional.
        }
    }

    fun copy(context: Context, text: String) {
        val manager = context.getSystemService(ClipboardManager::class.java) ?: return
        manager.setPrimaryClip(ClipData.newPlainText("code", text))
        Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    fun readClipboard(context: Context): String {
        val manager = context.getSystemService(ClipboardManager::class.java) ?: return ""
        val clip = manager.primaryClip ?: return ""
        if (clip.itemCount <= 0) return ""
        return clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
    }

    fun shareText(context: Context, text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        launch(context, Intent.createChooser(intent, context.getString(R.string.share)))
    }

    fun shareBitmap(context: Context, bitmap: Bitmap) {
        try {
            val dir = File(context.cacheDir, "share")
            dir.mkdirs()
            val file = File(dir, "code_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
            val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newUri(context.contentResolver, "code", uri)
            }
            launch(context, Intent.createChooser(intent, context.getString(R.string.share)))
        } catch (_: Exception) {
            Toast.makeText(context, R.string.could_not_write, Toast.LENGTH_SHORT).show()
        }
    }

    fun insertPng(context: Context, bitmap: Bitmap): Boolean {
        val name = "QR_${System.currentTimeMillis()}.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/QRScanner")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val uri = context.contentResolver.insert(collection, values) ?: return false
        context.contentResolver.openOutputStream(uri)?.use { stream ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                context.contentResolver.delete(uri, null, null)
                return false
            }
        } ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pending = ContentValues()
            pending.put(MediaStore.Images.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, pending, null, null)
        }
        return true
    }

    fun openContent(context: Context, text: String, settings: AppSettings) {
        val trimmed = text.trim()
        if (trimmed.startsWith("WIFI:", ignoreCase = true)) {
            showWifi(context, trimmed)
            return
        }
        val normalized = if (trimmed.startsWith("www.", ignoreCase = true)) "https://$trimmed" else trimmed
        if (Payloads.isHttpUrl(normalized)) {
            openHttp(context, normalized, settings.useInAppBrowser)
            return
        }
        val uri = Uri.parse(normalized)
        val scheme = uri.scheme?.lowercase()
        if (scheme == "tel" || scheme == "mailto" || scheme == "sms" || scheme == "smsto" || scheme == "geo" || scheme == "market") {
            try {
                launch(context, Intent(Intent.ACTION_VIEW, uri))
            } catch (_: Exception) {
                Toast.makeText(context, R.string.nothing_to_open, Toast.LENGTH_SHORT).show()
            }
            return
        }
        val search = Payloads.searchUrl(trimmed, settings.searchEngine, settings.searchCountry)
        if (search != null) {
            openHttp(context, search, settings.useInAppBrowser)
            return
        }
        try {
            val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra(SearchManager.QUERY, trimmed)
            }
            launch(context, intent)
        } catch (_: Exception) {
            val fallback = "https://www.google.com/search?q=" + URLEncoder.encode(trimmed, Charsets.UTF_8.name())
            openHttp(context, fallback, settings.useInAppBrowser)
        }
    }

    fun openHttp(context: Context, url: String, inApp: Boolean) {
        if (!Payloads.isHttpUrl(url)) {
            Toast.makeText(context, R.string.custom_action_http, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            if (inApp) {
                val intent = Intent(context, BrowserActivity::class.java).putExtra(BrowserActivity.EXTRA_URL, url)
                launch(context, intent)
            } else {
                launch(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        } catch (_: Exception) {
            Toast.makeText(context, R.string.nothing_to_open, Toast.LENGTH_SHORT).show()
        }
    }

    fun snackbar(view: View, text: String, action: String? = null, onAction: (() -> Unit)? = null) {
        val length = if (action == null) Snackbar.LENGTH_SHORT else Snackbar.LENGTH_LONG
        val bar = Snackbar.make(view, text, length)
        bar.setBackgroundTint(Color.parseColor("#424242"))
        bar.setTextColor(Color.WHITE)
        if (action != null && onAction != null) {
            bar.setAction(action) { onAction() }
            bar.setActionTextColor(Color.parseColor("#2196F3"))
        }
        bar.show()
    }

    private fun showWifi(context: Context, text: String) {
        val info = Payloads.parseWifi(text)
        if (info == null) {
            copy(context, text)
            return
        }
        val password = info.password.ifEmpty { "—" }
        val type = info.type.ifEmpty { "nopass" }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.wifi_title)
            .setMessage(context.getString(R.string.wifi_body, info.ssid, password, type))
            .setPositiveButton(R.string.ok, null)
            .setNeutralButton(R.string.copy_password) { _, _ ->
                if (info.password.isNotEmpty()) copy(context, info.password)
            }
            .show()
    }

    private fun launch(context: Context, intent: Intent) {
        var current: Context = context
        while (current is ContextWrapper && current !is android.app.Activity) {
            current = current.baseContext
        }
        if (current !is android.app.Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
