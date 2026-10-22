package com.localscanner.qr

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.zxing.BarcodeFormat
import com.localscanner.qr.databinding.LayoutResultSheetBinding

class ResultBottomSheet : BottomSheetDialogFragment() {
    private var pendingExport: Pair<String, String>? = null

    private val writePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val pending = pendingExport
        pendingExport = null
        if (pending == null || !isAdded) return@registerForActivityResult
        if (granted) {
            exportPng(pending.first, pending.second)
        } else {
            Toast.makeText(requireContext(), R.string.could_not_write, Toast.LENGTH_SHORT).show()
        }
    }

    override fun getTheme(): Int = R.style.Theme_QrScanner_BottomSheet

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val binding = LayoutResultSheetBinding.inflate(inflater, container, false)
        val text = requireArguments().getString(ARG_TEXT).orEmpty()
        val formatName = requireArguments().getString(ARG_FORMAT).orEmpty()
        val kind = Parsers.kindOf(text)
        val kindLabel = kindLabel(kind)
        binding.sheetFormat.text = if (kindLabel == null) {
            Payloads.formatLabel(formatName)
        } else {
            getString(R.string.format_and_kind, Payloads.formatLabel(formatName), kindLabel)
        }
        binding.sheetText.text = text
        binding.sheetText.setTextIsSelectable(true)
        val store = Store(requireContext())
        val settings = store.getSettings()
        val risks = if (kind == ScanKind.URL) LinkSafety.check(text) else emptySet()
        if (risks.isEmpty()) {
            binding.sheetWarning.visibility = View.GONE
        } else {
            binding.sheetWarning.visibility = View.VISIBLE
            binding.sheetWarning.text = riskText(risks)
        }
        val (actionLabel, actionIcon) = actionFor(kind, Payloads.actionLabelIsOpen(text))
        binding.btnOpen.setText(actionLabel)
        binding.btnOpen.setIconResource(actionIcon)
        binding.btnCopy.setOnClickListener { Actions.copy(requireContext(), text) }
        binding.btnShare.setOnClickListener { Actions.shareText(requireActivity(), text) }
        binding.btnOpen.setOnClickListener {
            Actions.openContent(requireActivity(), text, store.getSettings())
        }
        fun refreshFavorite() {
            val id = arguments?.getString(ARG_ID)
            val favorite = id?.let { store.findById(it)?.favorite } == true
            binding.btnFavorite.setIconResource(if (favorite) R.drawable.ic_star else R.drawable.ic_star_border)
            val color = if (favorite) Color.parseColor("#FFC107") else Color.parseColor("#2196F3")
            binding.btnFavorite.iconTint = ColorStateList.valueOf(color)
        }
        binding.btnFavorite.setOnClickListener {
            val updated = store.toggleFavoriteFor(text, formatName, arguments?.getString(ARG_ID))
            arguments?.putString(ARG_ID, updated.id)
            refreshFavorite()
        }
        binding.btnShowCode.setOnClickListener { showCode(text, formatName) }
        binding.btnExportPng.setOnClickListener { exportPng(text, formatName) }
        val custom = Payloads.customActionUrl(settings.customActionUrl, text)
        if (custom == null) {
            binding.btnCustomAction.visibility = View.GONE
        } else {
            binding.btnCustomAction.visibility = View.VISIBLE
            binding.btnCustomAction.setOnClickListener {
                Actions.openHttp(requireActivity(), custom, store.getSettings().useInAppBrowser)
            }
        }
        refreshFavorite()
        return binding.root
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        (activity as? MainActivity)?.onResultSheetDismissed()
    }

    private fun kindLabel(kind: ScanKind): String? {
        return when (kind) {
            ScanKind.URL -> getString(R.string.type_url)
            ScanKind.WIFI -> getString(R.string.type_wifi)
            ScanKind.CONTACT -> getString(R.string.type_contact)
            ScanKind.EVENT -> getString(R.string.type_calendar)
            ScanKind.EMAIL -> getString(R.string.type_email)
            ScanKind.SMS -> getString(R.string.type_sms)
            ScanKind.PHONE -> getString(R.string.type_phone)
            ScanKind.GEO -> getString(R.string.type_geo)
            ScanKind.TEXT -> null
        }
    }

    private fun actionFor(kind: ScanKind, opens: Boolean): Pair<Int, Int> {
        return when (kind) {
            ScanKind.WIFI -> R.string.connect to R.drawable.ic_wifi
            ScanKind.CONTACT -> R.string.add_contact to R.drawable.ic_person
            ScanKind.EVENT -> R.string.add_event to R.drawable.ic_event
            ScanKind.EMAIL -> R.string.send_email to R.drawable.ic_email
            ScanKind.SMS -> R.string.send_sms to R.drawable.ic_sms
            ScanKind.PHONE -> R.string.call to R.drawable.ic_phone
            ScanKind.GEO -> R.string.show_map to R.drawable.ic_place
            else -> if (opens) R.string.open to R.drawable.ic_open else R.string.search to R.drawable.ic_search
        }
    }

    private fun riskText(risks: Set<LinkRisk>): String {
        val lines = risks.map { risk ->
            val res = when (risk) {
                LinkRisk.INSECURE -> R.string.risk_insecure
                LinkRisk.IP_HOST -> R.string.risk_ip_host
                LinkRisk.PUNYCODE -> R.string.risk_punycode
                LinkRisk.NON_ASCII_HOST -> R.string.risk_non_ascii
                LinkRisk.SHORTENER -> R.string.risk_shortener
                LinkRisk.CREDENTIALS -> R.string.risk_credentials
            }
            "• " + getString(res)
        }
        return getString(R.string.risk_header) + "\n" + lines.joinToString("\n")
    }

    private fun exportPng(text: String, formatName: String) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingExport = text to formatName
            writePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        val format = Encoder.formatOf(formatName) ?: BarcodeFormat.QR_CODE
        val bitmap = Encoder.encode(text, format) ?: Encoder.encode(text, BarcodeFormat.QR_CODE)
        if (bitmap == null) {
            Toast.makeText(requireContext(), R.string.invalid_entry, Toast.LENGTH_SHORT).show()
            return
        }
        val saved = try {
            Actions.insertPng(requireContext(), bitmap)
        } catch (_: Exception) {
            false
        }
        Toast.makeText(
            requireContext(),
            if (saved) R.string.saved_pictures else R.string.could_not_write,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun showCode(text: String, formatName: String) {
        val format = Encoder.formatOf(formatName) ?: BarcodeFormat.QR_CODE
        val bitmap = Encoder.encode(text, format) ?: Encoder.encode(text, BarcodeFormat.QR_CODE)
        if (bitmap == null) {
            Toast.makeText(requireContext(), R.string.invalid_entry, Toast.LENGTH_SHORT).show()
            return
        }
        val image = ImageView(requireContext())
        val pad = (16 * resources.displayMetrics.density).toInt()
        image.setPadding(pad, pad, pad, pad)
        image.setBackgroundColor(Color.WHITE)
        image.adjustViewBounds = true
        image.setImageBitmap(bitmap)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(Payloads.formatLabel(formatName))
            .setView(image)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    companion object {
        const val TAG = "result"
        private const val ARG_TEXT = "text"
        private const val ARG_FORMAT = "format"
        private const val ARG_ID = "id"

        fun newInstance(text: String, format: String, id: String?): ResultBottomSheet {
            return ResultBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_TEXT, text)
                    putString(ARG_FORMAT, format)
                    if (id != null) putString(ARG_ID, id)
                }
            }
        }
    }
}
