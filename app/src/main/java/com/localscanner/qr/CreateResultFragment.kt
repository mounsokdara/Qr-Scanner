package com.localscanner.qr

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.localscanner.qr.databinding.LayoutCreateResultBinding
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

class CreateResultFragment : Fragment() {
    private var _binding: LayoutCreateResultBinding? = null
    private val binding get() = _binding!!
    private val io = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var bitmap: Bitmap? = null
    private var pendingPng: String? = null
    private var foreground = Color.BLACK
    private var ecLevel = ErrorCorrectionLevel.M
    private var generation = 0
    private var codeText = ""
    private var codeFormat = BarcodeFormat.QR_CODE

    private val createPng = registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) copyPending(uri)
    }
    private val writePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) saveCurrent() else stageAndCreate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingPng = savedInstanceState?.getString(STATE_PNG)
        if (savedInstanceState != null) {
            foreground = savedInstanceState.getInt(STATE_COLOR, Color.BLACK)
            ecLevel = when (savedInstanceState.getString(STATE_EC)) {
                "L" -> ErrorCorrectionLevel.L
                "Q" -> ErrorCorrectionLevel.Q
                "H" -> ErrorCorrectionLevel.H
                else -> ErrorCorrectionLevel.M
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = LayoutCreateResultBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        codeText = requireArguments().getString(ARG_TEXT).orEmpty()
        codeFormat = Encoder.formatOf(requireArguments().getString(ARG_FORMAT).orEmpty()) ?: BarcodeFormat.QR_CODE
        binding.codeText.text = codeText
        binding.btnSave.setOnClickListener { saveCurrent() }
        binding.btnShare.setOnClickListener {
            val image = bitmap ?: return@setOnClickListener
            Actions.shareBitmap(requireActivity(), image)
        }
        buildSwatches()
        setupErrorCorrection()
        regenerate()
    }

    private fun setupErrorCorrection() {
        // Error correction only exists for QR codes; other symbologies ignore it.
        if (codeFormat != BarcodeFormat.QR_CODE) {
            binding.ecRow.visibility = View.GONE
            return
        }
        binding.ecGroup.check(
            when (ecLevel) {
                ErrorCorrectionLevel.L -> R.id.btnEcL
                ErrorCorrectionLevel.Q -> R.id.btnEcQ
                ErrorCorrectionLevel.H -> R.id.btnEcH
                else -> R.id.btnEcM
            }
        )
        binding.ecGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            ecLevel = when (checkedId) {
                R.id.btnEcL -> ErrorCorrectionLevel.L
                R.id.btnEcQ -> ErrorCorrectionLevel.Q
                R.id.btnEcH -> ErrorCorrectionLevel.H
                else -> ErrorCorrectionLevel.M
            }
            regenerate()
        }
    }

    private fun buildSwatches() {
        val row = binding.colorRow
        row.removeAllViews()
        val density = resources.displayMetrics.density
        val size = (36 * density).toInt()
        val gap = (12 * density).toInt()
        val ring = (3 * density).toInt()
        for (color in SWATCHES) {
            val swatch = View(requireContext())
            val selected = color == foreground
            swatch.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                if (selected) setStroke(ring, Color.WHITE) else setStroke(ring, Color.TRANSPARENT)
            }
            swatch.contentDescription = getString(R.string.qr_color)
            swatch.layoutParams = ViewGroup.MarginLayoutParams(size, size).apply { marginEnd = gap }
            swatch.setOnClickListener {
                if (foreground != color) {
                    foreground = color
                    buildSwatches()
                    regenerate()
                }
            }
            row.addView(swatch)
        }
    }

    private fun regenerate() {
        val gen = ++generation
        val color = foreground
        val level = ecLevel
        val text = codeText
        val format = codeFormat
        io.execute {
            val encoded = Encoder.encode(text, format, color, level)
            mainHandler.post {
                if (_binding == null || gen != generation) {
                    encoded?.recycle()
                    return@post
                }
                val old = bitmap
                bitmap = encoded
                if (encoded == null) {
                    binding.codeImage.setImageDrawable(null)
                    Toast.makeText(requireContext(), R.string.invalid_entry, Toast.LENGTH_LONG).show()
                } else {
                    binding.codeImage.setImageBitmap(encoded)
                }
                old?.recycle()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.applyToolbar(getString(R.string.create_qr), true, 0, null)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PNG, pendingPng)
        outState.putInt(STATE_COLOR, foreground)
        outState.putString(
            STATE_EC,
            when (ecLevel) {
                ErrorCorrectionLevel.L -> "L"
                ErrorCorrectionLevel.Q -> "Q"
                ErrorCorrectionLevel.H -> "H"
                else -> "M"
            }
        )
    }

    override fun onDestroyView() {
        binding.codeImage.setImageDrawable(null)
        bitmap?.recycle()
        bitmap = null
        _binding = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    private fun saveCurrent() {
        val image = bitmap ?: return
        if (Build.VERSION.SDK_INT <= 28 &&
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            writePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        val saved = try {
            Actions.insertPng(requireContext(), image)
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
        if (saved) {
            Toast.makeText(requireContext(), R.string.saved_pictures, Toast.LENGTH_SHORT).show()
        } else {
            stageAndCreate()
        }
    }

    private fun stageAndCreate() {
        val image = bitmap ?: return
        try {
            val file = File(requireContext().cacheDir, "pending_save.png")
            FileOutputStream(file).use { stream ->
                image.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
            pendingPng = file.absolutePath
            createPng.launch("code.png")
        } catch (_: Exception) {
            Toast.makeText(requireContext(), R.string.could_not_write, Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyPending(uri: Uri) {
        val path = pendingPng ?: return
        try {
            requireContext().contentResolver.openOutputStream(uri)?.use { output ->
                File(path).inputStream().use { input -> input.copyTo(output) }
            }
            Toast.makeText(requireContext(), R.string.saved, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(requireContext(), R.string.could_not_write, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val ARG_TEXT = "text"
        private const val ARG_FORMAT = "format"
        private const val STATE_PNG = "pendingPng"
        private const val STATE_COLOR = "foreground"
        private const val STATE_EC = "ecLevel"

        // Dark enough on a white background that scanners still read them reliably.
        private val SWATCHES = intArrayOf(
            Color.parseColor("#000000"),
            Color.parseColor("#0D47A1"),
            Color.parseColor("#1B5E20"),
            Color.parseColor("#B71C1C"),
            Color.parseColor("#4A148C")
        )

        fun newInstance(text: String, format: String): CreateResultFragment {
            return CreateResultFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_TEXT, text)
                    putString(ARG_FORMAT, format)
                }
            }
        }
    }
}
