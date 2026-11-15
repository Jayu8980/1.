package com.tubepilot.media.library

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class LibraryItem(val name: String, val uri: Uri, val mime: String, val sizeBytes: Long)

/**
 * The user's own royalty-free music and B-roll, chosen as folders through the Storage Access
 * Framework (persisted URI permission). The app never downloads third-party media.
 */
@Singleton
class AssetLibrary @Inject constructor(@ApplicationContext private val context: Context) {

    suspend fun list(folderUri: String?, mimePrefix: String): List<LibraryItem> = withContext(Dispatchers.IO) {
        val uri = folderUri?.let(Uri::parse) ?: return@withContext emptyList()
        val root = DocumentFile.fromTreeUri(context, uri) ?: return@withContext emptyList()
        if (!root.canRead()) return@withContext emptyList()
        root.walk().filter { it.isFile && (it.type ?: "").startsWith(mimePrefix) }
            .map { LibraryItem(it.name ?: "untitled", it.uri, it.type.orEmpty(), it.length()) }
            .sortedBy { it.name }
            .toList()
    }

    private fun DocumentFile.walk(depth: Int = 0): Sequence<DocumentFile> = sequence {
        if (depth > 3) return@sequence
        for (child in listFiles()) {
            if (child.isDirectory) yieldAll(child.walk(depth + 1)) else yield(child)
        }
    }

    /** Deterministic pick so the same project always gets the same track. */
    suspend fun pickMusic(folderUri: String?, seed: String, moodHints: List<String>): LibraryItem? {
        val tracks = list(folderUri, "audio/")
        if (tracks.isEmpty()) return null
        val hinted = tracks.filter { t -> moodHints.any { h -> t.name.lowercase(Locale.ROOT).contains(h.lowercase(Locale.ROOT)) } }
        val pool = hinted.ifEmpty { tracks }
        return pool[Math.floorMod(seed.hashCode(), pool.size)]
    }

    /** Best keyword match on file names, e.g. "city_night_timelapse.mp4" for "night city". */
    suspend fun findBroll(folderUri: String?, query: String, exclude: Set<String>): LibraryItem? {
        if (query.isBlank()) return null
        val words = query.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 }.toSet()
        if (words.isEmpty()) return null
        return list(folderUri, "video/")
            .filter { it.uri.toString() !in exclude }
            .map { item -> item to words.count { item.name.lowercase(Locale.ROOT).contains(it) } }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }?.first
    }

    suspend fun copyToFile(item: LibraryItem, dest: File): File = withContext(Dispatchers.IO) {
        if (dest.isFile && dest.length() == item.sizeBytes) return@withContext dest
        dest.parentFile?.mkdirs()
        context.contentResolver.openInputStream(item.uri)?.use { input -> dest.outputStream().use { input.copyTo(it) } }
            ?: throw java.io.IOException("Cannot read ${item.name}")
        dest
    }

    /** Keeps read access to a user-chosen folder across reboots. */
    fun persistPermission(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
