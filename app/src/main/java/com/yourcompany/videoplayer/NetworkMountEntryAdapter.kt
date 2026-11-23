package com.yourcompany.videoplayer

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat

internal class NetworkMountEntryAdapter(
    private val context: Context,
    private val durationMillis: (NetworkMountEntry) -> Long?,
    private val onDurationNeeded: (NetworkMountEntry) -> Unit
) : BaseAdapter() {

    var entries: List<NetworkMountEntry> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun getCount(): Int = entries.size

    override fun getItem(position: Int): NetworkMountEntry = entries[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(parent.context)
            .inflate(R.layout.item_network_mount_entry, parent, false)
        val holder = view.tag as? EntryHolder ?: EntryHolder(view).also { view.tag = it }
        val entry = getItem(position)

        holder.name.text = entry.name
        if (entry.isDirectory) {
            holder.icon.setImageResource(R.drawable.ios_folder)
            holder.icon.imageTintList = null
            holder.details.text = NetworkEntryFormatter.details(entry, null)
        } else {
            holder.icon.setImageResource(R.drawable.ic_video)
            holder.icon.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.color_primary)
            )
            val duration = durationMillis(entry)
            if (duration == null) onDurationNeeded(entry)
            holder.details.text = NetworkEntryFormatter.details(entry, duration)
        }
        return view
    }

    private class EntryHolder(view: View) {
        val icon: ImageView = view.findViewById(R.id.network_entry_icon)
        val name: TextView = view.findViewById(R.id.network_entry_name)
        val details: TextView = view.findViewById(R.id.network_entry_details)
    }
}
