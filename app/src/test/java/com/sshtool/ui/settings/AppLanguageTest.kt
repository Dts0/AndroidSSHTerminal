package com.sshtool.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AppLanguageTest {
    @Test fun autoIsRepresentedByAnEmptyOverride() {
        assertEquals("", AppLanguage.AUTO.languageTag)
        assertEquals(AppLanguage.AUTO, AppLanguage.fromLanguageTags(""))
        assertEquals(AppLanguage.AUTO, AppLanguage.fromLanguageTags("  "))
    }

    @Test fun supportedChoicesRoundTripAndHaveDistinctLabels() {
        assertEquals(listOf("", "en", "zh-Hans", "zh-Hant", "ja"),
            AppLanguage.values().map { it.languageTag })
        AppLanguage.values().forEach {
            assertEquals(it, AppLanguage.fromLanguageTags(it.languageTag))
        }
        assertEquals(5, AppLanguage.values().map { it.labelRes }.toSet().size)
    }

    @Test fun regionalEnglishAndJapaneseOverridesAreRecognized() {
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromLanguageTags("en-US"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromLanguageTags("en-GB"))
        assertEquals(AppLanguage.JAPANESE, AppLanguage.fromLanguageTags("ja-JP"))
    }

    @Test fun simplifiedChineseRegionsAreRecognized() {
        listOf("zh", "zh-CN", "zh-SG", "zh-Hans-CN").forEach {
            assertEquals(it, AppLanguage.SIMPLIFIED_CHINESE, AppLanguage.fromLanguageTags(it))
        }
    }

    @Test fun traditionalChineseRegionsAreRecognized() {
        listOf("zh-TW", "zh-HK", "zh-MO", "zh-Hant-TW").forEach {
            assertEquals(it, AppLanguage.TRADITIONAL_CHINESE, AppLanguage.fromLanguageTags(it))
        }
    }

    @Test fun explicitScriptTakesPrecedenceOverRegion() {
        assertEquals(AppLanguage.SIMPLIFIED_CHINESE, AppLanguage.fromLanguageTags("zh-Hans-HK"))
        assertEquals(AppLanguage.TRADITIONAL_CHINESE, AppLanguage.fromLanguageTags("zh-Hant-CN"))
    }

    @Test fun firstOverrideDeterminesTheSelectedChoiceNotTheSystemLanguage() {
        assertEquals(AppLanguage.JAPANESE, AppLanguage.fromLanguageTags("ja,en"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromLanguageTags("fr-FR,zh-Hant"))
        assertNotEquals(AppLanguage.AUTO, AppLanguage.fromLanguageTags("fr-FR"))
    }
}
