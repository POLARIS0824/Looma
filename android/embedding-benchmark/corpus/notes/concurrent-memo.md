# Externally modified meeting memo

The writer's laptop returns from sleep after a colleague adjusted the decision in the shared Markdown file. An ordinary save may be holding an obsolete baseline even though the file date resembles the one from the last open.

Compare the persisted bytes against the fingerprint captured at open; a mismatch blocks an ordinary save.

The explicit replace action first writes a recoverable copy of the previous disk contents and stops if that copy cannot be created.
