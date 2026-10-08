package com.amp.player.shuffle

import com.amp.player.data.db.ShuffleStateDao
import com.amp.player.data.db.ShuffleStateEntity
import com.amp.player.data.db.SourceQueueDao
import com.amp.player.data.db.SourceQueueEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `shuffle_state` holds the active queue (restored after restart / BT wake);
 * `source_queues` keeps the last queue of every source so switching back continues it.
 */
@Singleton
class ShuffleQueueStore @Inject constructor(
    private val dao: ShuffleStateDao,
    private val sourceDao: SourceQueueDao,
) {
    fun observe(): Flow<PersistedShuffle?> = dao.observe().map { it?.toPersisted() }

    suspend fun load(): PersistedShuffle? = dao.get()?.toPersisted()

    fun observeSource(sourceId: String): Flow<PersistedShuffle?> =
        sourceDao.observe(sourceId).map { it?.toPersisted() }

    suspend fun loadSource(sourceId: String): PersistedShuffle? =
        sourceDao.get(sourceId)?.toPersisted()

    suspend fun deleteSource(sourceId: String) = sourceDao.delete(sourceId)

    suspend fun save(
        queue: ShuffleQueue,
        positionMs: Long = 0L,
        shuffleEnabled: Boolean = true,
        isPlaying: Boolean = false,
    ) {
        val now = System.currentTimeMillis()
        val csv = queue.trackIds.joinToString(",")
        dao.upsert(
            ShuffleStateEntity(
                id = 1,
                sourceId = queue.sourceId,
                trackIdsCsv = csv,
                currentIndex = queue.currentIndex,
                generation = queue.generation,
                positionMs = positionMs,
                shuffleEnabled = shuffleEnabled,
                isPlaying = isPlaying,
                updatedAt = now,
            ),
        )
        sourceDao.upsert(
            SourceQueueEntity(
                sourceId = queue.sourceId,
                trackIdsCsv = csv,
                currentIndex = queue.currentIndex,
                generation = queue.generation,
                positionMs = positionMs,
                shuffleEnabled = shuffleEnabled,
                updatedAt = now,
            ),
        )
    }

    suspend fun updatePlaybackFlags(positionMs: Long, isPlaying: Boolean) {
        val current = dao.get() ?: return
        val now = System.currentTimeMillis()
        dao.upsert(
            current.copy(
                positionMs = positionMs,
                isPlaying = isPlaying,
                updatedAt = now,
            ),
        )
        sourceDao.updatePosition(current.sourceId, positionMs, now)
    }

    suspend fun clear() = dao.clear()

    private fun ShuffleStateEntity.toPersisted(): PersistedShuffle? =
        persisted(sourceId, trackIdsCsv, currentIndex, generation, positionMs, shuffleEnabled, isPlaying)

    private fun SourceQueueEntity.toPersisted(): PersistedShuffle? =
        persisted(sourceId, trackIdsCsv, currentIndex, generation, positionMs, shuffleEnabled, false)

    private fun persisted(
        sourceId: String,
        trackIdsCsv: String,
        currentIndex: Int,
        generation: Int,
        positionMs: Long,
        shuffleEnabled: Boolean,
        isPlaying: Boolean,
    ): PersistedShuffle? {
        val ids = trackIdsCsv.split(',').mapNotNull { it.toLongOrNull() }
        if (ids.isEmpty()) return null
        return PersistedShuffle(
            queue = ShuffleQueue.restore(
                sourceId,
                ids,
                currentIndex.coerceIn(0, ids.lastIndex),
                generation,
            ),
            positionMs = positionMs,
            shuffleEnabled = shuffleEnabled,
            isPlaying = isPlaying,
        )
    }
}

data class PersistedShuffle(
    val queue: ShuffleQueue,
    val positionMs: Long,
    val shuffleEnabled: Boolean = true,
    val isPlaying: Boolean = false,
)
