package com.example.looma.data.index

import com.example.looma.domain.model.NotePath

internal data class ScanPlan(
    val toRead: List<NotePath>,
    val kept: List<IndexedNote>,
    val removed: List<NotePath>,
)

internal fun planScan(
    indexed: List<IndexedNote>,
    onDisk: Map<NotePath, FileFingerprint>,
    reindex: Set<NotePath> = emptySet(),
): ScanPlan {
    val indexedByPath = indexed.associateBy { it.id.path }
    val toRead = onDisk.entries
        .filter { (path, fingerprint) -> path in reindex || indexedByPath[path]?.fingerprint != fingerprint }
        .map { it.key }
    val kept = indexed.filter { note -> note.id.path !in reindex && onDisk[note.id.path] == note.fingerprint }
    val removed = indexed
        .filter { it.id.path !in onDisk }
        .map { it.id.path }
    return ScanPlan(toRead = toRead, kept = kept, removed = removed)
}
