package io.github.gess79.amp.shuffle

import io.github.gess79.amp.data.db.TrackEntity
import kotlin.math.abs
import kotlin.math.min
import kotlin.random.Random

data class ShufflePlanOptions(
    val spreadSimilar: Boolean = true,
    val spreadSameArtist: Boolean = true,
)

/**
 * Starts from a real random shuffle, then optionally nudges similar titles
 * (substring match on artist AND title) and same-artist clumps farther apart.
 * Distances are circular so a new round does not play a pair back-to-back.
 */
object ShufflePlanner {

    data class PlanResult(
        val trackIds: List<Long>,
        val similarPairs: Int,
    )

    data class Meta(
        val id: Long,
        val artistKey: String,
        val titleKey: String,
    )

    fun planRound(
        tracks: List<TrackEntity>,
        startTrackId: Long? = null,
        options: ShufflePlanOptions = ShufflePlanOptions(),
        random: Random = Random.Default,
    ): PlanResult {
        val byId = tracks.associate { it.id to metaOf(it) }
        val ids = tracks.map { it.id }.distinct()
        require(ids.isNotEmpty())
        val start = startTrackId?.takeIf { it in byId.keys } ?: ids.random(random)
        val rest = ids.filter { it != start }.shuffled(random).toMutableList()
        val order = (listOf(start) + rest).toMutableList()
        val similarPairs = similarPairsOf(order, byId)
        if (options.spreadSimilar) {
            spreadSimilar(order, byId, pinnedIndex = 0, random = random)
        }
        if (options.spreadSameArtist) {
            spreadArtists(order, byId, pinnedIndex = 0, random = random)
            if (options.spreadSimilar) {
                spreadSimilar(order, byId, pinnedIndex = 0, random = random)
            }
        }
        return PlanResult(order.toList(), similarPairs.size)
    }

    fun metaOf(track: TrackEntity): Meta = Meta(
        id = track.id,
        artistKey = normalize(track.artist),
        titleKey = normalize(track.title),
    )

    fun normalize(value: String): String =
        value.lowercase().trim().replace(Regex("\\s+"), " ")

    fun similar(a: Meta, b: Meta): Boolean =
        a.id != b.id &&
            contained(a.artistKey, b.artistKey) &&
            contained(a.titleKey, b.titleKey)

    fun contained(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        val shorter = if (a.length <= b.length) a else b
        val longer = if (a.length <= b.length) b else a
        if (shorter.length < 3) return false
        var start = 0
        while (true) {
            val index = longer.indexOf(shorter, start)
            if (index < 0) return false
            val beforeOk = index == 0 || !longer[index - 1].isLetterOrDigit()
            val after = index + shorter.length
            val afterOk = after == longer.length || !longer[after].isLetterOrDigit()
            if (beforeOk && afterOk) return true
            start = index + 1
        }
    }

    fun circularDistance(i: Int, j: Int, n: Int): Int {
        if (n <= 1) return 0
        val d = abs(i - j)
        return min(d, n - d)
    }

    fun similarMinGap(n: Int): Int {
        if (n < 6) return 2
        return (n / 4).coerceIn(4, (n / 3).coerceAtLeast(4)).coerceAtMost(n / 2)
    }

    private fun similarPairsOf(
        order: List<Long>,
        byId: Map<Long, Meta>,
    ): List<Pair<Int, Int>> {
        val pairs = mutableListOf<Pair<Int, Int>>()
        for (i in order.indices) {
            val a = byId[order[i]] ?: continue
            for (j in i + 1 until order.size) {
                val b = byId[order[j]] ?: continue
                if (similar(a, b)) pairs += i to j
            }
        }
        return pairs
    }

    private fun spreadSimilar(
        order: MutableList<Long>,
        byId: Map<Long, Meta>,
        pinnedIndex: Int,
        random: Random,
    ) {
        val n = order.size
        val minGap = similarMinGap(n)
        repeat(n * 3) {
            val close = similarPairsOf(order, byId).firstOrNull { (i, j) ->
                circularDistance(i, j, n) < minGap
            } ?: return
            val (i, j) = close
            val movable = when {
                i == pinnedIndex -> j
                j == pinnedIndex -> i
                else -> if (random.nextBoolean()) i else j
            }
            val partner = if (movable == i) j else i
            val candidates = order.indices.filter { idx ->
                idx != movable &&
                    idx != partner &&
                    idx != pinnedIndex &&
                    circularDistance(idx, partner, n) >= minGap
            }
            if (candidates.isEmpty()) return
            val target = candidates.random(random)
            val tmp = order[movable]
            order[movable] = order[target]
            order[target] = tmp
        }
    }

    private fun spreadArtists(
        order: MutableList<Long>,
        byId: Map<Long, Meta>,
        pinnedIndex: Int,
        random: Random,
    ) {
        val n = order.size
        if (n < 4) return
        repeat(4) {
            var moved = false
            for (i in 1 until n) {
                val artist = byId[order[i]]?.artistKey.orEmpty()
                if (artist.length < 3) continue
                val prev = byId[order[i - 1]]?.artistKey.orEmpty()
                if (artist != prev) continue
                val candidates = order.indices.filter { idx ->
                    idx != i &&
                        idx != pinnedIndex &&
                        idx != i - 1 &&
                        byId[order[idx]]?.artistKey != artist &&
                        (idx == 0 || byId[order[idx - 1]]?.artistKey != artist) &&
                        (idx == n - 1 || byId[order[idx + 1]]?.artistKey != artist)
                }
                if (candidates.isEmpty()) continue
                val target = candidates.random(random)
                if (target == pinnedIndex) continue
                order[i] = order[target].also { order[target] = order[i] }
                moved = true
            }
            if (!moved) return
        }
    }
}
