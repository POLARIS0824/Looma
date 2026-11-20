# Configure Looma

Settings shows the connected vault and file access, controls appearance and language, sets note behavior and the editor toolbar order, manages the local semantic search model and vault index, and chooses where inserted images go.

## Sub-features

- `settings-entry`: the Home avatar (description `Settings`) and the drawer each open Settings; Back returns.
- `settings-vault`: `Notes vault` shows the folder name, path and Change vault; `File access` shows `All files access granted`, or `All files access not granted` with Grant access.
- `settings-theme`: Dynamic color (`Use wallpaper colors (Android 12+)`) and Color theme presets `Looma`, `Indigo`, `Teal`, `Rose`, `Amber`. Picking a preset also turns Dynamic color off; while Dynamic color is on, `Color theme` shows `Wallpaper` and `Choosing a theme turns off dynamic color`.
- `settings-language`: `Follow system`, `English` and `简体中文`.
- `settings-notes`: `Editor toolbar order` sheet, `Keep empty notes` (default off) and `Show inline title` (default on).
- `settings-semantic-search`: `Search` → `Semantic search` sub-screen with `Find notes by meaning`, `On-device model` (`EmbeddingGemma 2 model`, `Model details` toggle, `One-time download · 157 MiB` / `Download model` / `Cancel` / `Retry`), and `Vault index` (`Build index` / `Update index` once model ready).
- `settings-images`: `Image upload destination` (`Local attachment` or `Image bed`, disabled until S3 is configured) and the `S3 image bed` sheet (credentials AES-GCM encrypted in Android KeyStore).

## How to get to it (user POV)

- Home avatar, or Home root → Open navigation → Settings.
- Sections in order: Notes vault, Appearance, Language, Notes, Search, Images, File access, About. Reaching `File access` and `About` on a 1920 px screen requires **three** `v swipe up` gestures from the top.

## Driving it with control.py

Preconditions: connected Home, English UI, API 31+, doctor passes.

- **Entries.** `v tap --desc Settings`; `v assert --text 'Notes vault'`; `v tap --desc Back`. Then `v tap --desc 'Open navigation'`; `v tap --text Settings`. The drawer header also shows the vault name.
- **Theme.** `v assert --text Wallpaper` and `v assert --text 'Choosing a theme turns off dynamic color'` while Dynamic color is on. `v tap --text 'Color theme'`; `v tap --text Teal`; the row now reads `Teal`, `Wallpaper` disappears and the Dynamic color switch is unchecked. `v relaunch`; reopen and assert `Teal`.
- **Language.** Header and row are both `Language`: `v tap --text Language --index 1`; `v tap --text 简体中文`; `v doctor`. Assert `设置`; in Chinese the row is `语言`: `v tap --text 语言 --index 1`; `v tap --text English` (the option text is the same in every locale). Doctor again before continuing.
- **Switches.** `SettingsSwitchRow` exposes a full-row `android.view.View` node with `checkable=true` (not `android.widget.Switch`). After one `v swipe up` from the top of Settings, `v toggle 'Keep empty notes'` and `v toggle 'Show inline title'` each toggle and print the new `checked` value.
- **Keep empty notes.** With it on: `v tap --desc 'Create new'`; `v tap --text 'New note'`; `v tap --desc Back`; `v files <name>` shows the new 0-byte `.md`. With it off the untouched note is deleted on leave.
- **Show inline title.** With it off, the file name moves from the page body to the top bar (`Edit` **still has two `EditText` nodes**, reversed: index `0` is the body, index `1` is the top-bar title!). It is editable in Edit and static text in Read (`v assert --text 'Untitled note'` still matches the top bar). Restore `Keep empty notes` off and `Show inline title` on.
- **Toolbar order.** `v tap --text 'Editor toolbar order'`. The sheet opens directly expanded (no initial `v swipe up` needed for `Move Italic up`); `v tap --desc 'Move Italic up'`; `v tap --text Save`. A new note's toolbar shows Italic bounds preceding Bold, and the order survives relaunch. `Reset to default` and `Cancel` change nothing until Save.
- **Semantic search.** After one `v swipe up`, `v tap --text 'Semantic search'`; `v assert --text 'Find notes by meaning'`; `v assert --text 'EmbeddingGemma 2 model'`; `v assert --text 'One-time download · 157 MiB'`; `v assert --text 'Download the model before building the index.'`; `v assert --text 'Build index' --absent`. Toggle `Model details` → `Hide model details` → `Model details`. `v swipe up` to check footer `You can leave this screen while the model downloads or the index builds.` (`v swipe down` before `v capture settings-semantic`). On API 33+, the first `v tap --text 'Download model'` raises `Allow Looma to send you notifications?` (`v doctor --system`, buttons `ALLOW` / `DON’T ALLOW` with U+2019). With external HTTPS access to `huggingface.co`, download progresses and enables `Build index`; offline/blocked devices show `Couldn’t download the model.` (U+2019) + `Retry`.
- **Images.** After 1–2 `v swipe up`, `v tap --text 'Image upload destination'`; while S3 is unconfigured, `Image bed` is disabled and shows `Configure the S3 image bed before choosing it`; dismiss with `v tap --text OK`. Then `v tap --text 'S3 image bed'` (opens directly expanded); tap `Endpoint`, `Region`, `Bucket`, then `v key KEYCODE_BACK` to hide the keyboard before tapping `Access key` and `Secret key` (hidden behind the IME), `v key KEYCODE_BACK`, `v swipe up`, assert `Path style access` and `Public read ACL`, and `v tap --text Save`. The row reads `<bucket> · <endpoint>`. Now `v tap --text 'Image upload destination'`; `v tap --text 'Image bed'`. `v relaunch`; reopen Settings, swipe twice to `Images`, assert `Image bed` and `<bucket> · <endpoint>`, then set destination back to `Local attachment` before image recipes expecting `![[…]]`.
- **About.** After **three** `v swipe up` gestures from the top of Settings: `All files access granted` and `Version 0.2.0` (from `versionName`).

## Gotchas

- Settings live in Preferences DataStore (`looma_settings_preferences`, with S3 keys AES-GCM encrypted in Android KeyStore), app language lives in Android/AppCompat per-app locale storage, and semantic search models/vectors live under `noBackupFilesDir/semantic-search/` — none in the vault. Prove them through the UI after `v relaunch`, not with `v files`.
- Semantic model download requires external HTTPS access to `huggingface.co` (157 MiB) and 64-bit ABI (32-bit devices show `Semantic search is unavailable on this device.`).
- A real S3 upload needs a reachable endpoint; debug builds allow cleartext only to `10.0.2.2` and `localhost`. Without one, report uploads as unverified.
- Radio dialogs (`Color theme`, `Language`, `Image upload destination`) dismiss with `OK`.
- Below API 31 Dynamic color is disabled with `Not supported on this version of Android`.
- Language changes recreate the UI; restore English for the other recipes.
