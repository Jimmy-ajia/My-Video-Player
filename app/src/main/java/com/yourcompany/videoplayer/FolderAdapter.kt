package com.yourcompany.videoplayer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class FolderAdapter(
    private val context: Context,
    private val folderList: ArrayList<FolderModel>,
    private val onFolderClick: (FolderModel) -> Unit
) : RecyclerView.Adapter<FolderAdapter.FolderViewHolder>() {

    class FolderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val folderIcon: ImageView = view.findViewById(R.id.folderIcon)
        val folderName: TextView = view.findViewById(R.id.folderName)
        val videoCount: TextView = view.findViewById(R.id.videoCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FolderViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.folder_item, parent, false)
        return FolderViewHolder(view)
    }

    override fun onBindViewHolder(holder: FolderViewHolder, position: Int) {
        val folder = folderList[position]

        holder.folderName.text = folder.folderName
        holder.videoCount.text = "${folder.videoCount}"

        holder.folderIcon.setImageResource(R.drawable.ios_folder)

        holder.itemView.setOnClickListener {
            // Check if there are subfolders by checking paths (Simple way)
            // If it's a leaf folder (no subfolders), go to video list.
            // But usually we go inside for better navigation.
            onFolderClick(folder)
            
            // If the folder has videos directly, we can also provide a button to play all.
            // But for now, we follow the "go deeper" logic.
        }
        
        // Long click to directly go to video list if needed
        holder.itemView.setOnLongClickListener {
            val intent = Intent(context, VideoListActivity::class.java).apply {
                putExtra("FOLDER_NAME", folder.folderName)
                putExtra("FOLDER_PATH", folder.path)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }
    }

    override fun getItemCount(): Int = folderList.size
}