# Fingerprint cache for the render farm

A render worker recomputes a document fingerprint at the end of a build. Two identical manifests can reuse scenery even if the job timestamps differ. If the job was reported twice, the later trace annotation is attached to the existing build record.

Manifest equality is used solely to reuse generated previews for unchanged inputs.

The preview warmer can run without an established draft or history of edits.
