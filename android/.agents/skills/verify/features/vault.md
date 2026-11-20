# Connect a local vault

Users choose a folder for their Markdown files, grant all-files access, confirm the pending selection, and return to the same vault on later launches. Change vault is in Settings.

## Sub-features

- `vault-first-launch`: a fresh install shows `Choose a Looma vault` with only `Choose folder`; there is no Connect button yet.
- `vault-permission`: Choose folder opens Android's all-files access page when access is missing.
- `vault-picker`: a primary-storage subfolder can be selected in DocumentsUI.
- `vault-confirm`: the choice shows as `Pending` / `Waiting for confirmation` with `Reselect` and `Connect`; Connect opens Home.
- `vault-persist`: relaunch keeps the vault; each canonical vault path keeps a stable `VaultId` in DataStore (`vault_id_for:<path>`) across switches, preserving vault-scoped Room index and search history.
- `vault-change`: Settings → Change vault shows `Current folder` / `Connected` with `Change` and `Connect`; Back cancels (tapping `Connect` on `Current folder` re-validates and returns to Home). After picking a new folder, the `Pending` card also shows `Current folder: <previous>`.
- `vault-unavailable`: if the stored folder fails validation (access revoked, folder gone) on launch, `ON_START`, or Settings `ON_RESUME`, the back stack resets to setup with `The vault folder is not available`.

## How to get to it (user POV)

- Launch a fresh install.
- Home avatar or Open navigation → Settings → Change vault.

## Driving it with control.py

Preconditions: a fresh healthy session; its empty folder `Documents/<session.json owner>` exists. English UI, API 30+.

- **Gate.** `v assert --text 'Choose a Looma vault'`; `v assert --text Connect --absent`; `v capture vault-gate`.
- **Permission.** `v tap --text 'Choose folder'`; `v doctor --system`. On API 36 tap `v tap --text 'Allow access to manage all files'`, then `v key KEYCODE_BACK`. With access granted, the folder picker opens; without it the setup screen shows `Grant all files access to use a vault`.
- **Picker.** `v doctor --system`. DocumentsUI may remember an old folder (on `looma36` it opens `Looma/notes`); `v key KEYCODE_BACK` twice reaches primary storage. Then `v tap --text Documents`; `v tap --text <owner>`; `v tap --text 'USE THIS FOLDER'`; `v tap --text ALLOW`. Never select a remembered personal folder.
- **Pending and connect.** `v doctor`; `v assert --text Pending`; `v assert --text 'Waiting for confirmation'`; `v assert --text Reselect`; `v tap --text Connect`; `v assert --desc 'Create new'`; `v assert --text 'No notes yet. Tap + to create one'`; `v files vault-empty`.
- **Relaunch.** `v relaunch`; `v assert --desc 'Create new'`; `v assert --text 'Choose a Looma vault' --absent`.
- **Change entry.** `v tap --desc Settings`; `v assert --text <owner>`; `v tap --text 'Change vault'`; `v assert --text 'Current folder'`; `v assert --text Connected`; `v assert --text 'Choose folder' --absent`. `v key KEYCODE_BACK` returns to Settings unchanged. To prove switching, tap `Change`, create a second run-owned folder (`<owner>-2`) with the picker's `New folder` action, select it and tap `USE THIS FOLDER` → `ALLOW`; assert `Pending`, `Waiting for confirmation`, and `Current folder: <owner>`, then `Connect`; the drawer header (`Open navigation`) and Settings then show `<owner>-2`.
- **Unavailable route (external prerequisite).** `vault-unavailable` requires deleting the active vault folder or revoking all-files access externally and returning via launch / `ON_START` / Settings `ON_RESUME`; revalidation does not fire spontaneously while staying in the foreground.

## Gotchas

- Only local **primary** shared storage works. The storage root, cloud providers and secondary volumes show `Could not read that folder`.
- `Connecting…` uses an ellipsis character, disables controls and ignores system Back. A stuck Connecting state or `Could not connect the vault. Try again` is a failure.
- A folder picked and then abandoned with Back stays pending in the shared ViewModel; the next Change vault visit shows that `Pending` card.
- The drawer hides the vault-name subtitle if the folder is literally named `Looma`.
- `v files` pulls only the launch-time vault, not a second vault you switch to.
- API 26–29 uses runtime storage permissions (`Grant storage access to use a vault`) instead; that route is not covered on `looma36`.
