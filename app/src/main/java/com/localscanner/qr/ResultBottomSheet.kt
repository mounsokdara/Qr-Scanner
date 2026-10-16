package com.localscanner.qr

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.zxing.BarcodeFormat
import com.localscanner.qr.databinding.LayoutResultSheetBinding

class ResultBottomSheet : BottomSheetDialogFragment() {
    override fun getTheme(): Int = R.style.Theme_QrScanner_BottomSheet

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val binding = LayoutResultSheetBinding.inflate(inflater, container, false)
        val text = requireArguments().getString(ARG_TEXT).orEmpty()
        val formatName = requireArguments().getString(ARG_FORMAT).orEmpty()
        binding.sheetFormat.text = Payloads.formatLabel(formatName)
        binding.sheetText.text = text
        binding.sheetText.setTextIsSelectable(true)
        val store = Store(requireContext())
        val settings = store.getSettings()
        val open = Payloads.actionLabelIsOpen(text)
        binding.btnOpen.setText(if (open) R.string.open else R.string.search)
        binding.btnOpen.setIconResource(if (open) R.drawable.ic_open else R.drawable.ic_search)
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

    private fun exportPng(text: String, formatName: String) {
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
