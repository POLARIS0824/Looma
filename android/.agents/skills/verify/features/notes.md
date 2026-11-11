# Create, edit and read Markdown notes

Users create or open a note, edit its Markdown in one field, format it, switch to reading, follow wiki links, insert images and return without losing content. Files on disk are the durable source of truth.

## Sub-features

- `note-create-save`: New note creates `Untitled note.md` in the current folder and opens the editor with the title field focused and the default name selected; typed Markdown persists after leaving and relaunch.
- `note-title`: the filename is an editable title. With Show inline title on it sits above the body in Edit and Read; off it moves to a centered, smaller top-bar field. Typing a new name and committing (Done or focus loss) renames the file and rewrites wiki links; a taken name shows a snackbar and reverts. A new note names its file by typing into the focused title, so `Untitled note (1).md` no longer accumulates.
- `note-open`: Recent cards, Notes cards and search results each open the selected note in Edit mode.
<<<<<<< Updated upstream
- `note-read-edit`: the top-bar Read/Edit toggle preserves content. Read shows the inline title (the file name, display only and scrolling with the body), the rendered body and backlinks; tapping a checkbox rewrites `[ ]`/`[x]`.
- `note-rename`: editing the file name in the inline title (or in the top bar when Show inline title is off) in Edit mode renames the file in place and retargets wiki links. Read never edits the name. A snackbar reports backlinks updated or failed.
=======
- `note-read-edit`: the top-bar Read/Edit toggle preserves content. Read shows the editable filename title, the rendered body and backlinks; tapping a checkbox rewrites `[ ]`/`[x]`.
>>>>>>> Stashed changes
- `note-format`: toolbar actions, Undo/Redo and the configured toolbar order change the Markdown.
- `note-links`: Link a note inserts a wiki link; Read resolves it, opens the target, and the target lists `Backlinks · N`; a missing target shows a snackbar.
- `note-images`: Insert image picks a photo, writes `<first 16 hex of SHA-256>.<ext>` at the vault root and inserts `![[…]]` (Local attachment), or uploads to S3 and inserts `![](url)` (Image bed). Read renders `![](…)`, `![[…]]` and sized `![[x.png|300]]`.
- `note-math`: `$…$` and `$$…$$` render as formulas in Read.
- `note-conflict`: a save against bytes changed by another editor writes a conflict copy and shows a banner with Reload from disk and Overwrite; other save failures offer Retry save; leaving while a save fails asks first.

## How to get to it (user POV)

- Home → New note (in the current folder).
- Tap a card in Recent, Notes or folder contents, or a search result.
- A resolved wiki link or a backlink opens its target; Back returns to the prior note.

## Driving it with control.py

Preconditions: connected empty Home, English UI, doctor passes. Create fixtures through New note.

