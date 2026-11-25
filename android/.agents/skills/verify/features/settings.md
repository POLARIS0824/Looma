# Configure Looma

Settings shows the connected vault and file access, controls appearance and language, sets note behavior and the editor toolbar order, manages the local semantic search model and vault index, configures S3/R2 vault sync, and chooses where inserted images go.

## Sub-features

- `settings-entry`: the Home avatar (description `Settings`) and the drawer (`Notes`, `Ask`, `Trash`, `Settings`) each open Settings; Back returns.
- `settings-vault`: `Notes vault` shows the folder name, path and Change vault; `File access` shows `All files access granted`, or `All files access not granted` with Grant access.
- `settings-theme`: Dynamic color (`Use wallpaper colors (Android 12+)`) and Color theme presets `Looma`, `Indigo`, `Teal`, `Rose`, `Amber`. Picking a preset also turns Dynamic color off; while Dynamic color is on, `Color theme` shows `Wallpaper` and `Choosing a theme turns off dynamic color`.
- `settings-language`: `Follow system`, `English` and `简体中文`.
- `settings-notes`: `Editor toolbar order` sheet, `Keep empty notes` (default off) and `Show inline title` (default on).
- `settings-semantic-search`: `Search` → `Semantic search` sub-screen with `Find notes by meaning`, `Step 1 of 2` (`EmbeddingGemma 2 model`, `One-time download · 157 MiB`, `Download model` / `Cancel` / `Retry`, static model details text), `Step 2 of 2` (`Build index` / `Update index` / `Delete model` once model is ready), and `Compute backend` (`CPU` / `NPU` when `npuSupported`).
- `settings-sync`: `Sync` → `Vault sync` sub-screen (`Sync your vault across devices`, `Not configured` / `Ready`, `Configure`, `Test connection`, `Preview first sync` / `Sync now`, `SyncConfigSheet`, `SyncPreviewSheet`, and collapsible `Setup guide` / `Obsidian Remotely Save checklist`). Sync credentials are AES-GCM encrypted in Android KeyStore (`SYNC_KEY_ALIAS`), while synced vault files are transferred without encryption.
- `settings-images`: `Image upload destination` (`Local attachment` or `Image bed`, disabled until S3 is configured) and the `S3 image bed` sheet (credentials AES-GCM encrypted in Android KeyStore under `DEFAULT_KEY_ALIAS`).

## How to get to it (user POV)

- Home avatar, or Home root → Open navigation → Settings.
- Sections in order: Notes vault, Appearance, Language, Notes, Search, Sync, Images, File access, About. Reaching `Images`, `File access`, and `About` on a 1920 px screen requires multiple `v swipe up` gestures from the top.

## Driving it with control.py

Preconditions: connected Home, English UI, API 31+, doctor passes.

