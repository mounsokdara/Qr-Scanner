package com.localscanner.qr

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.zxing.BarcodeFormat
import com.localscanner.qr.databinding.LayoutMyQrBinding
import java.io.File
import java.io.FileOutputStream

class MyQrFragment : Fragment() {
    private var _binding: LayoutMyQrBinding? = null
    private val binding get() = _binding!!
    private lateinit var store: Store
    private var editing = true
    private var populated = false
    private var bitmap: Bitmap? = null
    private var pendingPng: String? = null

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            editing = false
            render()
        }
    }

    private val createPng = registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) copyPending(uri)
    }
    private val writePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) saveCurrent() else stageAndCreate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(requireContext())
        editing = savedInstanceState?.getBoolean(STATE_EDITING)
            ?: !store.getMyQr().saved
        pendingPng = savedInstanceState?.getString(STATE_PNG)
        requireActivity().onBackPressedDispatcher.addCallback(this, backCallback)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = LayoutMyQrBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        store = (requireActivity() as MainActivity).store
        binding.inputPhone.inputType = InputType.TYPE_CLASS_PHONE
        binding.inputEmail.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        binding.inputWebsite.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        binding.inputAddress.isSingleLine = false
        binding.inputAddress.minLines = 2
        binding.inputNote.isSingleLine = false
        binding.inputNote.minLines = 2
        binding.btnSave.setOnClickListener { saveCurrent() }
        binding.btnShare.setOnClickListener {
            val image = bitmap ?: return@setOnClickListener
            Actions.shareBitmap(requireActivity(), image)
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_EDITING, editing)
        outState.putString(STATE_PNG, pendingPng)
    }

    override fun onDestroyView() {
        _binding?.codeImage?.setImageDrawable(null)
        bitmap?.recycle()
        bitmap = null
        _binding = null
        super.onDestroyView()
    }

    private fun render() {
        if (_binding == null) return
        val host = activity as? MainActivity ?: return
        if (editing || !store.getMyQr().saved) {
            editing = true
            showForm(host)
        } else {
            showResult(host)
        }
        val profile = store.getMyQr()
        backCallback.isEnabled = editing && profile.saved
    }

    private fun showForm(host: MainActivity) {
        binding.formGroup.visibility = View.VISIBLE
        binding.resultGroup.visibility = View.GONE
        if (!populated) {
            val profile = store.getMyQr()
            binding.inputName.setText(profile.name)
            binding.inputOrg.setText(profile.organization)
            binding.inputPhone.setText(profile.phone)
            binding.inputEmail.setText(profile.email)
            binding.inputAddress.setText(profile.address)
            binding.inputWebsite.setText(profile.website)
            binding.inputNote.setText(profile.note)
            populated = true
        }
        host.applyToolbar(getString(R.string.my_qr), false, R.menu.menu_form) { item ->
            if (item.itemId == R.id.action_done) {
                saveProfile()
                true
            } else {
                false
            }
        }
    }

    private fun showResult(host: MainActivity) {
        binding.formGroup.visibility = View.GONE
        binding.resultGroup.visibility = View.VISIBLE
        val profile = store.getMyQr()
        val card = Payloads.vCard(
            profile.name,
            profile.organization,
            profile.phone,
            profile.email,
            profile.address,
            profile.website,
            profile.note
        )
        val encoded = if (card == null) null else Encoder.encode(card, BarcodeFormat.QR_CODE)
        binding.codeImage.setImageDrawable(null)
        bitmap?.recycle()
        bitmap = encoded
        if (encoded == null || card == null) {
            editing = true
            showForm(host)
            return
        }
        binding.codeImage.setImageBitmap(encoded)
        binding.codeText.text = card
        host.applyToolbar(getString(R.string.my_qr), false, R.menu.menu_edit) { item ->
            if (item.itemId == R.id.action_edit) {
                editing = true
                populated = false
                render()
                true
            } else {
                false
            }
        }
    }

    private fun saveProfile() {
        val profile = MyQrProfile(
            name = binding.inputName.text?.toString()?.trim().orEmpty(),
            organization = binding.inputOrg.text?.toString()?.trim().orEmpty(),
            phone = binding.inputPhone.text?.toString()?.trim().orEmpty(),
            email = binding.inputEmail.text?.toString()?.trim().orEmpty(),
            address = binding.inputAddress.text?.toString()?.trim().orEmpty(),
            website = binding.inputWebsite.text?.toString()?.trim().orEmpty(),
            note = binding.inputNote.text?.toString()?.trim().orEmpty(),
            saved = true
        )
        val card = Payloads.vCard(
            profile.name,
            profile.organization,
            profile.phone,
            profile.email,
            profile.address,
            profile.website,
            profile.note
        )
        if (card == null || !Encoder.canEncode(card, BarcodeFormat.QR_CODE)) {
            Toast.makeText(requireContext(), R.string.invalid_entry, Toast.LENGTH_LONG).show()
            return
        }
        store.saveMyQr(profile)
        store.addRecord(card, BarcodeFormat.QR_CODE.name, respectDuplicates = true)
        editing = false
        populated = true
        render()
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
            val file = File(requireContext().cacheDir, "pending_my_qr.png")
            FileOutputStream(file).use { stream ->
                image.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
            pendingPng = file.absolutePath
            createPng.launch("my_qr.png")
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
        private const val STATE_EDITING = "editing"
        private const val STATE_PNG = "pendingPng"
    }
}
