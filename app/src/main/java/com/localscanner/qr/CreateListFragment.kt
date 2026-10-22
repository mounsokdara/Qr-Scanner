package com.localscanner.qr

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.localscanner.qr.databinding.ItemCreateTypeBinding
import com.localscanner.qr.databinding.ItemSectionBinding
import com.localscanner.qr.databinding.LayoutCreateListBinding

class CreateListFragment : Fragment() {
    private var _binding: LayoutCreateListBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = LayoutCreateListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val adapter = CreateAdapter(buildRows()) { entry -> onEntry(entry) }
        val spans = UiUtil.columns(binding.createList, 360, 3)
        binding.createList.layoutManager = GridLayoutManager(requireContext(), spans).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int = if (adapter.isHeader(position)) spans else 1
            }
        }
        binding.createList.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.applyToolbar(getString(R.string.create_qr), false, 0, null)
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun onEntry(entry: Entry) {
        if (entry.kind == CreateKind.CLIPBOARD) {
            val text = Actions.readClipboard(requireContext())
            if (text.isBlank()) {
                Toast.makeText(requireContext(), R.string.clipboard_empty, Toast.LENGTH_SHORT).show()
                return
            }
            val built = Payloads.build(CreateKind.TEXT, mapOf("v1" to text))
            if (built == null) {
                Toast.makeText(requireContext(), R.string.invalid_entry, Toast.LENGTH_SHORT).show()
                return
            }
            openResult(built)
            return
        }
        (activity as? MainActivity)?.openChild(CreateFormFragment.newInstance(entry.kind))
    }

    private fun openResult(built: BuiltCode) {
        val host = activity as? MainActivity ?: return
        host.store.addRecord(built.text, built.format.name, respectDuplicates = true)
        host.openChild(CreateResultFragment.newInstance(built.text, built.format.name))
    }

    private fun buildRows(): List<CreateRow> {
        val rows = ArrayList<CreateRow>()
        rows.add(CreateRow.Header(getString(R.string.section_qr)))
        rows.addAll(qrEntries().map { CreateRow.Type(it) })
        rows.add(CreateRow.Header(getString(R.string.section_barcode)))
        rows.addAll(barEntries().map { CreateRow.Type(it) })
        return rows
    }

    private fun qrEntries(): List<Entry> = listOf(
        Entry(CreateKind.CLIPBOARD, R.string.type_clipboard, R.drawable.ic_clipboard),
        Entry(CreateKind.URL, R.string.type_url, R.drawable.ic_link),
        Entry(CreateKind.TEXT, R.string.type_text, R.drawable.ic_text),
        Entry(CreateKind.CONTACT, R.string.type_contact, R.drawable.ic_person),
        Entry(CreateKind.EMAIL, R.string.type_email, R.drawable.ic_email),
        Entry(CreateKind.SMS, R.string.type_sms, R.drawable.ic_sms),
        Entry(CreateKind.GEO, R.string.type_geo, R.drawable.ic_place),
        Entry(CreateKind.PHONE, R.string.type_phone, R.drawable.ic_phone),
        Entry(CreateKind.CALENDAR, R.string.type_calendar, R.drawable.ic_event),
        Entry(CreateKind.WIFI, R.string.type_wifi, R.drawable.ic_wifi)
    )

    private fun barEntries(): List<Entry> = listOf(
        Entry(CreateKind.EAN_8, R.string.type_ean8, R.drawable.ic_barcode),
        Entry(CreateKind.EAN_13, R.string.type_ean13, R.drawable.ic_barcode),
        Entry(CreateKind.UPC_E, R.string.type_upce, R.drawable.ic_barcode),
        Entry(CreateKind.UPC_A, R.string.type_upca, R.drawable.ic_barcode),
        Entry(CreateKind.CODE_39, R.string.type_code39, R.drawable.ic_barcode),
        Entry(CreateKind.CODE_93, R.string.type_code93, R.drawable.ic_barcode),
        Entry(CreateKind.CODE_128, R.string.type_code128, R.drawable.ic_barcode),
        Entry(CreateKind.ITF, R.string.type_itf, R.drawable.ic_barcode),
        Entry(CreateKind.CODABAR, R.string.type_codabar, R.drawable.ic_barcode),
        Entry(CreateKind.DATA_MATRIX, R.string.type_datamatrix, R.drawable.ic_qr),
        Entry(CreateKind.PDF_417, R.string.type_pdf417, R.drawable.ic_barcode),
        Entry(CreateKind.AZTEC, R.string.type_aztec, R.drawable.ic_qr)
    )

    data class Entry(val kind: CreateKind, val labelRes: Int, val iconRes: Int)

    sealed class CreateRow {
        data class Header(val title: String) : CreateRow()
        data class Type(val entry: Entry) : CreateRow()
    }

    private class CreateAdapter(
        private val rows: List<CreateRow>,
        private val onClick: (Entry) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount(): Int = rows.size

        fun isHeader(position: Int): Boolean = rows.getOrNull(position) is CreateRow.Header

        override fun getItemViewType(position: Int): Int {
            return if (rows[position] is CreateRow.Header) 0 else 1
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == 0) {
                HeaderHolder(ItemSectionBinding.inflate(inflater, parent, false))
            } else {
                TypeHolder(ItemCreateTypeBinding.inflate(inflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is CreateRow.Header -> (holder as HeaderHolder).binding.sectionTitle.text = row.title
                is CreateRow.Type -> {
                    val typeHolder = holder as TypeHolder
                    typeHolder.binding.typeIcon.setImageResource(row.entry.iconRes)
                    typeHolder.binding.typeLabel.setText(row.entry.labelRes)
                    typeHolder.binding.root.setOnClickListener { onClick(row.entry) }
                }
            }
        }

        private class HeaderHolder(val binding: ItemSectionBinding) : RecyclerView.ViewHolder(binding.root)
        private class TypeHolder(val binding: ItemCreateTypeBinding) : RecyclerView.ViewHolder(binding.root)
    }
}
