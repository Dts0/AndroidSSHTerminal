package com.sshtool.ui.settings

import com.sshtool.R
import com.sshtool.util.UserMessage
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/** Checks the actual XML, not duplicate test-only translation tables. */
class LocalizationResourcesTest {
    private val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "app/src/main/res/values/strings.xml").isFile }
    private val locales = listOf("values-b+zh+Hans", "values-b+zh+Hant", "values-ja")
    private val formatArgument = Regex("%[0-9]+\\\$[a-zA-Z]")

    private fun elements(file: File, tag: String): List<Element> {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val nodes = factory.newDocumentBuilder().parse(file).getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun strings(module: String, directory: String) = elements(
        File(root, "$module/src/main/res/$directory/strings.xml"), "string"
    ).associate { it.getAttribute("name") to it }

    @Test fun everyTranslatableKeyExistsInAllSupportedLanguages() {
        listOf("app", "termux-terminal-view").forEach { module ->
            val base = strings(module, "values")
            val expected = base.filterValues { it.getAttribute("translatable") != "false" }.keys
            locales.forEach { directory ->
                val translated = strings(module, directory)
                assertEquals("$module/$directory", expected, translated.keys)
                expected.forEach { key ->
                    assertTrue("Empty $directory/$key", translated.getValue(key).textContent.isNotBlank())
                }
            }
        }
    }

    @Test fun translationsPreserveFormatArgumentsAndTheirTypes() {
        listOf("app", "termux-terminal-view").forEach { module ->
            val base = strings(module, "values")
            locales.forEach { directory ->
                strings(module, directory).forEach { (key, element) ->
                    assertEquals("$module/$directory/$key",
                        formatArgument.findAll(base.getValue(key).textContent).map { it.value }.sorted().toList(),
                        formatArgument.findAll(element.textContent).map { it.value }.sorted().toList())
                }
            }
        }
    }

    @Test fun keysAreUniqueAndStandardKeyNamesAreNotTranslated() {
        listOf("app", "termux-terminal-view").forEach { module ->
            (listOf("values") + locales).forEach { directory ->
                val entries = elements(File(root, "$module/src/main/res/$directory/strings.xml"), "string")
                assertEquals(entries.size, entries.map { it.getAttribute("name") }.toSet().size)
            }
        }
        val base = strings("app", "values")
        listOf("key_ctrl", "key_tab", "key_esc", "key_pgup", "key_pgdn", "key_home", "key_end",
            "key_del", "key_enter", "key_up", "key_down", "key_left", "key_right").forEach {
            assertEquals(it, "false", base.getValue(it).getAttribute("translatable"))
        }
    }

    @Test fun pasteKeyUsesALocalizedActionRatherThanAnEnglishKeyName() {
        val expected = listOf("Paste", "粘贴", "貼上", "貼り付け")
        (listOf("values") + locales).forEachIndexed { index, directory ->
            assertEquals(expected[index], strings("app", directory).getValue("key_paste").textContent)
        }
    }

    @Test fun systemLocaleConfigurationMatchesTheManualChoices() {
        val configured = elements(File(root, "app/src/main/res/xml/locales_config.xml"), "locale")
            .map { it.getAttribute("android:name") }
        assertEquals(AppLanguage.values().filter { it != AppLanguage.AUTO }.map { it.languageTag }, configured)
    }

    @Test fun structuredErrorsResolveUsingEachActualTranslation() {
        val resourceNames = R.string::class.java.fields.associate { it.getInt(null) to it.name }
        val message = UserMessage(R.string.error_remote_directory_limit, 10000)
        (listOf("values") + locales).forEach { directory ->
            val resources = strings("app", directory)
            val result = message.resolve { id, args ->
                String.format(Locale.ROOT, resources.getValue(resourceNames.getValue(id)).textContent, *args)
            }
            assertTrue(directory, result.contains("10000"))
            assertFalse(directory, result.contains("%1"))
        }
    }

    @Test fun readmeLanguageLinksAreValidRelativePaths() {
        listOf("README.md", "docs/README_zh.md").forEach { path ->
            val readme = File(root, path)
            val languageLinks = readme.readLines().first { it.startsWith("[English]") }
            val links = Regex("\\]\\(([^)]+)\\)").findAll(languageLinks).map { it.groupValues[1] }.toList()
            assertEquals(2, links.size)
            links.forEach { assertTrue("Broken $path link: $it", File(readme.parentFile, it).isFile) }
        }
    }
}
