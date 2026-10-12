package io.github.gess79.amp.shuffle

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.random.Random

class ShuffleQueueTest {

    @Test
    fun create_containsEachTrackExactlyOnce() {
        val ids = (1L..20L).toList()
        val queue = ShuffleQueue.create("p1", ids, random = Random(42))
        assertThat(queue.trackIds).containsExactlyElementsIn(ids)
        assertThat(queue.trackIds).hasSize(ids.size)
        assertThat(queue.currentIndex).isEqualTo(0)
        assertThat(queue.generation).isEqualTo(1)
    }

    @Test
    fun advance_throughFullRound_hasNoDuplicatesUntilReshuffle() {
        val ids = (1L..10L).toList()
        var queue = ShuffleQueue.create("p1", ids, startTrackId = 1L, random = Random(7))
        val played = mutableListOf<Long>()
        repeat(ids.size) {
            played += queue.currentTrackId
            if (it < ids.lastIndex) queue = queue.advance()
        }
        assertThat(played).containsExactlyElementsIn(ids)
        assertThat(played.toSet()).hasSize(ids.size)
    }

    @Test
    fun advance_afterLastTrack_startsNewRoundWithLastTrackFirst() {
        val ids = listOf(1L, 2L, 3L, 4L)
        var queue = ShuffleQueue.create("p1", ids, startTrackId = 1L, random = Random(1))
        while (queue.currentIndex < queue.trackIds.lastIndex) {
            queue = queue.advance()
        }
        val last = queue.currentTrackId
        val nextRound = queue.advance()
        assertThat(nextRound.generation).isEqualTo(queue.generation + 1)
        assertThat(nextRound.trackIds.first()).isEqualTo(last)
        assertThat(nextRound.currentIndex).isEqualTo(1)
        assertThat(nextRound.currentTrackId).isNotEqualTo(last)
        assertThat(nextRound.trackIds).containsExactlyElementsIn(ids)
    }

    @Test
    fun restore_keepsIndexAndOrder() {
        val original = ShuffleQueue.create("dir:/music", (1L..5L).toList(), random = Random(3))
            .advance()
            .advance()
        val restored = ShuffleQueue.restore(
            original.sourceId,
            original.trackIds,
            original.currentIndex,
            original.generation,
        )
        assertThat(restored).isEqualTo(original)
        assertThat(restored.currentTrackId).isEqualTo(original.trackIds[2])
    }

    @Test
    fun retreat_doesNotGoBelowZero() {
        val queue = ShuffleQueue.create("p", listOf(1L, 2L, 3L), startTrackId = 1L)
        assertThat(queue.retreat()).isEqualTo(queue)
        val mid = queue.advance()
        assertThat(mid.retreat().currentIndex).isEqualTo(0)
    }

    @Test
    fun reconcile_unchangedSource_returnsNull() {
        val queue = ShuffleQueue.restore("p", listOf(3L, 1L, 4L, 2L), currentIndex = 1, generation = 2)
        assertThat(queue.reconcile(listOf(1L, 2L, 3L, 4L), shuffle = true)).isNull()
    }

    @Test
    fun reconcile_shuffle_addsNewTracksOnlyToUnplayedRest() {
        val queue = ShuffleQueue.restore("p", listOf(3L, 1L, 4L, 2L), currentIndex = 1, generation = 2)
        val result = queue.reconcile(listOf(1L, 2L, 3L, 4L, 5L, 6L), shuffle = true, random = Random(5))!!
        assertThat(result.trackIds.subList(0, 2)).containsExactly(3L, 1L).inOrder()
        assertThat(result.currentIndex).isEqualTo(1)
        assertThat(result.currentTrackId).isEqualTo(1L)
        assertThat(result.trackIds.drop(2)).containsExactly(4L, 2L, 5L, 6L)
        assertThat(result.generation).isEqualTo(2)
    }

    @Test
    fun reconcile_shuffle_keepsOrderOfExistingUpcomingTracks() {
        val queue = ShuffleQueue.restore("p", listOf(1L, 2L, 3L, 4L, 5L), currentIndex = 0, generation = 1)
        val result = queue.reconcile((1L..8L).toList(), shuffle = true, random = Random(11))!!
        val oldUpcoming = result.trackIds.filter { it in listOf(2L, 3L, 4L, 5L) }
        assertThat(oldUpcoming).containsExactly(2L, 3L, 4L, 5L).inOrder()
    }

