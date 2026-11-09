# Configure Looma

Settings shows the connected vault and file access, controls appearance and language, sets note behavior and the editor toolbar order, and chooses where inserted images go.

## Sub-features

- `settings-entry`: the Home avatar (description `Settings`) and the drawer each open Settings; Back returns.
- `settings-vault`: `Notes vault` shows the folder name, path and Change vault; `File access` shows `All files access granted`, or `All files access not granted` with Grant access.
- `settings-theme`: Dynamic color (`Use wallpaper colors (Android 12+)`) and Color theme presets `Looma`, `Indigo`, `Teal`, `Rose`, `Amber`. Picking a preset also turns Dynamic color off.
- `settings-language`: `Follow system`, `English` and `简体中文`.
- `settings-notes`: `Editor toolbar order` sheet, `Keep empty notes` (default off) and `Show inline title` (default on).
- `settings-images`: `Image upload destination` (`Local attachment` or `Image bed`) and the `S3 image bed` sheet.

## How to get to it (user POV)

- Home avatar, or Home → Open navigation → Settings.
- Sections in order: Notes vault, Appearance, Language, Notes, Images, File access, About. File access and About need two `v swipe up` on a 1920 px screen.

## Driving it with control.py

Preconditions: connected Home, English UI, API 31+, doctor passes.

- **Entries.** `v tap --desc Settings`; `v assert --text 'Notes vault'`; `v tap --desc Back`. Then `v tap --desc 'Open navigation'`; `v tap --text Settings`. The drawer header also shows the vault name.
- **Theme.** `v assert --text Wallpaper` while Dynamic color is on. `v tap --text 'Color theme' --index 0`; `v tap --text Teal`; the row now reads `Teal` and the Dynamic color switch is unchecked. `v relaunch`; reopen and assert `Teal`.
- **Language.** Header and row are both `Language`: `v tap --text Language --index 1`; `v tap --text 简体中文`; `v doctor`. In Chinese the row is `语言`: `v tap --text 语言 --index 1`; `v tap --text English` (the option text is the same in every locale). Doctor again before continuing.
- **Switches.** Compose switches are `android.view.View` nodes with `checkable=true`, not `android.widget.Switch`. Use `v toggle 'Keep empty notes'` and `v toggle 'Show inline title'`; each prints the new `checked` value.
- **Keep empty notes.** With it on: `v tap --desc 'New note'`; `v tap --desc Back`; `v files` shows the new empty `.md`. With it off the untouched note is deleted on leave.
- **Show inline title.** With it off, a note whose file name differs from its first heading shows only the heading in Read: `v assert --text 'Untitled note' --absent`.
- **Toolbar order.** `v tap --text 'Editor toolbar order'`; `v swipe up` (the sheet opens half-height and the first rows sit under the navigation bar); `v tap --desc 'Move Italic up'`; `v tap --text Save`. A new note's toolbar shows Italic before Bold, and the order survives relaunch. `Reset to default` and `Cancel` change nothing until Save.
- **Images.** `v tap --text 'Image upload destination'`; `v tap --text 'Image bed'`. `v tap --text 'S3 image bed'`; `v swipe up` to expand the sheet. Tap each field by its label text (`Endpoint`, `Region`, `Bucket`, `Access key`, `Secret key`) and `v text` a value; the keyboard resizes the sheet, so EditText indices shift. `v key KEYCODE_BACK` hides the keyboard. `Path style access` and `Public read ACL` default on. `v swipe up`; `v tap --text Save`. The row reads `<bucket> · <endpoint>` and survives relaunch. Set the destination back to `Local attachment` before image recipes that expect `![[…]]`.
- **About.** After two `v swipe up`: `All files access granted` and `Version 0.1.0` (from `versionName`).

## Gotchas

- Settings values live in app DataStore, not the vault. Prove them through the UI after `v relaunch`, not with `v files`.
- A real S3 upload needs a reachable endpoint; debug builds allow cleartext only to `10.0.2.2` and `localhost`. Without one, report uploads as unverified.
- Below API 31 Dynamic color is disabled with `Not supported on this version of Android`.
- Language changes recreate the UI; restore English for the other recipes.
