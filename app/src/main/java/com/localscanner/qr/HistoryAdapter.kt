package com.localscanner.qr

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.localscanner.qr.databinding.ItemHistoryBinding
import com.localscanner.qr.databinding.ItemSectionBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed class HistoryRow {
    data class Header(val title: String) : HistoryRow()
    data class Item(val record: ScanRecord) : HistoryRow()
}

class HistoryAdapter(
    private val onOpen: (ScanRecord) -> Unit,
    private val onStar: (ScanRecord) -> Unit,
    private val onMore: (ScanRecord, android.view.View) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private val rows = ArrayList<HistoryRow>()
    private val dateFormat = SimpleDateFormat("M/d/yy h:mm:ss a", Locale.getDefault())

    fun submit(items: List<HistoryRow>) {
        rows.clear()
        rows.addAll(items)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size

    fun isHeader(position: Int): Boolean = rows.getOrNull(position) is HistoryRow.Header

    override fun getItemViewType(position: Int): Int {
        return if (rows[position] is HistoryRow.Header) TYPE_HEADER else TYPE_ITEM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderHolder(ItemSectionBinding.inflate(inflater, parent, false))
        } else {
            ItemHolder(ItemHistoryBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is HistoryRow.Header -> (holder as HeaderHolder).binding.sectionTitle.text = row.title
            is HistoryRow.Item -> bindItem(holder as ItemHolder, row.record)
        }
    }

    private fun bindItem(holder: ItemHolder, record: ScanRecord) {
        val binding = holder.binding
        binding.title.text = record.title.ifBlank { Payloads.formatLabel(record.format) }
        binding.subtitle.text = binding.root.context.getString(
            R.string.history_meta,
            dateFormat.format(Date(record.timestamp)),
            record.format
        )
        binding.preview.text = record.text
        val mark = textMark(record)
        if (mark != null) {
            binding.mark.visibility = android.view.View.VISIBLE
            binding.mark.text = mark
            binding.icon.visibility = android.view.View.GONE
        } else {
            binding.mark.visibility = android.view.View.GONE
            binding.icon.visibility = android.view.View.VISIBLE
            val icon = when (record.format) {
                "QR_CODE", "AZTEC", "DATA_MATRIX", "PDF_417" -> R.drawable.ic_qr
                else -> R.drawable.ic_barcode
            }
            binding.icon.setImageResource(icon)
        }
        binding.btnStar.setImageResource(if (record.favorite) R.drawable.ic_star else R.drawable.ic_star_border)
        val tint = if (record.favorite) Color.parseColor("#FFC107") else Color.parseColor("#9A9A9A")
        binding.btnStar.imageTintList = ColorStateList.valueOf(tint)
        binding.root.setOnClickListener { onOpen(record) }
        binding.btnStar.setOnClickListener { onStar(record) }
        binding.btnMore.setOnClickListener { onMore(record, it) }
    }

    private fun textMark(record: ScanRecord): String? {
        if (record.format != "QR_CODE") return null
        val text = record.text.trim()
        val structured = listOf("http://", "https://", "WIFI:", "BEGIN:VCARD", "mailto:", "sms:", "tel:", "geo:", "BEGIN:VCALENDAR", "BEGIN:VEVENT")
        if (structured.any { text.startsWith(it, ignoreCase = true) }) return null
        return "T"
    }

    private class HeaderHolder(val binding: ItemSectionBinding) : RecyclerView.ViewHolder(binding.root)
    private class ItemHolder(val binding: ItemHistoryBinding) : RecyclerView.ViewHolder(binding.root)

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM = 1
    }
}