- **Create.** `v tap --desc 'New note'`. With Show inline title on (the default) the editor has two `android.widget.EditText` nodes: the single-line file name `Untitled note`, focused and selected on a new note, and the Markdown body with placeholder `Start writing Markdown…`. `v tap --class android.widget.EditText --index 1`; `v text '# Verification note'`; `v key KEYCODE_ENTER`; `v text 'Persistence marker #verify'`; `v key KEYCODE_ENTER`; `v tap --desc Bold`; `v text formatted`.
- **Read/save.** `v tap --desc Read`. Read shows the inline title `Untitled note` (the file name) above the heading `Verification note`, and the title scrolls with the body; `formatted` renders bold. `v tap --desc Back`; `v files note-saved`. The file is `Untitled note.md` with `# Verification note`, `Persistence marker #verify` and `**formatted**`. The heading never renames the file.
- **Rename.** In Edit, `v tap --class android.widget.EditText --index 0`; `v select-all`; `v text 'Weekly notes'`; `v key KEYCODE_ENTER`. Done commits once, moves focus to the body and renames the file (`v files`). In Read the title is display only, so switch back to Edit to rename. When other notes linked to it, a snackbar reports the backlinks updated or failed. With Show inline title off the same field sits in the top bar, editable in Edit and static text in Read.
- **Persistence/open entries.** `v relaunch`; `v ui`. Home shows each note twice (Recent and Notes), so pass `--index`. The field text is the full saved Markdown. Repeat from the other card and from a search result; record each entry separately.
- **Toolbar.** About ten of the twenty actions fit a 1080 px screen: Undo, Redo, Link a note, Insert image, Bold, Italic, Strikethrough, Highlight, Link, then Inline code, Heading 1–3, Bulleted list, Numbered list, Checklist, Quote, Code block, Table and Divider offscreen. Scroll the row with `v swipe left --desc Bold` (repeat with any visible action). Then `v tap --desc Checklist`; `v text Task`; `v tap --desc Read`. Read exposes an `android.widget.CheckBox`; `v tap --class android.widget.CheckBox` sets `checked=true`, and after Back the file holds `- [x] Task`.
- **Links.** Create a second note `# Target note`. In the first note: `v tap --desc 'Link a note'`; `v assert --text 'Search notes'`; `v tap --text 'Target note'`. The inserted text is `[[Untitled note (1)|Target note]]`: the target's file name with its heading as alias. Type `[[Missing target]]` on another line. **Leave and reopen the note before testing Read**: links typed in this session render as missing until the note is saved and reopened, because resolution uses the indexed links. In Read the whole paragraph is one TextView, so tap a span with `--at`: `v tap --class android.widget.TextView --index 2 --at 0.3,0.87` hits the last line. Expect the snackbar `No note named "Missing target"` (in PowerShell write `'No note named \"Missing target\"'`). The resolved line opens the target in Edit; `v tap --desc Read` there shows `Backlinks · 1` and the referrer's title.
- **Images.** Make a small disposable PNG on the host. `v fixture <file.png> --to pictures` pushes it to `/sdcard/Pictures/<owner>/` and runs the media scanner. In Edit, `v tap --desc 'Insert image'`; `v doctor --system`. The photo picker lists `Photo taken on …` items; their descriptions contain U+202F before AM/PM, so copy the exact `content-desc` from `v ui` JSON instead of typing it. After the pick: Read renders the image; `v files` shows `<16 hex>.png` at the vault root, byte-identical to the fixture, and `![[<16 hex>.png]]` in the note.
- **Math.** `v text 'Energy $E=mc^2$ here'`; Read renders the formula inline. Home card previews show the raw `$…$` source.
- **Conflict.** Open a clean note. Write the "other editor" version on the host under the note's exact file name and push it with `v fixture '<file>' --to vault --replace`. Then type in the app: `v tap --class android.widget.EditText --index 1`; `v key KEYCODE_MOVE_END KEYCODE_CTRL_RIGHT`; `v text ' app edit'`. Within about 3 s, after the 2 s debounce, `v assert --text 'Reload from disk'` and `v assert --text Overwrite`. The banner reads `The note changed on disk. Your changes were saved as “<name> (conflicted copy yyyy-MM-dd HHmmss).md”.` with curly quotes; read it from `v ui`. `v files` shows that copy next to the note and the note holding the external bytes. `v tap --text Overwrite`: the banner closes, the note holds the app content, the conflict copy is gone and the external bytes are backed up under `.looma/conflicts/`.

## Gotchas

- With Show inline title on, Edit has two `EditText` nodes, the file name first and the body second. Read has no `EditText` and shows the name as text. With it off the page keeps only the body and the file name moves to the top bar, editable in Edit and static in Read. Draft recovery is gone.
- Saving: 2 s after the last edit, at most 15 s after the first edit of a burst, and on Back, pause and stop. Poll observable state and inspect disk; sleeps are not proof.
- Detection compares SHA-256 of the bytes, not mtime. An external change made while the app is in the background is taken silently on return if the note is clean; a conflict needs edits on top of an unseen external change.
- New empty notes are discarded on leave unless Keep empty notes is on.
- `Read` on a note opened from Read mode of another note: Back returns to that note, a second Back to Home.
- `Insert file` never appears in the toolbar. ADB ASCII input does not verify IME composition.
