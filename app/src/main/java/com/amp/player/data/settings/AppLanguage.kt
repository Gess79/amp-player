package com.amp.player.data.settings

/** Persisted app UI language. Empty tag / unset = follow system. */
enum class AppLanguage(val tag: String) {
    SYSTEM(""),
    GERMAN("de"),
    ENGLISH("en");

    companion object {
        val DEFAULT = SYSTEM

        fun fromStored(tag: String?): AppLanguage =
            if (tag == null) DEFAULT else entries.firstOrNull { it.tag == tag } ?: DEFAULT
    }
}
