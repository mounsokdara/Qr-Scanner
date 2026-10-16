package com.localscanner.qr

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.localscanner.qr.databinding.ItemSimpleBinding
import com.localscanner.qr.databinding.LayoutSimpleListBinding

class OurAppsFragment : Fragment() {
    private var _binding: LayoutSimpleListBinding? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val binding = LayoutSimpleListBinding.inflate(inflater, container, false)
        _binding = binding
        binding.emptyView.visibility = View.GONE
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = object : RecyclerView.Adapter<Holder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
                val row = ItemSimpleBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                return Holder(row)
            }

            override fun getItemCount(): Int = 1

            override fun onBindViewHolder(holder: Holder, position: Int) {
                holder.binding.simpleTitle.setText(R.string.app_name)
                holder.binding.simpleSubtitle.setText(R.string.our_apps_subtitle)
                holder.binding.simpleIcon.setImageResource(R.drawable.ic_qr)
            }
        }
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.applyToolbar(getString(R.string.our_apps), false, 0, null)
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private class Holder(val binding: ItemSimpleBinding) : RecyclerView.ViewHolder(binding.root)
}