- **Entries.** `v tap --desc Settings`; `v assert --text 'Notes vault'`; `v tap --desc Back`. Then `v tap --desc 'Open navigation'`; `v tap --text Settings`. The drawer header also shows the vault name (`Notes`, `Ask`, `Trash`, `Settings`).
- **Theme.** `v assert --text Wallpaper` and `v assert --text 'Choosing a theme turns off dynamic color'` while Dynamic color is on. `v tap --text 'Color theme'`; `v tap --text Teal`; the row now reads `Teal`, `Wallpaper` disappears and the Dynamic color switch is unchecked. `v relaunch`; reopen and assert `Teal`.
- **Language.** Header and row are both `Language`: `v tap --text Language --index 1`; `v tap --text 简体中文`; `v doctor`. Assert `外观`; in Chinese the row is `语言`: `v tap --text 语言 --index 1`; `v tap --text English` (the option text is the same in every locale). Doctor again before continuing.
- **Switches.** `SettingsSwitchRow` places the `Switch` in `trailingContent` (not row-level `toggleable`). `v toggle 'Dynamic color'`, `v toggle 'Keep empty notes'`, and `v toggle 'Show inline title'` each toggle and print the new `checked` value.
- **Keep empty notes.** With it on: `v tap --desc 'Create new'`; `v tap --text 'New note'`; `v tap --desc Back`; `v files <name>` shows the new 0-byte `.md`. With it off the untouched note is deleted on leave.
- **Show inline title.** With it off, the file name moves from the page body to the top bar (`Edit` still has **one** `EditText` node, index `0`, while the body is `LoomaCodeEditor`). It is editable in Edit and static text in Read (`v assert --text 'Untitled note'` still matches the top bar). Restore `Keep empty notes` off and `Show inline title` on.
- **Toolbar order.** `v tap --text 'Editor toolbar order'`. The sheet opens directly expanded; `Move <item> up` / `down` are `CustomAccessibilityAction` entries on the list item (not separate tappable `contentDescription` buttons). `Reset to default`, `Cancel`, and `Save` sit at the bottom.
- **Semantic search.** Scroll to `Search` and `v tap --text 'Semantic search'`; `v assert --text 'Find notes by meaning'`; `v assert --text 'Step 1 of 2'`; `v assert --text 'EmbeddingGemma 2 model'`; `v assert --text 'Download model'`; `v assert --text 'Hide model details' --absent`. On API 33+, the first `v tap --text 'Download model'` raises `Allow Looma to send you notifications?` (`v doctor --system`, buttons `ALLOW` / `DON’T ALLOW` with U+2019). With external HTTPS access to `huggingface.co`, download progresses and enables `Step 2 of 2` (`Build index`, `Delete model`).
- **Vault sync.** Scroll to `Sync` and `v tap --text 'Vault sync'`; `v assert --text 'Sync your vault across devices'`; `v assert --text 'Not configured'`. `v tap --text Configure` opens `SyncConfigSheet`: fill `Endpoint`, `Bucket`, `v key KEYCODE_BACK` to dismiss the IME, `v swipe up`, fill `Access key` and `Secret key`, `v key KEYCODE_BACK`, `v tap --text Save`. Assert `Test connection` and `Preview first sync`; `v tap --text 'Preview first sync'` opens `SyncPreviewSheet` (`First sync preview`, dismiss with `v tap --text Cancel`). Live connection test and `Sync now` require a reachable S3/R2 endpoint.
- **Images.** Scroll to `Images` and `v tap --text 'Image upload destination'`; while S3 is unconfigured, `Image bed` is disabled and shows `Configure the S3 image bed before choosing it`; dismiss with `v tap --text OK`. `v tap --text 'S3 image bed'` opens `ImageBedSheet` (`Endpoint`, `Region`, `Bucket`, `Access key`, `Secret key`, `Path prefix`, `Public URL`, `Path style access`, `Public read ACL`). Dismiss with `v key KEYCODE_BACK` (or scroll to `Cancel`).
- **About.** Scroll to the bottom of Settings: `All files access granted` and `Version 0.2.0` (from `versionName`).

## Gotchas

- Settings live in Preferences DataStore (`looma_settings_preferences`, with S3 image bed and sync keys AES-GCM encrypted in Android KeyStore), app language lives in Android/AppCompat per-app locale storage, and semantic search models/vectors live under `noBackupFilesDir/semantic-search/` — none in the vault. Prove them through the UI after `v relaunch`, not with `v files`.
- Semantic model download requires external HTTPS access to `huggingface.co` (157 MiB) and 64-bit ABI (32-bit devices show `Semantic search is unavailable on this device.`).
- Real S3 uploads and Vault Sync require a reachable endpoint; debug builds allow cleartext only to `10.0.2.2` and `localhost`. Without one, report live transfers as unverified.
- Radio dialogs (`Color theme`, `Language`, `Image upload destination`) dismiss with `OK`.
- Below API 31 Dynamic color is disabled with `Not supported on this version of Android`.
- Language changes recreate the UI; restore English for the other recipes.
