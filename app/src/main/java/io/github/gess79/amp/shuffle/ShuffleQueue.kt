package io.github.gess79.amp.shuffle

import kotlin.random.Random

/**
 * Persistent-friendly shuffle queue: no track repeats within a round.
 * When a round ends on [Track X], the next round is ordered as [X, ...] but
 * playback continues at the track after X so X is not played twice in a row.
 */
data class ShuffleQueue(
    val sourceId: String,
    val trackIds: List<Long>,
    val currentIndex: Int = 0,
    val generation: Int = 1,
) {
    init {
        require(trackIds.isNotEmpty()) { "Shuffle queue requires at least one track" }
        require(currentIndex in trackIds.indices) { "currentIndex out of bounds" }
    }

    val currentTrackId: Long get() = trackIds[currentIndex]

    val remainingInRound: Int get() = trackIds.size - currentIndex - 1

    val progressLabel: String
        get() = "${currentIndex + 1} / ${trackIds.size}"

    fun peekNextId(): Long? =
        if (currentIndex + 1 < trackIds.size) trackIds[currentIndex + 1] else null

    /** Advances within the round, or null if this was the last track. */
    fun tryAdvance(): ShuffleQueue? =
        if (currentIndex + 1 < trackIds.size) copy(currentIndex = currentIndex + 1) else null

    fun advance(): ShuffleQueue {
        tryAdvance()?.let { return it }
        val nextRound = reshuffleStartingWith(currentTrackId)
        // X is the overlap: last of this round = first of the next order, but do not play X twice.
        return if (nextRound.trackIds.size <= 1) nextRound
        else nextRound.copy(currentIndex = 1)
    }

    fun wrapToStart(): ShuffleQueue = copy(currentIndex = 0, generation = generation + 1)

    fun retreat(): ShuffleQueue {
        if (currentIndex == 0) return this
        return copy(currentIndex = currentIndex - 1)
    }

    fun jumpTo(index: Int): ShuffleQueue {
        require(index in trackIds.indices)
        return copy(currentIndex = index)
    }

    /**
     * Makes [trackId] the current track within this round. Shuffle: everything up to the old
     * current track counts as played, the remaining tracks keep their planned order, so no
     * track is skipped or doubled. Ordered: jumps to the track's position.
     */
    fun playNow(trackId: Long, shuffle: Boolean): ShuffleQueue {
        val index = trackIds.indexOf(trackId)
        require(index >= 0) { "trackId must belong to this queue" }
        if (!shuffle || index == currentIndex) return copy(currentIndex = index)
        val played = trackIds.subList(0, currentIndex + 1).filter { it != trackId }
        val upcoming = trackIds.subList(currentIndex + 1, trackIds.size).filter { it != trackId }
        return copy(trackIds = played + trackId + upcoming, currentIndex = played.size)
    }

    /** Drops [trackId] from the queue; the current track is never dropped. */
    fun without(trackId: Long): ShuffleQueue {
        if (trackId == currentTrackId || trackId !in trackIds) return this
        val shift = trackIds.subList(0, currentIndex).count { it == trackId }
        return copy(trackIds = trackIds.filter { it != trackId }, currentIndex = currentIndex - shift)
    }

    fun reshuffleStartingWith(startTrackId: Long, random: Random = Random.Default): ShuffleQueue {
        require(startTrackId in trackIds) { "startTrackId must belong to this queue source" }
        val others = trackIds.filter { it != startTrackId }.toMutableList()
        others.shuffle(random)
        return copy(
            trackIds = listOf(startTrackId) + others,
            currentIndex = 0,
            generation = generation + 1,
        )
    }

    /**
     * Applies changed source contents to this queue without starting a new round.
     * Shuffle: removed tracks leave the queue, new ones go to random spots in the unplayed
     * rest, so every track still plays once per round. Ordered: follows the source order.
     * The current track always stays (it may be playing). Returns null if nothing changed.
     */
    fun reconcile(
        sourceTrackIds: List<Long>,
        shuffle: Boolean,
        random: Random = Random.Default,
    ): ShuffleQueue? {
        val source = sourceTrackIds.distinct()
        if (source.isEmpty()) return null
        val current = currentTrackId
        val inSource = source.toHashSet()
        if (!shuffle) {
            val ids = if (current in inSource) {
                source
            } else {
                source.toMutableList().apply { add(currentIndex.coerceAtMost(size), current) }
            }
            val index = ids.indexOf(current)
            return if (ids == trackIds && index == currentIndex) null
            else copy(trackIds = ids, currentIndex = index)
        }
        val inQueue = trackIds.toHashSet()
        val played = trackIds.subList(0, currentIndex).filter { it in inSource }
        val upcomingBefore = trackIds.subList(currentIndex + 1, trackIds.size)
        val upcoming = upcomingBefore.filter { it in inSource }.toMutableList()
        val added = source.filter { it !in inQueue }
        if (played.size == currentIndex && upcoming.size == upcomingBefore.size && added.isEmpty()) {
            return null
        }
        for (id in added) upcoming.add(random.nextInt(upcoming.size + 1), id)
        return copy(trackIds = played + current + upcoming, currentIndex = played.size)
    }

    companion object {
        fun create(
            sourceId: String,
            trackIds: List<Long>,
            startTrackId: Long? = null,
            random: Random = Random.Default,
        ): ShuffleQueue {
            require(trackIds.isNotEmpty())
            val unique = trackIds.distinct()
            val start = startTrackId?.takeIf { it in unique } ?: unique.random(random)
            val others = unique.filter { it != start }.toMutableList()
            others.shuffle(random)
            return ShuffleQueue(
                sourceId = sourceId,
                trackIds = listOf(start) + others,
                currentIndex = 0,
                generation = 1,
            )
        }

        fun createOrdered(
            sourceId: String,
            trackIds: List<Long>,
            startTrackId: Long? = null,
        ): ShuffleQueue {
            require(trackIds.isNotEmpty())
            val unique = trackIds.distinct()
            val index = startTrackId?.let { unique.indexOf(it) }?.takeIf { it >= 0 } ?: 0
            return ShuffleQueue(
                sourceId = sourceId,
                trackIds = unique,
                currentIndex = index,
                generation = 1,
            )
        }

        fun restore(
            sourceId: String,
            trackIds: List<Long>,
            currentIndex: Int,
            generation: Int,
        ): ShuffleQueue = ShuffleQueue(sourceId, trackIds, currentIndex, generation)
    }
}
