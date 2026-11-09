# Looma Android verification map

This is the maintained Android user-journey index. Every feature file below was checked against source and driven live on `looma36` (API 36) during the 2026-11-09 maintenance pass, except the stalled-relocation banner, which no user path can create. Consult each run's `coverage.md` for that run's actual coverage.

## Baseline preconditions

- Follow [Launch and Doctor](../SKILL.md); use one owned, English-language API 30+ emulator serial and an empty, unique primary-storage directory.
- `launch` creates the empty directory, not notes. First run [vault connection](vault.md) through Android Settings and the system folder picker.
- Other recipes start on connected Home, with keyboard/search/selection closed. Use real UI note creation for the fixtures they name. Do not drive multiple flows concurrently.
- Run the `v` Nushell wrapper defined in the skill. `v ui` reveals exact accessible names; no guessed tap coordinates.

## Driving conventions

- Commands are relative to `android/`. `v doctor` precedes driving and follows unexpected results; use `--system` only for expected Android Settings/DocumentsUI.
- Select exact text/description. Duplicate titles may occur in Recent and Notes; inspect `v ui` and specify `--index` deliberately.
- `v capture <unique-name>` records UI and logs; `v files <unique-name>` copies actual vault bytes. Open images and inspect bytes.
- Create prerequisites through the UI. The only exception is `v fixture`, which stands in for something outside Looma: a photo in the gallery, or another editor changing a vault file. Missing data, permissions or system providers must be reported with the attempted route. Do not weaken the journey.
- Destructive operations apply only to this run's disposable vault. Retain evidence through cleanup.

## Features

| Feature | User surfaces |
| --- | --- |
| [Vault connection](vault.md) | First launch, all-files access, primary folder picker, pending confirmation, relaunch, change vault, unavailable vault |
| [Notes](notes.md) | New note, single-field Markdown editing, autosave, Read/Edit, toolbar and its scrolling, checklists, wiki links and backlinks, image insert, math, conflict copies and Overwrite |
| [Browse, files and search](browse-search.md) | Root and folders, Recent/Notes, layout and sort, rename/move with link rewriting, file info, batch actions, Undo, search and history, stalled relocation (source-only) |
| [Trash](trash.md) | Delete and Undo, folder delete, restore and its Undo, delete permanently, restore all, empty trash |
| [Settings](settings.md) | Avatar and drawer entries, vault and access, theme presets and Dynamic color, language, toolbar order, keep empty notes, inline title, image destination and S3 image bed |

## Proof and skip reporting

Record sub-feature ID, entry point, actual action/result and artifact names in run-local `coverage.md`. A skipped entry is unverified even if another route reaches the same screen. `verified-unreachable` requires the attempted route and a concrete OS/provider/input prerequisite. The initial one-feature proof does not establish complete release acceptance. Use `/maintain-verification-skill` to check the whole map as the product evolves.
