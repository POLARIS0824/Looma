# Catalog snapshots and foreground edits

The manual catalog refresh can still be computing when a writer reindexes a just-edited page. We used to publish both because the index could rebuild itself. That caused stale titles to survive until the next scheduled refresh.

The catalog writer compares a revision counter under the commit lock and discards any scan built from a superseded snapshot.

A storage read failure also affected pruning. The census result keeps a separate completion flag; deletion candidates are actionable only after all directories have been enumerated.
