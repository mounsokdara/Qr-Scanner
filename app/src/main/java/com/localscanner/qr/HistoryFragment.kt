package com.localscanner.qr

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SearchView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.zxing.BarcodeFormat
import com.localscanner.qr.databinding.DialogTextBinding
import com.localscanner.qr.databinding.LayoutHistoryBinding
import com.localscanner.qr.databinding.LayoutSimpleListBinding
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class HistoryFragment : Fragment() {
    private var favoritesOnly = false
    private lateinit var store: Store
    private lateinit var list: RecyclerView
    private lateinit var emptyView: View
    private lateinit var emptyTitle: TextView
    private lateinit var emptySubtitle: TextView
    private lateinit var adapter: HistoryAdapter
    private var pendingPath: String? = null
    private var pendingKind: String? = null
    private var query = ""

    private val exportTxt = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let { writeStaged(it) }
    }
    private val exportCsv = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { writeStaged(it) }
    }
    private val exportPng = registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        uri?.let { writeStaged(it) }
    }
    private val importCsv = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importUri(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        favoritesOnly = arguments?.getBoolean(ARG_FAVORITES) == true
        pendingPath = savedInstanceState?.getString(STATE_PATH)
        pendingKind = savedInstanceState?.getString(STATE_KIND)
        query = savedInstanceState?.getString(STATE_QUERY).orEmpty()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return if (favoritesOnly) {
            val binding = LayoutSimpleListBinding.inflate(inflater, container, false)
            list = binding.list
            emptyView = binding.emptyView
            emptyTitle = binding.emptyTitle
            emptySubtitle = binding.emptySubtitle
            binding.root
        } else {
            val binding = LayoutHistoryBinding.inflate(inflater, container, false)
            list = binding.historyList
            emptyView = binding.emptyView
            emptyTitle = binding.emptyTitle
            emptySubtitle = binding.emptySubtitle
            binding.root
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        store = (requireActivity() as MainActivity).store
        emptyTitle.setText(R.string.empty)
        emptySubtitle.setText(if (favoritesOnly) R.string.no_favorites_yet else R.string.no_history_yet)
        adapter = HistoryAdapter(
            onOpen = { record -> (activity as? MainActivity)?.showExistingRecord(record) },
            onStar = { record ->
                store.setFavorite(record.id, !record.favorite)
                reload()
            },
            onMore = { record, anchor -> showItemMenu(record, anchor) }
        )
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        val title = if (favoritesOnly) getString(R.string.favorites) else getString(R.string.history)
        val main = activity as? MainActivity
        main?.applyToolbar(title, false, R.menu.menu_history, ::onToolbar)
        setupSearch(main)
        reload()
    }

    private fun setupSearch(main: MainActivity?) {
        val item = main?.toolbarMenu()?.findItem(R.id.action_search) ?: return
        val searchView = item.actionView as? SearchView ?: return
        searchView.queryHint = getString(R.string.search_history)
        searchView.maxWidth = Int.MAX_VALUE
        if (query.isNotEmpty()) {
            item.expandActionView()
            searchView.setQuery(query, false)
        }
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(text: String?): Boolean = false

            override fun onQueryTextChange(text: String?): Boolean {
                query = text.orEmpty().trim()
                reload()
                return true
            }
        })
        item.setOnActionExpandListener(object : android.view.MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean = true

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                query = ""
                reload()
                return true
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PATH, pendingPath)
        outState.putString(STATE_KIND, pendingKind)
        outState.putString(STATE_QUERY, query)
    }

    private fun onToolbar(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_delete_all -> {
                deleteAll()
                true
            }
            R.id.action_import_csv -> {
                importCsv.launch(arrayOf("text/*", "text/csv", "text/comma-separated-values", "*/*"))
                true
            }
            R.id.action_export_all_txt -> {
                exportRecords(currentRecords(), "txt")
                true
            }
            R.id.action_export_all_csv -> {
                exportRecords(currentRecords(), "csv")
                true
            }
            else -> false
        }
    }

    private fun showItemMenu(record: ScanRecord, anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menuInflater.inflate(R.menu.menu_history_item, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_delete -> deleteOne(record)
                R.id.action_export_txt -> exportRecords(listOf(record), "txt")
                R.id.action_export_csv -> exportRecords(listOf(record), "csv")
                R.id.action_export_png -> exportPng(record)
                R.id.action_share -> Actions.shareText(requireActivity(), record.text)
                R.id.action_copy -> Actions.copy(requireContext(), record.text)
                R.id.action_rename -> rename(record)
            }
            true
        }
        popup.show()
    }

    private fun rename(record: ScanRecord) {
        val dialogBinding = DialogTextBinding.inflate(layoutInflater)
        dialogBinding.dialogInput.setText(record.title)
        dialogBinding.dialogInput.setSelection(dialogBinding.dialogInput.text?.length ?: 0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.rename)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.ok) { _, _ ->
                val title = dialogBinding.dialogInput.text?.toString()?.trim().orEmpty()
                if (title.isNotEmpty()) {
                    store.rename(record.id, title)
                    reload()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun deleteOne(record: ScanRecord) {
        store.delete(record.id)
        reload()
        Actions.snackbar(requireView(), getString(R.string.deleted), getString(R.string.undo)) {
            store.restore(record)
            reload()
        }
    }

    private fun deleteAll() {
        val snapshot = store.getHistory()
        val removing = if (favoritesOnly) snapshot.filter { it.favorite } else snapshot
        if (removing.isEmpty()) return
        val keep = if (favoritesOnly) snapshot.filter { !it.favorite } else emptyList()
        store.replaceAll(keep)
        reload()
        Actions.snackbar(requireView(), getString(R.string.deleted), getString(R.string.undo)) {
            store.restoreAll(removing)
            reload()
        }
    }

    private fun currentRecords(): List<ScanRecord> {
        val all = store.getHistory()
        return if (favoritesOnly) all.filter { it.favorite } else all
    }

    private fun reload() {
        if (!::adapter.isInitialized) return
        val needle = query.lowercase(Locale.getDefault())
        val records = currentRecords()
            .filter { record ->
                needle.isEmpty() ||
                    record.title.lowercase(Locale.getDefault()).contains(needle) ||
                    record.text.lowercase(Locale.getDefault()).contains(needle) ||
                    Payloads.formatLabel(record.format).lowercase(Locale.getDefault()).contains(needle)
            }
            .sortedByDescending { it.timestamp }
        emptySubtitle.setText(
            when {
                query.isNotEmpty() -> R.string.no_results
                favoritesOnly -> R.string.no_favorites_yet
                else -> R.string.no_history_yet
            }
        )
        emptyView.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
        list.visibility = if (records.isEmpty()) View.GONE else View.VISIBLE
        adapter.submit(group(records))
    }

    private fun group(records: List<ScanRecord>): List<HistoryRow> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val yesterday = today.minusDays(1)
        val formatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())
        val rows = ArrayList<HistoryRow>()
        var last: LocalDate? = null
        for (record in records) {
            val date = Instant.ofEpochMilli(record.timestamp).atZone(zone).toLocalDate()
            if (date != last) {
                val label = when (date) {
                    today -> getString(R.string.today)
                    yesterday -> getString(R.string.yesterday)
                    else -> date.format(formatter)
                }
                rows.add(HistoryRow.Header(label))
                last = date
            }
            rows.add(HistoryRow.Item(record))
        }
        return rows
    }

    private fun exportPng(record: ScanRecord) {
        val format = Encoder.formatOf(record.format) ?: BarcodeFormat.QR_CODE
        val bitmap = Encoder.encode(record.text, format) ?: Encoder.encode(record.text, BarcodeFormat.QR_CODE)
        if (bitmap == null) {
            Toast.makeText(requireContext(), R.string.invalid_entry, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val file = File(requireContext().cacheDir, "pending_export.png")
            FileOutputStream(file).use { stream ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
            }
            pendingPath = file.absolutePath
            pendingKind = "png"
            val base = record.title.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(40).ifBlank { "code" }
            exportPng.launch("$base.png")
        } catch (_: Exception) {
            Toast.makeText(requireContext(), R.string.could_not_write, Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportRecords(records: List<ScanRecord>, kind: String) {
        if (records.isEmpty()) return
        val text = if (kind == "csv") Transfer.toCsv(records) else Transfer.toTxt(records)
        val file = File(requireContext().cacheDir, "pending_export.$kind")
        file.writeText(text)
        pendingPath = file.absolutePath
        pendingKind = kind
        val name = if (records.size == 1) "scan.$kind" else "scans.$kind"
        if (kind == "csv") exportCsv.launch(name) else exportTxt.launch(name)
    }

    private fun writeStaged(uri: Uri) {
        val path = pendingPath
        if (path.isNullOrBlank()) return
        try {
            requireContext().contentResolver.openOutputStream(uri)?.use { stream ->
                if (pendingKind == "png") {
                    File(path).inputStream().use { input -> input.copyTo(stream) }
                } else {
                    stream.write(File(path).readText().toByteArray(Charsets.UTF_8))
                }
            } ?: throw IllegalStateException("stream")
        } catch (_: Exception) {
            Toast.makeText(requireContext(), R.string.could_not_write, Toast.LENGTH_SHORT).show()
        }
    }

    private fun importUri(uri: Uri) {
        try {
            val text = requireContext().contentResolver.openInputStream(uri)?.use { stream ->
                stream.bufferedReader(Charsets.UTF_8).readText()
            }.orEmpty()
            val records = Transfer.parseCsv(text)
            if (records.isEmpty()) {
                Toast.makeText(requireContext(), R.string.nothing_to_import, Toast.LENGTH_SHORT).show()
                return
            }
            store.importRecords(records)
            reload()
            Toast.makeText(requireContext(), getString(R.string.imported_n, records.size), Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(requireContext(), R.string.could_not_write, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val ARG_FAVORITES = "favorites"
        private const val STATE_PATH = "pendingPath"
        private const val STATE_KIND = "pendingKind"
        private const val STATE_QUERY = "query"

        fun newHistory(): HistoryFragment = HistoryFragment().apply {
            arguments = Bundle().apply { putBoolean(ARG_FAVORITES, false) }
        }

        fun newFavorites(): HistoryFragment = HistoryFragment().apply {
            arguments = Bundle().apply { putBoolean(ARG_FAVORITES, true) }
        }
    }
}
