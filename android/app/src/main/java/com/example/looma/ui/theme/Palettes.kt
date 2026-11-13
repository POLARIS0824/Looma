package com.example.looma.ui.theme

import androidx.compose.material3.ColorScheme
import com.example.looma.data.settings.ThemePreset

internal fun presetScheme(preset: ThemePreset, dark: Boolean): ColorScheme =
    (if (dark) PresetDarkSchemes else PresetLightSchemes)[preset]
        ?: if (dark) LoomaDarkColors else LoomaLightColors
