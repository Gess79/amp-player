package io.github.gess79.amp.loudness

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import io.github.gess79.amp.R
import io.github.gess79.amp.data.db.TrackEntity
import io.github.gess79.amp.data.library.LibraryRepository
import io.github.gess79.amp.util.AmpLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

data class LoudnessScanState(
    val running: Boolean = false,
    val sourceId: String? = null,
    val done: Int = 0,
    val total: Int = 0,
    val message: String? = null,
)

@Singleton
class LoudnessAnalyzer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val library: LibraryRepository,
    private val repository: LoudnessRepository,
    private val decoder: PcmTrackDecoder,
) {
    private val mutex = Mutex()

    private val _state = MutableStateFlow(LoudnessScanState())
    val state: StateFlow<LoudnessScanState> = _state.asStateFlow()

    /** [sourceId] is `playlist:<id>` or `dir:<path>`, as used by playback. */
    fun startScan(sourceId: String) {
        if (_state.value.running) return
        _state.value = LoudnessScanState(
            running = true,
            sourceId = sourceId,
            message = context.getString(R.string.rg_start),
        )
        val intent = Intent(context, LoudnessScanService::class.java)
            .putExtra(LoudnessScanService.EXTRA_SOURCE_ID, sourceId)
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (t: Throwable) {
            AmpLog.e("ReplayGain", "could not start scan service source=$sourceId", t)
            _state.value = LoudnessScanState(
                running = false,
                sourceId = sourceId,
                message = context.getString(R.string.rg_start_failed),
            )
        }
    }

    suspend fun runScan(sourceId: String): Boolean = mutex.withLock {
        val tracks = tracksFor(sourceId)
        val workers = SCAN_WORKERS.coerceAtMost(tracks.size.coerceAtLeast(1))
        AmpLog.i(
            "ReplayGain",
            "scan start source=$sourceId tracks=${tracks.size} workers=$workers",
        )
        _state.value = LoudnessScanState(
            running = true,
            sourceId = sourceId,
            done = 0,
            total = tracks.size,
            message = context.getString(R.string.rg_analyzing),
        )
        val done = AtomicInteger(0)
        val measured = AtomicInteger(0)
        val fromTags = AtomicInteger(0)
        val skipped = AtomicInteger(0)
        val failed = AtomicInteger(0)
        try {
            coroutineScope {
                val gate = Semaphore(workers)
                tracks.map { track ->
                    async {
                        gate.withPermit {
                            val analysis = analyzeTrack(track)
                            when (analysis.outcome) {
                                TrackOutcome.Current -> skipped.incrementAndGet()
                                TrackOutcome.Tag -> {
                                    measured.incrementAndGet()
                                    fromTags.incrementAndGet()
                                }
                                TrackOutcome.Scan -> measured.incrementAndGet()
                                TrackOutcome.Failed -> failed.incrementAndGet()
                            }
                            val n = done.incrementAndGet()
                            val extra = analysis.result?.let {
                                " lufs=${"%.1f".format(it.lufs)} peak=${"%.1f".format(it.peakDb)}"
                            }.orEmpty() + analysis.note
                            AmpLog.i(
                                "ReplayGain",
                                "scan $n/${tracks.size} ${analysis.outcome.name.lowercase()}$extra " +
                                    "id=${track.id} title=${track.title}",
                            )
                            _state.value = _state.value.copy(
                                done = n,
                                message = context.getString(
                                    R.string.rg_progress,
                                    track.title,
                                    n,
                                    tracks.size,
                                ),
                            )
                        }
                    }
                }.awaitAll()
            }
            AmpLog.i(
                "ReplayGain",
                "scan done source=$sourceId measured=${measured.get()} tags=${fromTags.get()} " +
                    "skipped=${skipped.get()} failed=${failed.get()}",
            )
            _state.value = LoudnessScanState(
                running = false,
                sourceId = sourceId,
                done = done.get(),
                total = tracks.size,
                message = if (failed.get() > 0) {
                    context.getString(
                        R.string.rg_done_with_failed,
                        measured.get(),
                        skipped.get(),
                        failed.get(),
                    )
                } else {
                    context.getString(R.string.rg_done, measured.get(), skipped.get())
                },
            )
            true
        } catch (t: CancellationException) {
            AmpLog.w(
                "ReplayGain",
                "scan cancelled source=$sourceId done=${done.get()}/${tracks.size}",
            )
            _state.value = LoudnessScanState(
                running = false,
                sourceId = sourceId,
                done = done.get(),
                total = tracks.size,
                message = context.getString(
                    R.string.rg_cancelled,
                    done.get(),
                    tracks.size,
                ),
            )
            throw t
        } catch (t: Throwable) {
            AmpLog.e(
                "ReplayGain",
                "scan aborted source=$sourceId done=${done.get()}/${tracks.size}",
                t,
            )
            _state.value = LoudnessScanState(
                running = false,
                sourceId = sourceId,
                done = done.get(),
                total = tracks.size,
                message = context.getString(
                    R.string.rg_cancelled,
                    done.get(),
                    tracks.size,
                ),
            )
            false
        }
    }

    private suspend fun tracksFor(sourceId: String): List<TrackEntity> = when {
        sourceId.startsWith("playlist:") ->
            sourceId.removePrefix("playlist:").toLongOrNull()?.let { library.getPlaylistTracks(it) }.orEmpty()
        sourceId.startsWith("dir:") -> library.getDirectoryTracks(sourceId.removePrefix("dir:"))
        else -> emptyList()
    }

    private suspend fun analyzeTrack(track: TrackEntity): TrackAnalysis {
        val entry = repository.get(track.id)
        val loudnessOk = entry != null && repository.isCurrent(entry, track)
        val waveformOk = entry != null && repository.isWaveformCurrent(entry, track)
        if (loudnessOk && waveformOk) {
            return TrackAnalysis(TrackOutcome.Current)
        }
        val uri = library.contentUriFor(track.id)
        var loudness: LoudnessResult? = null
        var loudnessSource: String? = null
        if (!loudnessOk) {
            val tagged = readTags(track, uri.toString())
            if (tagged != null) {
                loudness = tagged
                loudnessSource = "tag"
            }
        }
        val needDecode = !waveformOk || (loudness == null && !loudnessOk)
        var waveform: ByteArray? = null
        if (needDecode) {
            val measured = decoder.measure(track, uri)
            if (measured == null) {
                if (loudness != null && loudnessSource != null) {
                    repository.upsert(track, loudness, loudnessSource)
                    return TrackAnalysis(TrackOutcome.Tag, loudness, note = " waveform=failed")
                }
                AmpLog.w("ReplayGain", "measure failed id=${track.id} title=${track.title}")
                return TrackAnalysis(TrackOutcome.Failed)
            }
            if (!loudnessOk && loudness == null) {
                loudness = measured.loudness
                loudnessSource = "scan"
            }
            if (!waveformOk) waveform = measured.waveform
        }
        val note = buildString {
            if (loudnessOk) append(" loudness=kept")
            if (waveformOk) append(" waveform=kept")
            if (waveform != null) append(" waveform=saved")
        }
        when {
            loudness != null && loudnessSource != null ->
                repository.upsert(track, loudness, loudnessSource, waveform)
            waveform != null -> repository.saveWaveform(track, waveform)
        }
        val outcome = when {
            loudnessSource == "tag" -> TrackOutcome.Tag
            loudnessSource == "scan" || waveform != null -> TrackOutcome.Scan
            else -> TrackOutcome.Failed
        }
        return TrackAnalysis(outcome, loudness, note)
    }

    private fun readTags(track: TrackEntity, uriString: String): LoudnessResult? {
        val bytes = readHeader(track, uriString) ?: return null
        return ReplayGainTags.parse(bytes)
    }

    private fun readHeader(track: TrackEntity, uriString: String): ByteArray? {
        val file = File(track.path)
        if (file.isFile) {
            return runCatching { file.inputStream().use { it.readNBytesSafe(256 * 1024) } }.getOrNull()
        }
        val uri = android.net.Uri.parse(uriString)
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readNBytesSafe(256 * 1024) }
        }.getOrNull()
    }

    private data class TrackAnalysis(
        val outcome: TrackOutcome,
        val result: LoudnessResult? = null,
        val note: String = "",
    )

    private enum class TrackOutcome { Current, Tag, Scan, Failed }

    companion object {
        private const val SCAN_WORKERS = 2
    }
}

private fun java.io.InputStream.readNBytesSafe(max: Int): ByteArray {
    val buf = ByteArray(max)
    var offset = 0
    while (offset < max) {
        val n = read(buf, offset, max - offset)
        if (n <= 0) break
        offset += n
    }
    return if (offset == max) buf else buf.copyOf(offset)
}