    @Test
    fun reconcile_shuffle_removesDeletedTracksAndKeepsCurrent() {
        val queue = ShuffleQueue.restore("p", listOf(5L, 2L, 7L, 1L, 9L), currentIndex = 2, generation = 1)
        val result = queue.reconcile(listOf(1L, 5L), shuffle = true)!!
        assertThat(result.trackIds).containsExactly(5L, 7L, 1L).inOrder()
        assertThat(result.currentTrackId).isEqualTo(7L)
        assertThat(result.currentIndex).isEqualTo(1)
    }

    @Test
    fun reconcile_shuffle_roundStillPlaysEachTrackOnce() {
        var queue = ShuffleQueue.create("p", (1L..10L).toList(), startTrackId = 1L, random = Random(2))
        queue = queue.advance().advance()
        val source = (3L..14L).toList()
        queue = queue.reconcile(source, shuffle = true, random = Random(9))!!
        val remaining = queue.trackIds.drop(queue.currentIndex + 1)
        val played = queue.trackIds.take(queue.currentIndex + 1)
        assertThat((played + remaining).toSet()).hasSize(queue.trackIds.size)
        assertThat(remaining).containsAtLeast(11L, 12L, 13L, 14L)
    }

    @Test
    fun reconcile_ordered_followsSourceOrder() {
        val queue = ShuffleQueue.restore("p", listOf(1L, 2L, 3L), currentIndex = 1, generation = 1)
        val result = queue.reconcile(listOf(4L, 1L, 2L, 3L), shuffle = false)!!
        assertThat(result.trackIds).containsExactly(4L, 1L, 2L, 3L).inOrder()
        assertThat(result.currentTrackId).isEqualTo(2L)
    }

    @Test
    fun reconcile_ordered_keepsRemovedCurrentTrackInPlace() {
        val queue = ShuffleQueue.restore("p", listOf(1L, 2L, 3L), currentIndex = 1, generation = 1)
        assertThat(queue.reconcile(listOf(1L, 3L), shuffle = false)).isNull()
        assertThat(queue.reconcile(listOf(1L, 3L, 4L), shuffle = false)!!.trackIds)
            .containsExactly(1L, 2L, 3L, 4L).inOrder()
    }

    @Test
    fun reconcile_emptySource_returnsNull() {
        val queue = ShuffleQueue.restore("p", listOf(1L, 2L), currentIndex = 0, generation = 1)
        assertThat(queue.reconcile(emptyList(), shuffle = true)).isNull()
    }

    @Test
    fun singleTrack_reshuffleStillWorks() {
        var queue = ShuffleQueue.create("one", listOf(9L))
        queue = queue.advance()
        assertThat(queue.currentTrackId).isEqualTo(9L)
        assertThat(queue.generation).isEqualTo(2)
        assertThat(queue.trackIds).containsExactly(9L)
    }

    @Test
    fun playNow_shuffle_upcomingTrackKeepsRestOfRound() {
        val queue = ShuffleQueue.restore("p", listOf(1L, 2L, 3L, 4L, 5L), currentIndex = 1, generation = 3)
        val now = queue.playNow(4L, shuffle = true)
        assertThat(now.trackIds).containsExactly(1L, 2L, 4L, 3L, 5L).inOrder()
        assertThat(now.currentIndex).isEqualTo(2)
        assertThat(now.generation).isEqualTo(3)
    }

    @Test
    fun playNow_shuffle_playedTrackMovesToCurrent() {
        val queue = ShuffleQueue.restore("p", listOf(1L, 2L, 3L, 4L), currentIndex = 2, generation = 1)
        val now = queue.playNow(1L, shuffle = true)
        assertThat(now.trackIds).containsExactly(2L, 3L, 1L, 4L).inOrder()
        assertThat(now.currentIndex).isEqualTo(2)
    }

    @Test
    fun playNow_ordered_jumpsToPosition() {
        val queue = ShuffleQueue.restore("p", listOf(1L, 2L, 3L, 4L), currentIndex = 0, generation = 1)
        val now = queue.playNow(3L, shuffle = false)
        assertThat(now.trackIds).containsExactly(1L, 2L, 3L, 4L).inOrder()
        assertThat(now.currentIndex).isEqualTo(2)
    }

    @Test
    fun without_dropsTrackAndKeepsCurrent() {
        val queue = ShuffleQueue.restore("p", listOf(1L, 2L, 3L, 4L), currentIndex = 2, generation = 1)
        val dropped = queue.without(2L)
        assertThat(dropped.trackIds).containsExactly(1L, 3L, 4L).inOrder()
        assertThat(dropped.currentTrackId).isEqualTo(3L)
        assertThat(queue.without(3L)).isEqualTo(queue)
    }
}
