package com.amp.player.data.settings

import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

object AppLocale {
    private val mainHandler = Handler(Looper.getMainLooper())

    fun apply(language: AppLanguage) {
        val locales = if (language == AppLanguage.SYSTEM) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(language.tag)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            AppCompatDelegate.setApplicationLocales(locales)
        } else {
            mainHandler.post { AppCompatDelegate.setApplicationLocales(locales) }
        }
    }

    /**
     * Application / Glance contexts often ignore per-app locales.
     * Wrap them so [Context.getString] matches the Settings language choice.
     */
    fun wrap(context: Context): Context {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) return context
        val config = Configuration(context.resources.configuration)
        config.setLocales(android.os.LocaleList.forLanguageTags(locales.toLanguageTags()))
        return context.createConfigurationContext(config)
    }

    fun current(): AppLanguage {
        val tags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        return when {
            tags.isBlank() -> AppLanguage.SYSTEM
            tags.startsWith("de", ignoreCase = true) -> AppLanguage.GERMAN
            tags.startsWith("en", ignoreCase = true) -> AppLanguage.ENGLISH
            else -> AppLanguage.SYSTEM
        }
    }
}
