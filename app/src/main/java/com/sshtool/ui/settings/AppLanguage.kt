package com.sshtool.ui.settings

import androidx.annotation.StringRes
import com.sshtool.R
import java.util.Locale

/** An empty tag means no override; Android chooses from the system locale list. */
enum class AppLanguage(val languageTag: String, @StringRes val labelRes: Int) {
    AUTO("", R.string.language_auto),
    ENGLISH("en", R.string.language_english),
    SIMPLIFIED_CHINESE("zh-Hans", R.string.language_simplified_chinese),
    TRADITIONAL_CHINESE("zh-Hant", R.string.language_traditional_chinese),
    JAPANESE("ja", R.string.language_japanese);

    companion object {
        /** Also recognize regional tags supplied through Android's app-language settings. */
        fun fromLanguageTags(tags: String): AppLanguage {
            val tag = tags.substringBefore(',').trim()
            if (tag.isEmpty()) return AUTO
            val locale = Locale.forLanguageTag(tag)
            return when (locale.language) {
                "en" -> ENGLISH
                "ja" -> JAPANESE
                "zh" -> when {
                    locale.script.equals("Hant", ignoreCase = true) -> TRADITIONAL_CHINESE
                    locale.script.equals("Hans", ignoreCase = true) -> SIMPLIFIED_CHINESE
                    locale.country in setOf("TW", "HK", "MO") -> TRADITIONAL_CHINESE
                    else -> SIMPLIFIED_CHINESE
                }
                // Unsupported overrides are displayed as English, the resource fallback.
                else -> ENGLISH
            }
        }
    }
}
