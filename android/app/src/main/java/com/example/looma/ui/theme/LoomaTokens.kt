package com.example.looma.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

@Immutable
data class LoomaColors(
    val page: Color,
    val noteCanvas: Color,
    val card: Color,
    val cardInset: Color,
    val sheet: Color,
    val searchField: Color,
)

@Immutable
data class LoomaTypography(
    val heroTitle: TextStyle,
    val count: TextStyle,
    val cardMeta: TextStyle,
    val homeNotePreview: LoomaPreviewTypography,
    val noteBody: TextStyle,
    val noteHeadings: LoomaNoteHeadings,
    val noteCode: TextStyle,
)

@Immutable
data class LoomaNoteHeadings(
    val h1: TextStyle,
    val h2: TextStyle,
    val h3: TextStyle,
    val h4: TextStyle,
    val h5: TextStyle,
    val h6: TextStyle,
)

@Immutable
data class LoomaPreviewTypography(
    val body: TextStyle,
    val headings: LoomaNoteHeadings,
    val code: TextStyle,
)

fun loomaColors(scheme: ColorScheme): LoomaColors {
    val dark = scheme.surface.luminance() < 0.5f
    val raised = if (dark) scheme.surfaceContainerHigh else scheme.surface
    return LoomaColors(
        page = if (dark) scheme.surface else scheme.surfaceContainer,
        noteCanvas = scheme.surface,
        card = raised,
        cardInset = if (dark) scheme.surfaceContainerHighest else scheme.surfaceContainer,
        sheet = if (dark) scheme.surfaceContainerLow else scheme.surfaceContainer,
        searchField = raised,
    )
}

fun loomaTypography(base: Typography): LoomaTypography = LoomaTypography(
    heroTitle = base.headlineLargeEmphasized.copy(fontWeight = FontWeight(BRAND_HERO_WEIGHT)),
    count = base.labelMediumEmphasized.copy(fontFeatureSettings = TABULAR_FIGURES),
    cardMeta = base.labelMedium.copy(fontFeatureSettings = TABULAR_FIGURES),
    homeNotePreview = LoomaPreviewTypography(
        body = base.bodyLarge,
        headings = LoomaNoteHeadings(
            h1 = base.titleMediumEmphasized,
            h2 = base.bodyLargeEmphasized,
            h3 = base.bodyLargeEmphasized,
            h4 = base.bodyMediumEmphasized,
            h5 = base.bodyMediumEmphasized,
            h6 = base.bodySmall,
        ),
        code = base.bodyMedium.copy(fontFamily = FontFamily.Monospace),
    ),
    noteBody = base.bodyLarge.copy(
        lineHeight = BODY_LINE_HEIGHT.sp,
        letterSpacing = BODY_LETTER_SPACING.sp,
    ),
    noteHeadings = LoomaNoteHeadings(
        h1 = base.headlineMediumEmphasized,
        h2 = base.headlineSmallEmphasized,
        h3 = base.titleLargeEmphasized,
        h4 = base.titleMediumEmphasized,
        h5 = base.titleSmallEmphasized,
        h6 = base.titleSmall,
    ),
    noteCode = base.bodyMedium.copy(fontFamily = FontFamily.Monospace, lineHeight = BODY_LINE_HEIGHT.sp),
)

internal const val TABULAR_FIGURES = "\"tnum\" 1"

private const val BRAND_HERO_WEIGHT = 750

internal const val BODY_LINE_HEIGHT = 27
private const val BODY_LETTER_SPACING = 0.15f

val LocalLoomaColors = staticCompositionLocalOf { loomaColors(lightColorScheme()) }
val LocalLoomaTypography = staticCompositionLocalOf { loomaTypography(Typography()) }

object LoomaTokens {
    val colors: LoomaColors
        @Composable @ReadOnlyComposable get() = LocalLoomaColors.current

    val type: LoomaTypography
        @Composable @ReadOnlyComposable get() = LocalLoomaTypography.current
}
