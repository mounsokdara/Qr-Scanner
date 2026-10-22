package com.localscanner.qr

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.localscanner.qr.databinding.DialogTextBinding
import com.localscanner.qr.databinding.LayoutSettingsBinding

class SettingsFragment : Fragment() {
    private var _binding: LayoutSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var store: Store
    private var updating = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = LayoutSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        store = (requireActivity() as MainActivity).store
        load()
        binding.rowBatch.setOnClickListener { binding.checkBatch.toggle() }
        binding.rowAutofocus.setOnClickListener { binding.checkAutofocus.toggle() }
        binding.rowTouch.setOnClickListener {
            if (binding.checkAutofocus.isChecked) binding.checkTouch.toggle()
        }
        binding.rowDuplicates.setOnClickListener { binding.checkDuplicates.toggle() }
        binding.rowBrowser.setOnClickListener { binding.checkBrowser.toggle() }
        binding.rowAddHistory.setOnClickListener { binding.checkAddHistory.toggle() }
        binding.rowAutoOpen.setOnClickListener { binding.checkAutoOpen.toggle() }
        binding.rowSound.setOnClickListener { binding.checkSound.toggle() }
        binding.rowVibrate.setOnClickListener { binding.checkVibrate.toggle() }
        binding.checkBatch.setOnCheckedChangeListener { _, _ -> persist() }
        binding.checkAutofocus.setOnCheckedChangeListener { _, _ ->
            refreshTouch()
            persist()
        }
        binding.checkTouch.setOnCheckedChangeListener { _, _ -> persist() }
        binding.checkDuplicates.setOnCheckedChangeListener { _, _ -> persist() }
        binding.checkBrowser.setOnCheckedChangeListener { _, _ -> persist() }
        binding.checkAddHistory.setOnCheckedChangeListener { _, _ -> persist() }
        binding.checkAutoOpen.setOnCheckedChangeListener { _, _ -> persist() }
        binding.checkSound.setOnCheckedChangeListener { _, _ -> persist() }
        binding.checkVibrate.setOnCheckedChangeListener { _, _ -> persist() }
        binding.rowCustom.setOnClickListener { editCustomAction() }
        binding.rowCamera.setOnClickListener { pickCamera() }
        binding.rowEngine.setOnClickListener { pickEngine() }
        binding.rowCountry.setOnClickListener { pickCountry() }
        refreshTouch()
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.applyToolbar(getString(R.string.settings), false, 0, null)
        if (_binding != null) {
            load()
            refreshTouch()
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun load() {
        val settings = store.getSettings()
        updating = true
        binding.checkBatch.isChecked = settings.batchScanMode
        binding.checkAutofocus.isChecked = settings.useAutoFocus
        binding.checkTouch.isChecked = settings.touchFocus
        binding.checkDuplicates.isChecked = settings.keepDuplicates
        binding.checkBrowser.isChecked = settings.useInAppBrowser
        binding.checkAddHistory.isChecked = settings.addScansToHistory
        binding.checkAutoOpen.isChecked = settings.autoOpenUrls
        binding.checkSound.isChecked = settings.soundOnScan
        binding.checkVibrate.isChecked = settings.vibrateOnScan
        binding.valueCustom.text = settings.customActionUrl.ifBlank { getString(R.string.none) }
        binding.valueCamera.text = cameraLabel(settings.cameraId)
        binding.valueEngine.text = engineLabel(settings.searchEngine)
        binding.valueCountry.text = countryLabel(settings.searchCountry)
        updating = false
    }

    private fun persist() {
        if (updating || _binding == null) return
        val current = store.getSettings()
        store.saveSettings(
            current.copy(
                batchScanMode = binding.checkBatch.isChecked,
                useAutoFocus = binding.checkAutofocus.isChecked,
                touchFocus = binding.checkTouch.isChecked,
                keepDuplicates = binding.checkDuplicates.isChecked,
                useInAppBrowser = binding.checkBrowser.isChecked,
                addScansToHistory = binding.checkAddHistory.isChecked,
                autoOpenUrls = binding.checkAutoOpen.isChecked,
                soundOnScan = binding.checkSound.isChecked,
                vibrateOnScan = binding.checkVibrate.isChecked
            )
        )
    }

    private fun refreshTouch() {
        val enabled = binding.checkAutofocus.isChecked
        binding.rowTouch.isEnabled = enabled
        binding.checkTouch.isEnabled = enabled
        binding.rowTouch.alpha = if (enabled) 1f else 0.4f
    }

    private fun editCustomAction() {
        val dialogBinding = DialogTextBinding.inflate(layoutInflater)
        val current = store.getSettings().customActionUrl
        dialogBinding.dialogInput.setText(current)
        dialogBinding.dialogInput.hint = "https://example.com/?q={code}"
        dialogBinding.dialogInput.inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.custom_action)
            .setMessage(R.string.custom_action_message)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.ok) { _, _ ->
                val value = dialogBinding.dialogInput.text?.toString()?.trim().orEmpty()
                val lower = value.lowercase()
                if (value.isNotEmpty() && !lower.startsWith("http://") && !lower.startsWith("https://")) {
                    Toast.makeText(requireContext(), R.string.custom_action_http, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                store.updateSettings { it.copy(customActionUrl = value) }
                binding.valueCustom.text = value.ifBlank { getString(R.string.none) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun pickCamera() {
        val described = describeCameras()
        if (described.isEmpty()) {
            Toast.makeText(requireContext(), R.string.no_camera, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = described.map { it.second }.toTypedArray()
        val current = store.getSettings().cameraId
        val checked = described.indexOfFirst { it.first == current }.let { if (it < 0) 0 else it }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.camera)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                store.updateSettings { it.copy(cameraId = described[which].first) }
                binding.valueCamera.text = described[which].second
                dialog.dismiss()
            }
            .show()
    }

    private fun pickEngine() {
        val values = arrayOf("device", "google", "bing", "duckduckgo")
        val labels = arrayOf(
            getString(R.string.engine_device),
            "Google",
            "Bing",
            "DuckDuckGo"
        )
        val checked = values.indexOf(store.getSettings().searchEngine).coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.search_engine)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                store.updateSettings { it.copy(searchEngine = values[which]) }
                binding.valueEngine.text = labels[which]
                dialog.dismiss()
            }
            .show()
    }

    private fun pickCountry() {
        val labels = Payloads.COUNTRIES.map { getString(R.string.country_name, it.second, it.first) }.toTypedArray()
        val codes = Payloads.COUNTRIES.map { it.first }
        val checked = codes.indexOf(store.getSettings().searchCountry).coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.search_country)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                store.updateSettings { it.copy(searchCountry = codes[which]) }
                binding.valueCountry.text = labels[which]
                dialog.dismiss()
            }
            .show()
    }

