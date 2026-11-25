# Delete and restore notes

Deleted notes leave normal browsing and appear in Trash with their original location. Users can restore them, undo a restore, delete permanently, restore all or empty the trash.

## Sub-features

- `trash-delete-undo`: Home selection Delete (no confirmation) moves notes to `.looma/trash`; snackbar Undo restores them. Folder delete trashes the folder's notes after confirming.
- `trash-open-empty`: the drawer opens Trash; an empty trash shows `Trash is empty` and `Deleted notes stay here until you restore them`.
- `trash-restore`: Restore returns a note to its original path, recreating missing folders; its Undo trashes it again.
- `trash-permanent`: Delete permanently asks first and removes the note and its metadata.
- `trash-bulk`: Restore all runs at once; Empty trash asks first.

## How to get to it (user POV)

- Home: long-press note(s) → Delete; snackbar `N moved to trash` with Undo.
- Home root → Open navigation → Trash.
- Each row shows the title, an excerpt (when non-blank), `From <original path>`, `Deleted <date and time>`, and the actions Restore and Delete permanently. The top bar offers Restore all and Empty trash while the list is not empty.

## Driving it with control.py

Preconditions: connected Home root, doctor passes, at least one note created through New note.

- **Move to trash.** `v tap --text '<title>' --index 1 --long`; `v tap --desc Delete`; `v assert --text '1 moved to trash'`; `v tap --text Undo`; `v assert --text Undone` (tap Undo before slow `v files` pulls). Delete the note(s) again so Trash is populated, then `v files trash-items`: originals are gone and `.looma/trash/<uuid>.md` plus `<uuid>.properties` hold `originalPath=<path>` and `deletedAt=<epoch ms>`.
- **Row.** `v tap --desc 'Open navigation'`; `v tap --text Trash`; `v assert --text '<title>'`; `v assert --text 'From <original path>'`.
- **Restore and undo.** `v tap --desc Restore` (`--index 0` if multiple rows); `v assert --text 'Note restored'`; `v tap --text Undo`; `v assert --text 'Note moved back to the trash'`. The undo creates a **new** UUID pair; verify via `v files trash-after-restore-undo`.
- **Permanent delete.** `v tap --desc 'Delete permanently'`; `v assert --text 'Delete permanently?'`; `v assert --text 'This note cannot be restored after that.'`; `v tap --text Cancel` keeps the row. Again, then `v tap --text Delete` → `Note deleted`. With several rows, pass `--index`.
- **Bulk.** `v tap --desc 'Restore all'` (no dialog) → exact `<plurals>` copy `Restored 1 note` / `Restored N notes`; partial failures (or all items conflicting) read `Restored X, Y failed` (outer failure reads `Could not restore that note`). `v tap --desc 'Empty trash'`; `v assert --text 'Empty the trash?'`; the body uses `<plurals>`: `1 note will be deleted permanently.` / `N notes will be deleted permanently.`; `v tap --text Empty` → `Deleted 1 note permanently` / `Deleted N notes permanently` and `Trash is empty` (partial failure reads `Deleted X permanently, Y failed`; total failure reads `That action failed`). Prove each with `v files <name>`.

## Gotchas

- `Open navigation` exists only at Home root (inside folders `Go up one folder` replaces it and drawer gestures are disabled).
- The trash is `.looma/trash`, not Obsidian's `.trash`. Records whose `.properties` lacks a matching `.md`, lacks valid `originalPath`/`deletedAt`, or whose name is not a UUID, are hidden.
- Restoring onto an existing file (`RESTORE_CONFLICT`) or an I/O / invalid-path failure fails with `Could not restore that note` and overwrites nothing; Home batch Undo then shows `Undid X · Y could not be undone` (partial) or `Could not undo that`. Restoring a trashed file exceeding 2 MiB still succeeds on disk (only indexing is skipped).
- Permanent deletion is irreversible in the guest; use only the run's disposable vault and keep the pulled before/after copies.
- Snackbar Undo expires; a missed Undo is an unexercised branch, not a failure.
