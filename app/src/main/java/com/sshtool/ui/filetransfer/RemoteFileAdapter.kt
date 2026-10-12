package com.sshtool.ui.filetransfer

import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.sshtool.R
import com.sshtool.databinding.ItemRemoteFileBinding
import com.sshtool.ssh.RemoteFileEntry
import java.text.DateFormat
import java.util.Date

internal class RemoteFileAdapter(
    private val onClick: (RemoteFileEntry) -> Unit
) : ListAdapter<RemoteFileEntry, RemoteFileAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(
        ItemRemoteFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemRemoteFileBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: RemoteFileEntry) = with(binding) {
            val context = root.context
            val safeName = entry.name.escapeRemoteText()
            tvFileName.text = safeName
            fileIcon.setImageResource(
                if (entry.isDirectory) android.R.drawable.ic_menu_agenda
                else android.R.drawable.ic_menu_save
            )

            tvFileDetails.text = when {
                entry.isSymbolicLink -> context.getString(R.string.file_transfer_symbolic_link)
                entry.isDirectory -> context.getString(R.string.file_transfer_folder)
                else -> {
                    val size = if (entry.size >= 0) {
                        Formatter.formatShortFileSize(context, entry.size)
                    } else {
                        context.getString(R.string.file_transfer_unknown_size)
                    }
                    val modified = entry.modifiedTimeMillis?.let {
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                            .format(Date(it))
                    }
                    if (modified == null) size
                    else context.getString(R.string.file_transfer_file_size, size, modified)
                }
            }
            openIndicator.isVisible = true
            root.contentDescription = context.getString(R.string.file_transfer_item_open, safeName)
            root.setOnClickListener { onClick(entry) }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<RemoteFileEntry>() {
        override fun areItemsTheSame(oldItem: RemoteFileEntry, newItem: RemoteFileEntry): Boolean =
            oldItem.path == newItem.path

        override fun areContentsTheSame(oldItem: RemoteFileEntry, newItem: RemoteFileEntry): Boolean =
            oldItem == newItem
    }
}

/**
 * Remote names can contain controls and bidirectional formatting characters.
 * Escape by Unicode code point so supplementary FORMAT characters cannot
 * visually disguise the filename either.
 */
internal fun String.escapeRemoteText(): String = buildString(length) {
    this@escapeRemoteText.forEachRemoteCodePoint { codePoint ->
        if (codePoint.isUnsafeRemoteDisplayCodePoint()) {
            if (codePoint <= 0xFFFF) append("\\u%04X".format(codePoint))
            else append("\\U%08X".format(codePoint))
        } else {
            appendCodePoint(codePoint)
        }
    }
}

internal fun String.hasUnsafeRemoteDisplayCodePoint(): Boolean {
    var unsafe = false
    forEachRemoteCodePoint { codePoint ->
        if (codePoint.isUnsafeRemoteDisplayCodePoint()) unsafe = true
    }
    return unsafe
}

/** A display-only local filename suggestion; never use this as an SFTP path. */
internal fun String.toSafeDocumentName(): String {
    val safe = buildString(length) {
        this@toSafeDocumentName.forEachRemoteCodePoint { codePoint ->
            if (codePoint == '/'.code || codePoint == '\\'.code ||
                codePoint.isUnsafeRemoteDisplayCodePoint()) {
                append('_')
            } else {
                appendCodePoint(codePoint)
            }
        }
    }.trim()
    return safe.takeIf { candidate ->
        candidate.isNotBlank() && candidate.any { it != '_' && !it.isWhitespace() }
    } ?: "download"
}

internal inline fun String.forEachRemoteCodePoint(action: (Int) -> Unit) {
    var index = 0
    while (index < length) {
        val codePoint = Character.codePointAt(this, index)
        action(codePoint)
        index += Character.charCount(codePoint)
    }
}

internal fun Int.isUnsafeRemoteDisplayCodePoint(): Boolean {
    val type = Character.getType(this)
    return Character.isISOControl(this) || this == 0x7F ||
        type == Character.FORMAT.toInt() ||
        type == Character.LINE_SEPARATOR.toInt() ||
        type == Character.PARAGRAPH_SEPARATOR.toInt() ||
        type == Character.SURROGATE.toInt()
}
