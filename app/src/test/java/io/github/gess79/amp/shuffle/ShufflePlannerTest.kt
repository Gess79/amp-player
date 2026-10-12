package io.github.gess79.amp.shuffle

import io.github.gess79.amp.data.db.TrackEntity
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.random.Random

class ShufflePlannerTest {

    @Test
    fun contained_matchesNestedTitles() {
        assertThat(
            ShufflePlanner.contained(
                ShufflePlanner.normalize("Buona fortuna Jack"),
                ShufflePlanner.normalize("Buona fortuna Jack - Part 3"),
            ),
        ).isTrue()
        assertThat(ShufflePlanner.contained("ennio", "ennio morricone")).isTrue()
        assertThat(ShufflePlanner.contained("other 1", "other 10")).isFalse()
        assertThat(ShufflePlanner.contained("a", "alanis")).isFalse()
    }

    @Test
    fun similar_requiresArtistAndTitleOverlap() {
        val a = ShufflePlanner.Meta(1, "ennio", "buona fortuna jack")
        val b = ShufflePlanner.Meta(2, "ennio morricone", "buona fortuna jack - part 3")
        val c = ShufflePlanner.Meta(3, "alanis", "buona fortuna jack")
        assertThat(ShufflePlanner.similar(a, b)).isTrue()
        assertThat(ShufflePlanner.similar(a, c)).isFalse()
    }

    @Test
    fun planRound_containsEachIdOnceAndKeepsStart() {
        val tracks = (1L..40L).map { track(it, "Song $it", "Artist ${it % 8}") }
        val plan = ShufflePlanner.planRound(tracks, startTrackId = 1L, random = Random(42))
        assertThat(plan.trackIds).containsExactlyElementsIn(tracks.map { it.id })
        assertThat(plan.trackIds.first()).isEqualTo(1L)
    }

    @Test
    fun planRound_keepsSimilarTitlesAtCircularDistance() {
        val tracks = buildList {
            add(track(1, "Buona fortuna Jack", "Ennio"))
            add(track(2, "Buona fortuna Jack - Part 3", "Ennio"))
            addAll((3L..40L).map { track(it, "Other $it", "Artist $it") })
        }
        repeat(20) { seed ->
            val plan = ShufflePlanner.planRound(
                tracks,
                startTrackId = 10L,
                options = ShufflePlanOptions(spreadSimilar = true, spreadSameArtist = false),
                random = Random(seed),
            )
            val n = plan.trackIds.size
            val i1 = plan.trackIds.indexOf(1L)
            val i2 = plan.trackIds.indexOf(2L)
            val dist = ShufflePlanner.circularDistance(i1, i2, n)
            assertThat(dist).isAtLeast(ShufflePlanner.similarMinGap(n))
            assertThat(plan.similarPairs).isEqualTo(1)
        }
    }

    @Test
    fun planRound_withoutSimilarSpread_stillShuffles() {
        val tracks = (1L..12L).map { track(it, "Song $it", "A") }
        val plan = ShufflePlanner.planRound(
            tracks,
            startTrackId = 1L,
            options = ShufflePlanOptions(spreadSimilar = false, spreadSameArtist = false),
            random = Random(1),
        )
        assertThat(plan.trackIds).containsExactlyElementsIn(tracks.map { it.id })
        assertThat(plan.trackIds.first()).isEqualTo(1L)
    }

    @Test
    fun planRound_artistSpread_breaksLongRuns() {
        val tracks = (1L..24L).map { id ->
            val artist = if (id <= 8L) "Alanis" else "Other $id"
            track(id, "Song $id", artist)
        }
        val plan = ShufflePlanner.planRound(
            tracks,
            startTrackId = 9L,
            options = ShufflePlanOptions(spreadSimilar = false, spreadSameArtist = true),
            random = Random(3),
        )
        val artists = plan.trackIds.map { id -> tracks.first { it.id == id }.artist }
        var maxRun = 1
        var run = 1
        for (i in 1 until artists.size) {
            if (artists[i] == artists[i - 1]) {
                run++
                maxRun = maxOf(maxRun, run)
            } else {
                run = 1
            }
        }
        assertThat(maxRun).isLessThan(5)
    }

    private fun track(
        id: Long,
        title: String,
        artist: String,
        durationMs: Long = 180_000,
    ) = TrackEntity(
        id = id,
        title = title,
        artist = artist,
        album = "A",
        durationMs = durationMs,
        path = "/music/$id.mp3",
        directory = "/music",
        albumArtUri = null,
        dateAdded = 0L,
        sizeBytes = 1L,
    )
}
