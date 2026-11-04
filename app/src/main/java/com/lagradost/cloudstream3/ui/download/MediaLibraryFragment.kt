package com.lagradost.cloudstream3.ui.download

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.player.OfflinePlaybackHelper.playUri
import com.lagradost.cloudstream3.utils.DOWNLOAD_EPISODE_CACHE
import com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.DataStore.getKeys
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.getDownloadFileInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.io.File

private data class LibraryVideo(
    val uri: Uri,
    val title: String,
    val folder: String,
    val duration: Long,
    val size: Long,
    val dateAdded: Long,
    val fromApp: Boolean
)

class MediaLibraryFragment : Fragment() {
    private lateinit var list: RecyclerView
    private lateinit var search: EditText
    private lateinit var folderSpinner: Spinner
    private lateinit var sortSpinner: Spinner
    private lateinit var layoutButton: TextView
    private lateinit var status: TextView
    private val adapter = MediaLibraryAdapter { item ->
        playUri(requireActivity(), item.uri)
    }
    private var allVideos: List<LibraryVideo> = emptyList()
    private var selectedFolder: String = "All folders"
    private var sortMode: Int = 0
    private var gridMode = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        refreshLibrary(requestPermission = false)
        if (!granted) status.text = "Storage permission denied. CloudStream downloads are still shown."
    }

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 12, 16, 8)
            setBackgroundColor(android.graphics.Color.BLACK)
        }

        val heading = TextView(requireContext()).apply {
            text = "Video library"
            textSize = 22f
            setTextColor(android.graphics.Color.WHITE)
            setPadding(0, 4, 0, 12)
        }
        root.addView(heading)

        search = EditText(requireContext()).apply {
            hint = "Search videos or folders"
            singleLine = true
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(0xFFAAAAAA.toInt())
        }
        root.addView(search, LinearLayout.LayoutParams(-1, -2))

        val filters = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        folderSpinner = Spinner(requireContext())
        sortSpinner = Spinner(requireContext())
        layoutButton = TextView(requireContext()).apply {
            text = "Grid"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(android.graphics.Color.WHITE)
            setPadding(12, 12, 12, 12)
        }
        filters.addView(folderSpinner, LinearLayout.LayoutParams(0, -2, 1f))
        filters.addView(sortSpinner, LinearLayout.LayoutParams(0, -2, 1f))
        filters.addView(layoutButton, LinearLayout.LayoutParams(-2, -2))
        root.addView(filters)

        status = TextView(requireContext()).apply {
            setTextColor(0xFFCCCCCC.toInt())
            textSize = 13f
            setPadding(0, 8, 0, 8)
        }
        root.addView(status)

        list = RecyclerView(requireContext()).apply {
            adapter = this@MediaLibraryFragment.adapter
            layoutManager = LinearLayoutManager(requireContext())
            setHasFixedSize(false)
        }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))

        folderSpinner.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            listOf("All folders")
        )
        sortSpinner.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            listOf("Newest", "Name", "Largest", "Longest")
        )
        folderSpinner.onItemSelectedListener = SimpleSelectionListener { position ->
            selectedFolder = folderSpinner.getItemAtPosition(position).toString()
            applyFilters()
        }
        sortSpinner.onItemSelectedListener = SimpleSelectionListener { position ->
            sortMode = position
            applyFilters()
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = applyFilters()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        layoutButton.setOnClickListener {
            gridMode = !gridMode
            layoutButton.text = if (gridMode) "List" else "Grid"
            list.layoutManager = if (gridMode) GridLayoutManager(requireContext(), 2)
                else LinearLayoutManager(requireContext())
        }

        refreshLibrary()
        return root
    }

    private fun hasVideoPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
            else Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(requireContext(), permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun refreshLibrary(requestPermission: Boolean = true) {
        if (!hasVideoPermission() && requestPermission) {
            val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
                else Manifest.permission.READ_EXTERNAL_STORAGE
            permissionLauncher.launch(permission)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            status.text = "Scanning videos…"
            allVideos = withContext(Dispatchers.IO) {
                val found = linkedMapOf<String, LibraryVideo>()
                if (hasVideoPermission()) queryDeviceVideos(requireContext()).forEach { found[it.uri.toString()] = it }
                queryAppDownloads(requireContext()).forEach { found.putIfAbsent(it.uri.toString(), it) }
                found.values.toList()
            }
            val folders = listOf("All folders") + allVideos.map { it.folder }.distinct().filter { it.isNotBlank() }.sorted()
            folderSpinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, folders)
            selectedFolder = "All folders"
            applyFilters()
        }
    }

    private fun queryDeviceVideos(context: Context): List<LibraryVideo> {
        val output = mutableListOf<LibraryVideo>()
        val projection = mutableListOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED
        )
        if (Build.VERSION.SDK_INT >= 29) projection.add(MediaStore.Video.Media.RELATIVE_PATH)
        else projection.add(MediaStore.Video.Media.DATA)
        val sort = "${MediaStore.Video.Media.DATE_ADDED} DESC"
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(collection, projection.toTypedArray(), null, null, sort)
            cursor?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durationCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val pathCol = if (Build.VERSION.SDK_INT >= 29) c.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH) else -1
                val dataCol = if (Build.VERSION.SDK_INT < 29) c.getColumnIndex(MediaStore.Video.Media.DATA) else -1
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val uri = Uri.withAppendedPath(collection, id.toString())
                    val rawPath = when {
                        pathCol >= 0 -> c.getString(pathCol).orEmpty()
                        dataCol >= 0 -> File(c.getString(dataCol).orEmpty()).parent.orEmpty()
                        else -> ""
                    }
                    val folder = rawPath.trimEnd('/').ifBlank { "Videos" }
                    output.add(
                        LibraryVideo(
                            uri = uri,
                            title = c.getString(nameCol) ?: "Video",
                            folder = folder.ifBlank { "Videos" },
                            duration = c.getLong(durationCol),
                            size = c.getLong(sizeCol),
                            dateAdded = c.getLong(dateCol),
                            fromApp = false
                        )
                    )
                }
            }
        } catch (_: SecurityException) {
        } finally {
            cursor?.close()
        }
        return output
    }

    private fun queryAppDownloads(context: Context): List<LibraryVideo> {
        val output = mutableListOf<LibraryVideo>()
        val children = context.getKeys(DOWNLOAD_EPISODE_CACHE)
            .mapNotNull { key -> context.getKey<DownloadObjects.DownloadEpisodeCached>(key) }
        children.forEach { child ->
            val info = getDownloadFileInfo(context, child.id) ?: return@forEach
            val header = context.getKey<DownloadObjects.DownloadHeaderCached>(
                DOWNLOAD_HEADER_CACHE,
                child.parentId.toString()
            )
            val label = listOfNotNull(header?.name, child.name).joinToString(" • ").ifBlank { "Downloaded video" }
            output.add(
                LibraryVideo(
                    uri = info.path,
                    title = label,
                    folder = header?.name ?: "CloudStream downloads",
                    duration = 0L,
                    size = info.fileLength,
                    dateAdded = child.cacheTime / 1000L,
                    fromApp = true
                )
            )
        }
        return output
    }

    private fun applyFilters() {
        if (!this::list.isInitialized) return
        val query = search.text?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val filtered = allVideos.asSequence()
            .filter { selectedFolder == "All folders" || it.folder == selectedFolder }
            .filter { query.isBlank() || it.title.lowercase(Locale.ROOT).contains(query) || it.folder.lowercase(Locale.ROOT).contains(query) }
            .let { sequence ->
                when (sortMode) {
                    1 -> sequence.sortedBy { it.title.lowercase(Locale.ROOT) }
                    2 -> sequence.sortedByDescending { it.size }
                    3 -> sequence.sortedByDescending { it.duration }
                    else -> sequence.sortedByDescending { it.dateAdded }
                }
            }.toList()
        adapter.submit(filtered)
        status.text = "${filtered.size} videos • Device and app downloads"
    }
}

