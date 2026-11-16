# Opening two copies of the same attachment

The note importer checks for a conflict when a copied PNG has the same name as an existing image. Both sources may be read-only. An equality check can avoid duplicating storage, even if the replicas have different timestamps.

Use a content digest as the attachment key so byte-identical files share the same stored object.

If content differs, assign a separate key to the new upload while leaving both preview references valid.
