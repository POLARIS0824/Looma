package com.example.looma.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.example.looma.data.settings.ThemePreset
import org.junit.Assert.assertTrue
import org.junit.Test

class PalettesTest {

    private val presets = ThemePreset.entries.filter { it.seed != null }

    @Test
    fun `on roles are readable on their containers`() = forEachScheme { scheme, check ->
        listOf(
            Triple("onPrimary", scheme.primary, scheme.onPrimary),
            Triple("onPrimaryContainer", scheme.primaryContainer, scheme.onPrimaryContainer),
            Triple("onSecondary", scheme.secondary, scheme.onSecondary),
            Triple("onSecondaryContainer", scheme.secondaryContainer, scheme.onSecondaryContainer),
            Triple("onTertiary", scheme.tertiary, scheme.onTertiary),
            Triple("onTertiaryContainer", scheme.tertiaryContainer, scheme.onTertiaryContainer),
            Triple("onError", scheme.error, scheme.onError),
            Triple("onErrorContainer", scheme.errorContainer, scheme.onErrorContainer),
            Triple("onBackground", scheme.background, scheme.onBackground),
            Triple("onSurface", scheme.surface, scheme.onSurface),
            Triple("onSurfaceVariant", scheme.surfaceVariant, scheme.onSurfaceVariant),
            Triple("inverseOnSurface", scheme.inverseSurface, scheme.inverseOnSurface),
            Triple("onSurface/containerLowest", scheme.surfaceContainerLowest, scheme.onSurface),
            Triple("onSurface/containerLow", scheme.surfaceContainerLow, scheme.onSurface),
            Triple("onSurface/container", scheme.surfaceContainer, scheme.onSurface),
            Triple("onSurface/containerHigh", scheme.surfaceContainerHigh, scheme.onSurface),
            Triple("onSurface/containerHighest", scheme.surfaceContainerHighest, scheme.onSurface),
            Triple("onPrimaryFixed", scheme.primaryFixed, scheme.onPrimaryFixed),
            Triple("onPrimaryFixedVariant", scheme.primaryFixedDim, scheme.onPrimaryFixedVariant),
            Triple("onSecondaryFixed", scheme.secondaryFixed, scheme.onSecondaryFixed),
            Triple("onSecondaryFixedVariant", scheme.secondaryFixedDim, scheme.onSecondaryFixedVariant),
            Triple("onTertiaryFixed", scheme.tertiaryFixed, scheme.onTertiaryFixed),
            Triple("onTertiaryFixedVariant", scheme.tertiaryFixedDim, scheme.onTertiaryFixedVariant),
        ).forEach { (role, background, foreground) -> check(role, background, foreground, 4.5) }
    }

    @Test
    fun `accent roles stand out on surface`() = forEachScheme { scheme, check ->
        check("primary/surface", scheme.surface, scheme.primary, 4.5)
        check("error/surface", scheme.surface, scheme.error, 4.5)
        check("secondary/surface", scheme.surface, scheme.secondary, 3.0)
        check("tertiary/surface", scheme.surface, scheme.tertiary, 3.0)
    }

    @Test
    fun `surface containers step in order`() {
        val failures = mutableListOf<String>()
        presets.forEach { preset ->
            listOf(false, true).forEach { dark ->
                val scheme = presetScheme(preset, dark)
                val levels = listOf(
                    scheme.surfaceContainerLowest,
                    scheme.surfaceContainerLow,
                    scheme.surfaceContainer,
                    scheme.surfaceContainerHigh,
                    scheme.surfaceContainerHighest,
                ).map { it.luminance() }
                val ordered = levels.zipWithNext().all { (a, b) -> if (dark) a < b else a > b }
                if (!ordered) failures += "${label(preset, dark)} containers $levels"
                val dim = scheme.surfaceDim.luminance()
                val surface = scheme.surface.luminance()
                val bright = scheme.surfaceBright.luminance()
                if (!(dim <= surface && surface <= bright && dim < bright)) {
                    failures += "${label(preset, dark)} dim/surface/bright $dim $surface $bright"
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `fixed roles come from the preset`() {
        val failures = mutableListOf<String>()
        presets.forEach { preset ->
            listOf(false, true).forEach { dark ->
                val scheme = presetScheme(preset, dark)
                val baseline = if (dark) darkColorScheme() else lightColorScheme()
                listOf(
                    "primaryFixed" to (scheme.primaryFixed to baseline.primaryFixed),
                    "secondaryFixed" to (scheme.secondaryFixed to baseline.secondaryFixed),
                    "tertiaryFixed" to (scheme.tertiaryFixed to baseline.tertiaryFixed),
                ).forEach { (role, pair) ->
                    if (pair.first == pair.second) failures += "${label(preset, dark)} $role is baseline"
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun forEachScheme(
        block: (ColorScheme, (String, Color, Color, Double) -> Unit) -> Unit,
    ) {
        val failures = mutableListOf<String>()
        presets.forEach { preset ->
            listOf(false, true).forEach { dark ->
                block(presetScheme(preset, dark)) { role, background, foreground, minimum ->
                    val ratio = contrast(background, foreground)
                    if (ratio < minimum) {
                        failures += "${label(preset, dark)} $role was %.2f, expected %.1f".format(ratio, minimum)
                    }
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun label(preset: ThemePreset, dark: Boolean) = "$preset ${if (dark) "dark" else "light"}"

    private fun contrast(a: Color, b: Color): Double {
        val first = a.luminance() + 0.05
        val second = b.luminance() + 0.05
        return if (first > second) first / second else second / first
    }
}
