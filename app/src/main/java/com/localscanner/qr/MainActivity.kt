package com.localscanner.qr

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.localscanner.qr.databinding.ActivityMainBinding
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    lateinit var store: Store
        private set
    private var currentNavId: Int = R.id.nav_scan
    private var menuListener: ((MenuItem) -> Boolean)? = null
    private val io = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) {
            (currentFragment() as? ScanFragment)?.onImagePickCancelled()
        } else {
            decodePicked(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = Store(this)
        binding.drawerLayout.setStatusBarBackgroundColor(ContextCompat.getColor(this, R.color.colorPrimary))
        if (savedInstanceState != null) {
            currentNavId = savedInstanceState.getInt(STATE_NAV, R.id.nav_scan)
        }
        binding.toolbar.setOnMenuItemClickListener { item ->
            menuListener?.invoke(item) ?: false
        }
        setupDrawer()
        binding.navigationView.setCheckedItem(currentNavId)
        if (savedInstanceState == null) {
            showScan()
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBack()
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_NAV, currentNavId)
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    fun openDrawer() {
        binding.drawerLayout.openDrawer(GravityCompat.START)
    }

    /** Edge-swipe opening of the menu; the menu button keeps working when disabled. */
    fun setDrawerSwipeEnabled(enabled: Boolean) {
        val mode = if (enabled) {
            androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_UNLOCKED
        } else {
            androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_LOCKED_CLOSED
        }
        binding.drawerLayout.setDrawerLockMode(mode, GravityCompat.START)
    }

    fun toolbarMenu(): android.view.Menu = binding.toolbar.menu

    fun applyToolbar(
        title: String?,
        showBack: Boolean,
        menuRes: Int,
        listener: ((MenuItem) -> Boolean)?
    ) {
        menuListener = listener
        if (title == null) {
            binding.toolbar.visibility = android.view.View.GONE
            binding.toolbar.menu.clear()
            return
        }
        binding.toolbar.visibility = android.view.View.VISIBLE
        binding.toolbar.title = title
        if (showBack) {
            binding.toolbar.setNavigationIcon(R.drawable.ic_back)
            binding.toolbar.setNavigationOnClickListener {
                onBackPressedDispatcher.onBackPressed()
            }
        } else {
            binding.toolbar.setNavigationIcon(R.drawable.ic_menu)
            binding.toolbar.setNavigationOnClickListener { openDrawer() }
        }
        binding.toolbar.menu.clear()
        if (menuRes != 0) {
            binding.toolbar.inflateMenu(menuRes)
        }
    }

    fun openChild(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.contentFrame, fragment)
            .addToBackStack("child")
            .commit()
    }

    fun pickImage() {
        (currentFragment() as? ScanFragment)?.onImagePickStarted()
        pickImage.launch("image/*")
    }

    fun onCodeDetected(text: String, format: String, batch: Boolean): Boolean {
        if (text.isBlank()) return false
        val settings = store.getSettings()
        val outcome = if (settings.addScansToHistory) {
            store.addRecord(text, format, respectDuplicates = true)
        } else {
            null
        }
        if (batch) {
            val message = when {
                !settings.addScansToHistory -> getString(R.string.scanned)
                outcome != null && outcome.created -> getString(R.string.saved)
                outcome != null -> getString(R.string.already_in_history)
                else -> getString(R.string.scanned)
            }
            Actions.snackbar(binding.contentFrame, message)
            return false
        }
        if (settings.autoOpenUrls && Payloads.isHttpUrl(text.trim()) &&
            (!settings.linkWarnings || LinkSafety.check(text).isEmpty())
        ) {
            Actions.openContent(this, text, settings)
        }
        return showResultSheet(text, format, outcome?.record?.id)
    }

    fun showExistingRecord(record: ScanRecord) {
        showResultSheet(record.text, record.format, record.id)
    }

    fun onResultSheetDismissed() {
        (currentFragment() as? ScanFragment)?.resumeScanning()
    }

    private fun showResultSheet(text: String, format: String, recordId: String?): Boolean {
        if (supportFragmentManager.isStateSaved) return false
        if (supportFragmentManager.findFragmentByTag(ResultBottomSheet.TAG) != null) return true
        ResultBottomSheet.newInstance(text, format, recordId)
            .show(supportFragmentManager, ResultBottomSheet.TAG)
        return true
    }

    private fun setupDrawer() {
        binding.navigationView.setNavigationItemSelectedListener { item ->
            val actionOnly = item.itemId == R.id.nav_scan_image ||
                item.itemId == R.id.nav_share
            when (item.itemId) {
                R.id.nav_scan -> showScan()
                R.id.nav_scan_image -> pickImage()
                R.id.nav_favorites -> showFavorites()
                R.id.nav_history -> showHistory()
                R.id.nav_my_qr -> showMyQr()
                R.id.nav_create -> showCreate()
                R.id.nav_settings -> showSettings()
                R.id.nav_share -> shareApp()
            }
            if (actionOnly) {
                binding.navigationView.setCheckedItem(currentNavId)
                binding.navigationView.post {
                    binding.navigationView.setCheckedItem(currentNavId)
                }
            }
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            true
        }
    }

    private fun showRoot(fragment: Fragment, navId: Int) {
        val manager = supportFragmentManager
        if (manager.backStackEntryCount > 0) {
            manager.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        }
        manager.beginTransaction()
            .replace(R.id.contentFrame, fragment, "root")
            .commitNow()
        currentNavId = navId
        binding.navigationView.setCheckedItem(navId)
    }

    private fun showScan() = showRoot(ScanFragment(), R.id.nav_scan)

    private fun showFavorites() = showRoot(HistoryFragment.newFavorites(), R.id.nav_favorites)

    private fun showHistory() = showRoot(HistoryFragment.newHistory(), R.id.nav_history)

    private fun showMyQr() = showRoot(MyQrFragment(), R.id.nav_my_qr)

    private fun showCreate() = showRoot(CreateListFragment(), R.id.nav_create)

    private fun showSettings() = showRoot(SettingsFragment(), R.id.nav_settings)

    private fun shareApp() {
        Actions.shareText(this, getString(R.string.share_promo))
    }

    private fun handleBack() {
        if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            return
        }
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
            return
        }
        if ((currentFragment() as? ScanFragment)?.handleBack() == true) return
        val current = currentFragment()
        if (current !is ScanFragment) {
            showScan()
            return
        }
        finish()
    }

    private fun currentFragment(): Fragment? {
        return supportFragmentManager.findFragmentById(R.id.contentFrame)
    }

    private fun decodePicked(uri: Uri) {
        val appContext = applicationContext
        io.execute {
            var bitmap: Bitmap? = null
            var unreadable = false
            var codes: List<DetectedCode> = emptyList()
            try {
                bitmap = Decoder.loadOrientedBitmap(appContext, uri)
                if (bitmap == null) {
                    unreadable = true
                } else {
                    codes = Decoder.decodeStill(bitmap)
                }
            } catch (_: Exception) {
                if (bitmap == null) unreadable = true
            }
            mainHandler.post {
                val picture = bitmap
                if (isDestroyed || picture == null) {
                    if (picture != null && !picture.isRecycled) picture.recycle()
                    if (!isDestroyed) {
                        val message = if (unreadable) R.string.could_not_open_image else R.string.no_code_found
                        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                        (currentFragment() as? ScanFragment)?.onImagePickCancelled()
                    }
                    return@post
                }
                if (currentFragment() !is ScanFragment) showScan()
                val scan = currentFragment() as? ScanFragment
                if (scan == null) {
                    picture.recycle()
                    return@post
                }
                scan.showStill(picture, codes)
            }
        }
    }

    companion object {
        private const val STATE_NAV = "nav"
    }
}
