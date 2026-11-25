# Ask this vault

Users ask natural-language questions over the connected vault, inspect retrieved keyword or semantic evidence, open cited source notes, and optionally run a local `.litertlm` instruction model to generate cited claims and save the answer as a note.

## Sub-features

- `ask-entry`: `Open navigation` → `Ask this vault` opens `AskScreen` (`LoomaRoute.Ask`).
- `ask-evidence`: entering a question and tapping `Find an answer` retrieves matching passages (`Using keyword evidence` or semantic evidence) and lists clickable `[N] <path>` source buttons. Without a `.litertlm` model, the screen shows `Import a local instruction model to generate an answer from these passages.`; unmatched queries show `No matching evidence found.`.
- `ask-model-agent`: `Let the agent search further` is disabled until `answer-model.litertlm` is imported via `Import local generation model (.litertlm)`. With a model installed, generation shows a wavy progress indicator + `Cancel`, outputs cited claims, and offers `Save as note` → `Open saved note`.

## How to get to it (user POV)

- Connected Home root → `Open navigation` → `Ask this vault`.

## Driving it with control.py

Preconditions: connected Home root with at least one note, English UI, doctor passes.

- **Evidence lookup (no model).** `v tap --desc 'Open navigation'`; `v tap --text 'Ask this vault'`. `v assert --text 'Question about your notes'`; `v assert --text 'Find an answer'`. `v tap --class android.widget.EditText`; `v text 'Persistence'`; `v tap --text 'Find an answer'`. Expect `Import a local instruction model to generate an answer from these passages.`, `Using keyword evidence`, and `[1] <path>`. Tapping `[1] <path>` opens that note; `v tap --desc Back` returns to Home.
- **Model import & generation.** Tapping `Import local generation model (.litertlm)` opens the system file picker for a `.litertlm` model. Generation, `Let the agent search further`, and `Save as note` are prerequisite-gated on an imported `.litertlm` model.

## Gotchas

- `Let the agent search further` has `enabled=false` when no `.litertlm` model is installed.
- `AskScreen` does not persist across vault changes (`clazzContentKey = "ask/<vaultId>"`).
