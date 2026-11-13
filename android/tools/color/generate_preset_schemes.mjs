import { readFileSync, writeFileSync } from 'node:fs';
import { registerHooks } from 'node:module';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

registerHooks({
  resolve(specifier, context, nextResolve) {
    try {
      return nextResolve(specifier, context);
    } catch (error) {
      const relative = specifier.startsWith('./') || specifier.startsWith('../');
      if (error.code !== 'ERR_MODULE_NOT_FOUND' || !relative || specifier.endsWith('.js')) throw error;
      return nextResolve(`${specifier}.js`, context);
    }
  },
});

const { Hct, SchemeTonalSpot } = await import('@material/material-color-utilities');

const SPEC_VERSION = '2021';
const CONTRAST_LEVEL = 0;

const here = dirname(fileURLToPath(import.meta.url));
const appSource = resolve(here, '../../app/src/main/java/com/example/looma');
const settingsFile = resolve(appSource, 'data/settings/AppSettings.kt');
const outputFile = resolve(appSource, 'ui/theme/PresetSchemes.kt');

const roles = [
  'primary', 'onPrimary', 'primaryContainer', 'onPrimaryContainer', 'inversePrimary',
  'secondary', 'onSecondary', 'secondaryContainer', 'onSecondaryContainer',
  'tertiary', 'onTertiary', 'tertiaryContainer', 'onTertiaryContainer',
  'background', 'onBackground', 'surface', 'onSurface', 'surfaceVariant', 'onSurfaceVariant',
  'surfaceTint', 'inverseSurface', 'inverseOnSurface',
  'error', 'onError', 'errorContainer', 'onErrorContainer',
  'outline', 'outlineVariant', 'scrim',
  'surfaceBright', 'surfaceContainer', 'surfaceContainerHigh', 'surfaceContainerHighest',
  'surfaceContainerLow', 'surfaceContainerLowest', 'surfaceDim',
  'primaryFixed', 'primaryFixedDim', 'onPrimaryFixed', 'onPrimaryFixedVariant',
  'secondaryFixed', 'secondaryFixedDim', 'onSecondaryFixed', 'onSecondaryFixedVariant',
  'tertiaryFixed', 'tertiaryFixedDim', 'onTertiaryFixed', 'onTertiaryFixedVariant',
];

function readPresets() {
  const source = readFileSync(settingsFile, 'utf8');
  const body = source.match(/enum class ThemePreset\([^)]*\)\s*\{([\s\S]*?)\}/);
  if (!body) throw new Error(`ThemePreset not found in ${settingsFile}`);
  const presets = [...body[1].matchAll(/(\w+)\(Color\(0x([0-9A-Fa-f]{8})\)\)/g)]
    .map(([, name, argb]) => ({ name, argb: parseInt(argb, 16) >>> 0 }));
  if (presets.length === 0) throw new Error('ThemePreset has no seeded entries');
  return presets;
}

function hex(argb) {
  return `0x${(argb >>> 0).toString(16).toUpperCase().padStart(8, '0')}`;
}

function schemeCall(seed, dark) {
  const scheme = new SchemeTonalSpot(Hct.fromInt(seed), dark, CONTRAST_LEVEL, SPEC_VERSION);
  const args = roles.map((role) => {
    const value = scheme[role];
    if (typeof value !== 'number') throw new Error(`Missing role ${role}`);
    return `    ${role} = Color(${hex(value)}),`;
  });
  return `${dark ? 'darkColorScheme' : 'lightColorScheme'}(\n${args.join('\n')}\n)`;
}

function render(presets) {
  const lines = [
    'package com.example.looma.ui.theme',
    '',
    'import androidx.compose.material3.ColorScheme',
    'import androidx.compose.material3.darkColorScheme',
    'import androidx.compose.material3.lightColorScheme',
    'import androidx.compose.ui.graphics.Color',
    'import com.example.looma.data.settings.ThemePreset',
    '',
  ];
  for (const { name, argb } of presets) {
    lines.push(`private val ${name}Light: ColorScheme = ${schemeCall(argb, false)}`, '');
    lines.push(`private val ${name}Dark: ColorScheme = ${schemeCall(argb, true)}`, '');
  }
  for (const mode of ['Light', 'Dark']) {
    lines.push(`internal val Preset${mode}Schemes: Map<ThemePreset, ColorScheme> = mapOf(`);
    for (const { name } of presets) lines.push(`    ThemePreset.${name} to ${name}${mode},`);
    lines.push(')', '');
  }
  return lines.join('\n');
}

const presets = readPresets();
writeFileSync(outputFile, render(presets));
console.log(`Wrote ${presets.length} presets (SchemeTonalSpot, spec ${SPEC_VERSION}, contrast ${CONTRAST_LEVEL}) to ${outputFile}`);
