package com.localscanner.qr

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.localscanner.qr.databinding.LayoutBrowserBinding

class BrowserActivity : AppCompatActivity() {
    private lateinit var binding: LayoutBrowserBinding

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = LayoutBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (!Payloads.isHttpUrl(url)) {
            finish()
            return
        }
        binding.browserToolbar.setNavigationOnClickListener { finish() }
        binding.browserToolbar.title = getString(R.string.browser)
        binding.browserToolbar.inflateMenu(R.menu.menu_browser)
        binding.browserToolbar.setOnMenuItemClickListener { item ->
            val current = binding.browserWeb.url ?: url
            when (item.itemId) {
                R.id.action_reload -> binding.browserWeb.reload()
                R.id.action_open_external -> openExternal(current)
                R.id.action_copy_link -> Actions.copy(this, current)
                R.id.action_share_link -> Actions.shareText(this, current)
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        showHost(url)
        val web = binding.browserWeb
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val target = request.url?.toString().orEmpty()
                return !(target.startsWith("http://") || target.startsWith("https://"))
            }

            override fun onPageStarted(view: WebView?, pageUrl: String?, favicon: Bitmap?) {
                super.onPageStarted(view, pageUrl, favicon)
                if (pageUrl != null) showHost(pageUrl)
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.browserProgress.progress = newProgress
                binding.browserProgress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                if (!title.isNullOrBlank()) binding.browserToolbar.title = title
            }
        }
        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState)
        } else {
            web.loadUrl(url)
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::binding.isInitialized) binding.browserWeb.saveState(outState)
    }

    override fun onDestroy() {
        if (::binding.isInitialized) {
            val web = binding.browserWeb
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        }
        super.onDestroy()
    }

    /** Android 15 draws apps edge-to-edge, so pad the toolbar and content clear of the system bars. */
    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.browserRoot) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            binding.browserToolbar.updatePadding(top = bars.top)
            binding.browserRoot.updatePadding(left = bars.left, right = bars.right, bottom = bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    /** Always show which host is loaded, and flag unencrypted pages, so phishing is easier to spot. */
    private fun showHost(url: String) {
        val uri = Uri.parse(url)
        val host = uri.host.orEmpty()
        binding.browserToolbar.subtitle = if (uri.scheme.equals("http", ignoreCase = true)) {
            getString(R.string.not_secure) + " · " + host
        } else {
            host
        }
    }

    private fun openExternal(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, R.string.nothing_to_open, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val EXTRA_URL = "url"
    }
}
