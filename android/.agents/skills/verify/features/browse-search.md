# Browse files and search notes

Users browse folders and recent notes, change list/grid and sorting, search titles, text and tags, and manage notes or folders through selection actions with Undo.

## Sub-features

- `browse-layout-sort`: Show as list/Show as grid, the `Sort by` sheet (`Last modified`, `Title A–Z`, `Title Z–A`) and pull-to-refresh keep the visible note set.
- `browse-folders`: create, open, go up, rename, move and delete a folder.
- `browse-selection`: long-press selects notes or one folder (never both); batch Move to…/Delete; single-note Rename and File info; snackbar Undo.
- `browse-relink`: renaming or moving a note or folder rewrites wiki links in other notes; the rename snackbar reports how many.
- `search-match`: title, body and tag queries list matching notes; a result opens the note.
- `search-empty-clear`: blank query hint, `No matching notes`, Clear search and Close search.
- `search-history`: submitted or opened queries appear under `Recent searches`.
- `browse-stalled`: an unfinished rename/move journal shows a banner with Abandon. Source-only: no user path creates one.

## How to get to it (user POV)

- Connected Home shows `Recent` (root only, up to 10 newest notes), `Folders` (only when folders exist) and `Notes`. Inside a folder there are no headers; a breadcrumb `Home / <folder>` with `Go up one folder` replaces them.
- The search field is an EditText with description `Search`; its placeholder `Search <vault>` has no semantics.
- Show as list, Show as grid and Sort sit in the collapsed search bar.
- Long-press a note or folder. `New folder` and `New note` are floating actions, hidden during selection.

## Driving it with control.py

Preconditions: doctor passes on connected Home. Create two notes via [notes.md](notes.md); for relinking, one must link to the other.

- **Layout/sort.** `v tap --desc 'Show as list'`; `v assert --desc 'Show as grid'`; `v tap --desc 'Show as grid'`. `v tap --desc Sort`; `v assert --text 'Sort by'`; `v tap --text 'Title A–Z'` (U+2013 dash; plain `Title` matches nothing). Repeat `Title Z–A` and `Last modified`. Check the Notes order, not Recent, which is always newest first. Folders always sort by name.
- **Folder.** `v tap --desc 'New folder'`; `v assert --text 'Folder name'`; the dialog's field is then the only EditText: `v tap --class android.widget.EditText`; `v text Projects`; `v tap --text Create`; `v assert --text 'Folder created'` (its Undo deletes the folder). `v tap --text Projects`; `v assert --text 'This folder is empty'`; `v assert --text 'Home / Projects'`; `v tap --desc 'Go up one folder'`.
- **File info.** `v tap --text '<title>' --index 1 --long` (index 1 is the Notes card; 0 is Recent). `v tap --desc 'File info'`: rows `Location`, `Size`, `Modified`, `Words`, `Characters` and, with checkboxes, `Checklist` / `1 of 1 done`. Location uses `›` between folders. Close with `v tap --text OK`.
- **Rename and relink.** Long-press the linked target; `v tap --desc Rename`; `v assert --text 'File name'`; `v tap --class android.widget.EditText`; `v select-all`; `v text Renamed`; `v tap --text Save`. Expect `Renamed, 1 backlink(s) updated` (plain `Renamed` when no link changed). `v files` shows `Renamed.md` and the referrer now holding `[[Renamed|…]]`. `v tap --text Undo` right away; `v assert --text Undone`; the file name and the link return.
- **Move.** Long-press a note; `v tap --desc 'Move to…'`; `v doctor --system`. DocumentsUI opens at the vault folder: `v tap --text Projects`; `v tap --text 'USE THIS FOLDER'`; `v tap --text ALLOW`. Expect `1 moved` + Undo, and the file under `Projects/`. A link that uses the bare file name keeps resolving and is not rewritten. Undo expires after about 10 s; a `v files` pull before tapping it can take longer.
- **Batch.** Long-press note A, then tap note B: the title is `2`, `File info` disappears and `Rename` is disabled. `v tap --desc Delete` has no confirmation: `2 moved to trash` + Undo → `Undone`.
- **Folder delete.** `v tap --text Projects --long`; `v tap --desc Delete`; `v assert --text 'Delete “Projects”?'`; the message is `1 note in this folder will move to the trash.` or `This folder is empty and will be removed.` `v tap --text Delete` → `Folder deleted · 1 note moved to trash` + Undo. The note lands in `.looma/trash/` with `originalPath=Projects/<name>.md`.
- **Search.** `v tap --class android.widget.EditText`; with no history expect `Search titles, text and tags`. `v text Verification` → result `Verification note`. `v tap --desc 'Clear search'`; `v text Persistence` (body); clear; `v text '#verify'` (tag). A result row shows the note's stored excerpt, not a snippet around the match. Tapping it opens the note and records the query. Reopen the field: `Recent searches` lists `#verify`. `v text nonexistent-verify-token` → `No matching notes`. `v tap --desc 'Clear search'`; `v tap --desc 'Close search'` → `v assert --desc 'Show as list'`.

## Gotchas

- Titles repeat across Recent, Notes and the selection bar. Resolve `--index` from `v ui`.
- Do not press Enter while inspecting results: submit records the query, collapses search and clears it. Closing search also clears it.
- Dialog actions are text (`Create`, `Save`, `Delete`, `OK`); bar controls are descriptions.
- Snackbars with Undo use the long duration. Assert the message before slow steps.
- A stalled relocation needs a broken `.looma/operations/op-<uuid>` journal. No harness command or user path creates one; report it as source-only.
