package com.localscanner.qr

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
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

    private val createPng = registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) copyPending(uri)
    }
    private val writePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) saveCurrent() else stageAndCreate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingPng = savedInstanceState?.getString(STATE_PNG)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = LayoutCreateResultBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val text = requireArguments().getString(ARG_TEXT).orEmpty()
        val format = Encoder.formatOf(requireArguments().getString(ARG_FORMAT).orEmpty()) ?: BarcodeFormat.QR_CODE
        binding.codeText.text = text
        binding.btnSave.setOnClickListener { saveCurrent() }
        binding.btnShare.setOnClickListener {
            val image = bitmap ?: return@setOnClickListener
            Actions.shareBitmap(requireActivity(), image)
        }
        io.execute {
            val encoded = Encoder.encode(text, format)
            mainHandler.post {
                if (_binding == null) {
                    encoded?.recycle()
                    return@post
                }
                bitmap = encoded
                if (encoded == null) {
                    Toast.makeText(requireContext(), R.string.invalid_entry, Toast.LENGTH_LONG).show()
                } else {
                    binding.codeImage.setImageBitmap(encoded)
                }
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
