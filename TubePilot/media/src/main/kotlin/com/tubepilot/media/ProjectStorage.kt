package com.tubepilot.media

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** App-private working directories. Each project keeps its assets under projects/<id>/. */
@Singleton
class ProjectStorage @Inject constructor(@ApplicationContext private val context: Context) {
    fun projectDir(projectId: String): File = File(context.filesDir, "projects/$projectId").apply { mkdirs() }
    fun sub(projectId: String, name: String): File = File(projectDir(projectId), name).apply { mkdirs() }
    fun cacheDir(name: String): File = File(context.cacheDir, name).apply { mkdirs() }

    /** Removes intermediate files once a video is published, keeping the final render and thumbnails. */
    fun pruneIntermediates(projectId: String) {
        listOf("voice_parts", "tmp").forEach { File(projectDir(projectId), it).deleteRecursively() }
    }

    fun delete(projectId: String) {
        File(context.filesDir, "projects/$projectId").deleteRecursively()
    }

    fun usedBytes(): Long = File(context.filesDir, "projects").walkBottomUp().filter { it.isFile }.sumOf { it.length() }
}