    private fun engineLabel(engine: String): String {
        return when (engine) {
            "google" -> "Google"
            "bing" -> "Bing"
            "duckduckgo" -> "DuckDuckGo"
            else -> getString(R.string.engine_device)
        }
    }

    private fun countryLabel(code: String): String {
        val match = Payloads.COUNTRIES.firstOrNull { it.first.equals(code, ignoreCase = true) }
        return if (match == null) code else getString(R.string.country_name, match.second, match.first)
    }

    private fun cameraLabel(id: String): String {
        val described = describeCameras()
        if (described.isEmpty()) return getString(R.string.camera_recommended)
        val match = described.firstOrNull { it.first == id }
        return match?.second ?: described.first().second
    }

    private fun describeCameras(): List<Pair<String, String>> {
        val manager = requireContext().getSystemService(CameraManager::class.java) ?: return emptyList()
        val ids = try {
            manager.cameraIdList
        } catch (_: Exception) {
            emptyArray()
        }
        return ids.mapIndexed { index, id ->
            val facing = try {
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
            } catch (_: Exception) {
                null
            }
            val facingLabel = when (facing) {
                CameraCharacteristics.LENS_FACING_FRONT -> getString(R.string.facing_front)
                CameraCharacteristics.LENS_FACING_BACK -> getString(R.string.facing_back)
                else -> getString(R.string.facing_external)
            }
            val label = if (index == 0) {
                getString(R.string.camera_recommended)
            } else {
                getString(R.string.camera_n, index + 1, facingLabel)
            }
            id to label
        }
    }
}
