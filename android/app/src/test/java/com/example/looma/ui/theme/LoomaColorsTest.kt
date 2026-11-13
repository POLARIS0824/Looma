package com.example.looma.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.luminance
import com.example.looma.data.settings.ThemePreset
import org.junit.Assert.assertTrue
import org.junit.Test

class LoomaColorsTest {

    @Test
    fun `raised surfaces sit above the page in both themes`() {
        val failures = mutableListOf<String>()
        forEachScheme { label, scheme ->
            val colors = loomaColors(scheme)
            val page = colors.page.luminance()
            listOf(
                "card" to colors.card,
                "searchField" to colors.searchField,
            ).forEach { (role, color) ->
                val raised = color.luminance()
                if (page >= raised) {
                    failures += "$label $role %.3f was not above page %.3f".format(raised, page)
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun forEachScheme(block: (String, ColorScheme) -> Unit) {
        listOf(false, true).forEach { dark ->
            val mode = if (dark) "dark" else "light"
            block("baseline $mode", if (dark) darkColorScheme() else lightColorScheme())
            ThemePreset.entries.forEach { preset ->
                block("$preset $mode", presetScheme(preset, dark))
            }
        }
    }
}
