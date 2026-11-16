# Continuous transcription and saving cadence

A court clerk can dictate for minutes without pausing. Autosave normally waits for a brief idle period to avoid repeated disk writes. A second timer starts with the first unsaved edit.

After the maximum dirty interval expires, persist the draft even when typing has never become idle.

When the editor loses foreground, flush the still-dirty buffer immediately instead of waiting for the idle timer.
