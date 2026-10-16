package com.localscanner.qr

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import com.localscanner.qr.databinding.LayoutCreateFormBinding

class CreateFormFragment : Fragment() {
    private var _binding: LayoutCreateFormBinding? = null
    private val binding get() = _binding!!
    private lateinit var kind: CreateKind
    private var security = "WPA"
    private var submitted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = requireArguments().getString(ARG_KIND) ?: CreateKind.TEXT.name
        kind = try {
            CreateKind.valueOf(name)
        } catch (_: Exception) {
            CreateKind.TEXT
        }
        security = savedInstanceState?.getString(STATE_SECURITY) ?: "WPA"
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = LayoutCreateFormBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val specs = fieldsFor(kind)
        val boxes = listOf(
            binding.layout1, binding.layout2, binding.layout3,
            binding.layout4, binding.layout5, binding.layout6
        )
        val inputs = listOf(
            binding.input1, binding.input2, binding.input3,
            binding.input4, binding.input5, binding.input6
        )
        for (index in boxes.indices) {
            val box: TextInputLayout = boxes[index]
            val input = inputs[index]
            if (index < specs.size) {
                val spec = specs[index]
                box.visibility = View.VISIBLE
                box.hint = spec.hint
                if (spec.multi) {
                    input.isSingleLine = false
                    input.minLines = 3
                    input.gravity = Gravity.TOP or Gravity.START
                } else {
                    input.isSingleLine = true
                    input.minLines = 1
                }
                input.inputType = spec.inputType
            } else {
                box.visibility = View.GONE
            }
        }
        binding.groupWifi.visibility = if (kind == CreateKind.WIFI) View.VISIBLE else View.GONE
        binding.wifiSecurity.text = securityLabel(security)
        binding.wifiSecurity.setOnClickListener { pickSecurity() }
        val hint = hintFor(kind)
        if (hint == null) {
            binding.formHint.visibility = View.GONE
        } else {
            binding.formHint.visibility = View.VISIBLE
            binding.formHint.text = hint
        }
    }

    override fun onResume() {
        super.onResume()
        val title = getString(labelRes(kind))
        (activity as? MainActivity)?.applyToolbar(title, true, R.menu.menu_form) { item ->
            if (item.itemId == R.id.action_done) {
                submit()
                true
            } else {
                false
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_SECURITY, security)
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun pickSecurity() {
        val labels = arrayOf("WPA/WPA2", "WEP", "None")
        val values = arrayOf("WPA", "WEP", "nopass")
        val checked = values.indexOf(security).coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.security)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                security = values[which]
                binding.wifiSecurity.text = labels[which]
                dialog.dismiss()
            }
            .show()
    }

    private fun submit() {
        if (submitted || _binding == null) return
        val fields = mapOf(
            "v1" to binding.input1.text?.toString().orEmpty(),
            "v2" to binding.input2.text?.toString().orEmpty(),
            "v3" to binding.input3.text?.toString().orEmpty(),
            "v4" to binding.input4.text?.toString().orEmpty(),
            "v5" to binding.input5.text?.toString().orEmpty(),
            "v6" to binding.input6.text?.toString().orEmpty(),
            "security" to security,
            "hidden" to if (binding.wifiHidden.isChecked) "true" else "false"
        )
        val built = Payloads.build(kind, fields)
        if (built == null) {
            Toast.makeText(requireContext(), R.string.invalid_entry, Toast.LENGTH_LONG).show()
            return
        }
        submitted = true
        val host = activity as? MainActivity ?: return
        host.store.addRecord(built.text, built.format.name, respectDuplicates = true)
        host.openChild(CreateResultFragment.newInstance(built.text, built.format.name))
    }

    private fun securityLabel(value: String): String {
        return when (value) {
            "WEP" -> "WEP"
            "nopass" -> "None"
            else -> "WPA/WPA2"
        }
    }

    private data class FieldSpec(val hint: String, val inputType: Int, val multi: Boolean = false)

    private fun fieldsFor(kind: CreateKind): List<FieldSpec> {
        val text = InputType.TYPE_CLASS_TEXT
        val multi = text or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        val words = text or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        val uri = text or InputType.TYPE_TEXT_VARIATION_URI
        val email = text or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        val person = text or InputType.TYPE_TEXT_VARIATION_PERSON_NAME
        val phone = InputType.TYPE_CLASS_PHONE
        val number = InputType.TYPE_CLASS_NUMBER or
            InputType.TYPE_NUMBER_FLAG_DECIMAL or
            InputType.TYPE_NUMBER_FLAG_SIGNED
        val password = text or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        return when (kind) {
            CreateKind.URL -> listOf(FieldSpec(getString(R.string.hint_url), uri))
            CreateKind.TEXT -> listOf(FieldSpec(getString(R.string.hint_text), multi, true))
            CreateKind.CONTACT -> listOf(
                FieldSpec(getString(R.string.hint_name), person),
                FieldSpec(getString(R.string.hint_organization), words),
                FieldSpec(getString(R.string.hint_phone), phone),
                FieldSpec(getString(R.string.hint_email), email),
                FieldSpec(getString(R.string.hint_address), multi, true),
                FieldSpec(getString(R.string.hint_website), uri)
            )
            CreateKind.EMAIL -> listOf(
                FieldSpec(getString(R.string.hint_to), email),
                FieldSpec(getString(R.string.hint_subject), text or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES),
                FieldSpec(getString(R.string.hint_body), multi, true)
            )
            CreateKind.SMS -> listOf(
                FieldSpec(getString(R.string.hint_number), phone),
                FieldSpec(getString(R.string.hint_message), multi, true)
            )
            CreateKind.GEO -> listOf(
                FieldSpec(getString(R.string.hint_latitude), number),
                FieldSpec(getString(R.string.hint_longitude), number),
                FieldSpec(getString(R.string.hint_label), text)
            )
            CreateKind.PHONE -> listOf(FieldSpec(getString(R.string.hint_phone_number), phone))
            CreateKind.CALENDAR -> listOf(
                FieldSpec(getString(R.string.hint_title), text or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES),
                FieldSpec(getString(R.string.hint_location), words),
                FieldSpec(getString(R.string.hint_start), text),
                FieldSpec(getString(R.string.hint_end), text),
                FieldSpec(getString(R.string.hint_description), multi, true)
            )
            CreateKind.WIFI -> listOf(
                FieldSpec(getString(R.string.hint_ssid), text),
                FieldSpec(getString(R.string.hint_password), password)
            )
            CreateKind.EAN_8 -> listOf(FieldSpec(getString(R.string.hint_ean8), InputType.TYPE_CLASS_NUMBER))
            CreateKind.EAN_13 -> listOf(FieldSpec(getString(R.string.hint_ean13), InputType.TYPE_CLASS_NUMBER))
            CreateKind.UPC_A -> listOf(FieldSpec(getString(R.string.hint_upca), InputType.TYPE_CLASS_NUMBER))
            CreateKind.UPC_E -> listOf(FieldSpec(getString(R.string.hint_upce), InputType.TYPE_CLASS_NUMBER))
            CreateKind.CODE_39 -> listOf(FieldSpec(getString(R.string.hint_code39_field), text or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS))
            CreateKind.CODE_93 -> listOf(FieldSpec(getString(R.string.hint_code93_field), text or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS))
            CreateKind.CODE_128 -> listOf(FieldSpec(getString(R.string.hint_code128_field), text))
            CreateKind.ITF -> listOf(FieldSpec(getString(R.string.hint_itf_field), InputType.TYPE_CLASS_NUMBER))
            CreateKind.CODABAR -> listOf(FieldSpec(getString(R.string.hint_codabar_field), text or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS))
            CreateKind.DATA_MATRIX -> listOf(FieldSpec(getString(R.string.hint_datamatrix_field), multi, true))
            CreateKind.PDF_417 -> listOf(FieldSpec(getString(R.string.hint_pdf417_field), multi, true))
            CreateKind.AZTEC -> listOf(FieldSpec(getString(R.string.hint_aztec_field), multi, true))
            CreateKind.CLIPBOARD -> listOf(FieldSpec(getString(R.string.hint_text), multi, true))
        }
    }

    private fun hintFor(kind: CreateKind): String? {
        return when (kind) {
            CreateKind.EAN_8, CreateKind.EAN_13, CreateKind.UPC_A -> getString(R.string.hint_check_digit)
            CreateKind.UPC_E -> getString(R.string.hint_upce_help)
            CreateKind.ITF -> getString(R.string.hint_itf)
            CreateKind.CODE_39 -> getString(R.string.hint_code39)
            CreateKind.CALENDAR -> getString(R.string.hint_calendar)
            else -> null
        }
    }

    companion object {
        private const val ARG_KIND = "kind"
        private const val STATE_SECURITY = "security"

        fun newInstance(kind: CreateKind): CreateFormFragment {
            return CreateFormFragment().apply {
                arguments = Bundle().apply { putString(ARG_KIND, kind.name) }
            }
        }

        fun labelRes(kind: CreateKind): Int {
            return when (kind) {
                CreateKind.CLIPBOARD -> R.string.type_clipboard
                CreateKind.URL -> R.string.type_url
                CreateKind.TEXT -> R.string.type_text
                CreateKind.CONTACT -> R.string.type_contact
                CreateKind.EMAIL -> R.string.type_email
                CreateKind.SMS -> R.string.type_sms
                CreateKind.GEO -> R.string.type_geo
                CreateKind.PHONE -> R.string.type_phone
                CreateKind.CALENDAR -> R.string.type_calendar
                CreateKind.WIFI -> R.string.type_wifi
                CreateKind.EAN_8 -> R.string.type_ean8
                CreateKind.EAN_13 -> R.string.type_ean13
                CreateKind.UPC_E -> R.string.type_upce
                CreateKind.UPC_A -> R.string.type_upca
                CreateKind.CODE_39 -> R.string.type_code39
                CreateKind.CODE_93 -> R.string.type_code93
                CreateKind.CODE_128 -> R.string.type_code128
                CreateKind.ITF -> R.string.type_itf
                CreateKind.CODABAR -> R.string.type_codabar
                CreateKind.DATA_MATRIX -> R.string.type_datamatrix
                CreateKind.PDF_417 -> R.string.type_pdf417
                CreateKind.AZTEC -> R.string.type_aztec
            }
        }
    }
}
