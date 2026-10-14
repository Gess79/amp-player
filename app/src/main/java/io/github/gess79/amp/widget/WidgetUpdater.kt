package io.github.gess79.amp.widget

import io.github.gess79.amp.data.db.TrackEntity
import io.github.gess79.amp.shuffle.ShuffleQueue
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Facade used by PlaybackService. Writes Glance preferences then forces a widget redraw.
 */
@Singleton
class WidgetUpdater @Inject constructor(
    private val bridge: WidgetUiBridge,
) {
    fun updateAll() = bridge.updateAll()

    fun refreshLocale() = bridge.refreshLocale()

    fun setPlaying(playing: Boolean) = bridge.setPlaying(playing)

    fun publish(
        queue: ShuffleQueue?,
        current: TrackEntity?,
        positionMs: Long,
        isPlaying: Boolean,
        shuffleEnabled: Boolean,
        force: Boolean = false,
    ) = bridge.publish(queue, current, positionMs, isPlaying, shuffleEnabled, force)
}
