package com.tubepilot.media.video

import com.tubepilot.core.model.VideoScript
import com.tubepilot.core.model.VisualSet
import com.tubepilot.core.model.VoiceTrack

/** Joins script scenes, voice timings and visuals into the clip list the renderer consumes. */
object Timeline {
    fun build(script: VideoScript, voice: VoiceTrack, visuals: VisualSet): List<SceneClip> {
        val visualByScene = visuals.visuals.associateBy { it.sceneIndex }
        val timings = voice.sceneTimings.sortedBy { it.startMs }
        return timings.mapIndexed { i, timing ->
            val scene = script.scenes.firstOrNull { it.index == timing.sceneIndex }
            val visual = visualByScene[timing.sceneIndex] ?: visualByScene.values.first()
            // The last scene runs to the end of the mixed audio (music tail included).
            val end = if (i == timings.lastIndex) maxOf(timing.endMs, voice.durationMs) else timing.endMs
            SceneClip(
                path = visual.path,
                kind = visual.kind,
                startMs = timing.startMs,
                durationMs = (end - timing.startMs).coerceAtLeast(500),
                onScreenText = scene?.onScreenText.orEmpty(),
            )
        }.let { clips ->
            // Close any gaps so the video timeline is contiguous from 0.
            var cursor = 0L
            clips.map { c -> c.copy(startMs = cursor, durationMs = c.durationMs + (c.startMs - cursor)).also { cursor = it.startMs + it.durationMs } }
        }
    }
}
