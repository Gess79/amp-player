package io.github.gess79.amp.playback

object PlayerCommands {
    const val START_SHUFFLE = "io.github.gess79.amp.START_SHUFFLE"
    const val RESUME_SHUFFLE = "io.github.gess79.amp.RESUME_SHUFFLE"
    const val TOGGLE_FAVORITE = "io.github.gess79.amp.TOGGLE_FAVORITE"
    const val SET_SLEEP_TIMER = "io.github.gess79.amp.SET_SLEEP_TIMER"
    const val TOGGLE_SHUFFLE = "io.github.gess79.amp.TOGGLE_SHUFFLE"
    const val SET_SHUFFLE = "io.github.gess79.amp.SET_SHUFFLE"
    const val SKIP_NEXT = "io.github.gess79.amp.SKIP_NEXT"
    const val SKIP_PREVIOUS = "io.github.gess79.amp.SKIP_PREVIOUS"
    const val PLAY_SOURCE = "io.github.gess79.amp.PLAY_SOURCE"

    /** Continue the source's saved queue (order, track, position, shuffle flag). */
    const val MODE_RESUME = "resume"
    /** Continue the saved round if it is shuffled, otherwise start a new shuffle round. */
    const val MODE_SHUFFLE_PLAY = "shuffle_play"
    /** Flip the source's shuffle flag; switching it on always mixes a new order. */
    const val MODE_TOGGLE_SHUFFLE = "toggle_shuffle"
    /** Play [ARG_START_TRACK_ID] now inside the source's current round (no new round). */
    const val MODE_PLAY_TRACK = "play_track"

    const val ARG_SOURCE_ID = "source_id"
    const val ARG_TRACK_IDS = "track_ids"
    const val ARG_START_TRACK_ID = "start_track_id"
    const val ARG_SLEEP_MINUTES = "sleep_minutes"
    const val ARG_SHUFFLE_ENABLED = "shuffle_enabled"
    const val ARG_PLAY_MODE = "play_mode"
}
