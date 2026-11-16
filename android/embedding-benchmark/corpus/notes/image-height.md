# Late illustrations and chapter restore

The page restores a reader's last position after tablet rotation. Remote illustrations resolve separately from the saved chapter selection, so row heights may change after initial restoration. The internal page anchor pairs a paragraph key with an offset inside that paragraph.

Restore the paragraph key and the displacement within it after a rotation; a raw pixel offset drifts when illustrations load.

Quick jump buttons may still request an absolute offset during the current layout pass.
