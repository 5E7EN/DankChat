package com.flxrs.dankchat.chat.menu

import android.graphics.Rect
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.flxrs.dankchat.databinding.EmoteMenuTabBinding
import com.flxrs.dankchat.service.twitch.emote.GenericEmote

class EmoteMenuAdapter(private val onEmoteClick: (emote: String) -> Unit) :
    ListAdapter<List<EmoteItem>, EmoteMenuAdapter.ViewHolder>(DetectDiff()) {

    inner class ViewHolder(val adapter: EmoteAdapter, val binding: EmoteMenuTabBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun getItemCount() = EmoteMenuTab.values().size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val adapter = EmoteAdapter(onEmoteClick)
        return ViewHolder(
            adapter,
            EmoteMenuTabBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            ).apply {
                emoteList.layoutManager = GridLayoutManager(parent.context, 6).apply {
                    spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                        override fun getSpanSize(position: Int): Int {
                            return when(adapter.getItemViewType(position)) {
                                EmoteAdapter.ITEM_VIEW_TYPE_HEADER -> 6
                                else -> 1
                            }
                        }
                    }
                }
                emoteList.adapter = adapter
                emoteList.addItemDecoration(object : RecyclerView.ItemDecoration() {
                    override fun getItemOffsets(
                        outRect: Rect,
                        view: View,
                        parent: RecyclerView,
                        state: RecyclerView.State
                    ) {
                        outRect.setEmpty()
                    }
                })
            }
        )
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val emotes = getItem(position)
        holder.adapter.submitList(emotes)
    }

    private class DetectDiff : DiffUtil.ItemCallback<List<EmoteItem>>() {
        override fun areItemsTheSame(
            oldItem: List<EmoteItem>,
            newItem: List<EmoteItem>
        ): Boolean {
            return oldItem == newItem
        }

        override fun areContentsTheSame(
            oldItem: List<EmoteItem>,
            newItem: List<EmoteItem>
        ): Boolean {
            return oldItem == newItem
        }
    }
}