private class SimpleSelectionListener(
    private val selected: (Int) -> Unit
) : android.widget.AdapterView.OnItemSelectedListener {
    override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = selected(position)
    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
}

private class MediaLibraryAdapter(
    private val onClick: (LibraryVideo) -> Unit
) : RecyclerView.Adapter<MediaLibraryAdapter.VideoHolder>() {
    private var items: List<LibraryVideo> = emptyList()

    fun submit(newItems: List<LibraryVideo>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoHolder {
        val text = TextView(parent.context).apply {
            setTextColor(android.graphics.Color.WHITE)
            textSize = 15f
            setPadding(14, 16, 14, 16)
            setBackgroundResource(android.R.drawable.list_selector_background)
        }
        return VideoHolder(text)
    }

    override fun onBindViewHolder(holder: VideoHolder, position: Int) {
        val item = items[position]
        val sizeMb = item.size / (1024f * 1024f)
        val durationMin = item.duration / 60000L
        (holder.itemView as TextView).text = buildString {
            append(item.title)
            append("\n")
            append(item.folder)
            append(" • ")
            append(String.format(Locale.ROOT, "%.1f MB", sizeMb))
            if (durationMin > 0) append(" • ${durationMin} min")
            if (item.fromApp) append(" • CloudStream")
        }
        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount() = items.size

    class VideoHolder(view: View) : RecyclerView.ViewHolder(view)
}
