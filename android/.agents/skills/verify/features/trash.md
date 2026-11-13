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
- Home → Open navigation → Trash.
- Each row shows the title, an excerpt, `From <original path>`, `Deleted <date>`, and the actions Restore and Delete permanently. The top bar offers Restore all and Empty trash while the list is not empty.

## Driving it with control.py

Preconditions: connected Home, doctor passes, at least one note created through New note.

- **Move to trash.** `v tap --text '<title>' --index 1 --long`; `v tap --desc Delete`; `v assert --text '1 moved to trash'`. `v files` shows the original gone and `.looma/trash/<uuid>.md` plus `<uuid>.properties` holding `originalPath=<path>` and `deletedAt=<epoch ms>`. `v tap --text Undo`; `v assert --text Undone`.
- **Row.** `v tap --desc 'Open navigation'`; `v tap --text Trash`; `v assert --text '<title>'`; `v assert --text 'From <original path>'`.
- **Restore and undo.** `v tap --desc Restore`; `v assert --text 'Note restored'`; `v tap --text Undo`; `v assert --text 'Note moved back to the trash'`. The undo creates a **new** UUID pair; do not expect the old one.
- **Permanent delete.** `v tap --desc 'Delete permanently'`; `v assert --text 'Delete permanently?'`; `v tap --text Cancel` keeps the row. Again, then `v tap --text Delete` → `Note deleted`. With several rows, pass `--index`.
- **Bulk.** `v tap --desc 'Restore all'` (no dialog) → `Restored N note(s)`; partial failures read `Restored X, Y failed`. `v tap --desc 'Empty trash'`; `v assert --text 'Empty the trash?'`; the body is `N note(s) will be deleted permanently.`; `v tap --text Empty` → `Deleted N note(s) permanently` and `Trash is empty`. Prove each with `v files`.

## Gotchas

- The trash is `.looma/trash`, not Obsidian's `.trash`. Records whose `.properties` lacks a matching `.md`, or whose name is not a UUID, are hidden.
- Restoring onto an existing file fails with `Could not restore that note` and overwrites nothing; Home Undo then shows `Could not undo that`.
- Permanent deletion is irreversible in the guest; use only the run's disposable vault and keep the pulled before/after copies.
- Snackbar Undo expires; a missed Undo is an unexercised branch, not a failure.
