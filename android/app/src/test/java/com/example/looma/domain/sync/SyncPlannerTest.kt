package com.example.looma.domain.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPlannerTest {

    @Test
    fun `remote FileTooLarge skipped does not generate DeleteLocal`() {
        val path = SyncRelativePath("huge-remote.bin")
        val plan = SyncPlanner.plan(
            isInitialSync = false,
            localFiles = mapOf(path to local(path, sizeBytes = 100, modifiedAtMillis = 1_000L)),
            remoteObjects = emptyMap(),
            baselines = mapOf(path to baseline(path, localSizeBytes = 100, localModifiedAtMillis = 1_000L)),
            extraSkipped = listOf(SyncSkippedItem(path.value, SyncSkipReason.FileTooLarge)),
        )

        assertTrue(plan.items.isEmpty())
        assertEquals(listOf(SyncSkippedItem(path.value, SyncSkipReason.FileTooLarge)), plan.skipped)
    }

    @Test
    fun `local FileTooLarge skipped does not generate DeleteRemote`() {
        val path = SyncRelativePath("huge-local.bin")
        val plan = SyncPlanner.plan(
            isInitialSync = false,
            localFiles = emptyMap(),
            remoteObjects = mapOf(path to remote(path, etag = "etag-1", sizeBytes = 100)),
            baselines = mapOf(path to baseline(path, remoteEtag = "etag-1", remoteSizeBytes = 100)),
            extraSkipped = listOf(SyncSkippedItem(path.value, SyncSkipReason.FileTooLarge)),
        )

        assertTrue(plan.items.isEmpty())
        assertEquals(listOf(SyncSkippedItem(path.value, SyncSkipReason.FileTooLarge)), plan.skipped)
    }

    @Test
    fun `normal remote delete generates DeleteLocal`() {
        val path = SyncRelativePath("note.md")
        val plan = SyncPlanner.plan(
            isInitialSync = false,
            localFiles = mapOf(path to local(path, sizeBytes = 50, modifiedAtMillis = 1_000L)),
            remoteObjects = emptyMap(),
            baselines = mapOf(path to baseline(path, localSizeBytes = 50, localModifiedAtMillis = 1_000L)),
        )

        assertEquals(
            listOf(SyncPlanItem.DeleteLocal(path, expectedLocalSha256 = "sha-local")),
            plan.items,
        )
    }

    @Test
    fun `normal local delete generates DeleteRemote`() {
        val path = SyncRelativePath("note.md")
        val plan = SyncPlanner.plan(
            isInitialSync = false,
            localFiles = emptyMap(),
            remoteObjects = mapOf(path to remote(path, etag = "etag-1", sizeBytes = 50)),
            baselines = mapOf(path to baseline(path, remoteEtag = "etag-1", remoteSizeBytes = 50)),
        )

        assertEquals(
            listOf(
                SyncPlanItem.DeleteRemote(
                    path = path,
                    objectKey = "vault/note.md",
                    expectedRemoteEtag = "etag-1",
                ),
            ),
            plan.items,
        )
    }

    @Test
    fun `diverged local and remote changes produce ResolveConflict`() {
        val path = SyncRelativePath("note.md")
        val localSnapshot = local(path, sizeBytes = 60, modifiedAtMillis = 2_000L, sha256 = "sha-new", md5 = "md5-new")
        val remoteSnapshot = remote(path, etag = "0123456789abcdef0123456789abcdef", sizeBytes = 70)
        val base = baseline(path, localSizeBytes = 50, localModifiedAtMillis = 1_000L, remoteEtag = "etag-1", remoteSizeBytes = 50)

        val plan = SyncPlanner.plan(
            isInitialSync = false,
            localFiles = mapOf(path to localSnapshot),
            remoteObjects = mapOf(path to remoteSnapshot),
            baselines = mapOf(path to base),
        )

        assertEquals(
            listOf(SyncPlanItem.ResolveConflict(path, localSnapshot, remoteSnapshot, base)),
            plan.items,
        )
    }

    @Test
    fun `initial sync never deletes local or remote when baseline exists`() {
        val localOnly = SyncRelativePath("local-only.md")
        val remoteOnly = SyncRelativePath("remote-only.md")
        val plan = SyncPlanner.plan(
            isInitialSync = true,
            localFiles = mapOf(localOnly to local(localOnly, sizeBytes = 40, modifiedAtMillis = 1_000L)),
            remoteObjects = mapOf(remoteOnly to remote(remoteOnly, etag = "etag-1", sizeBytes = 50)),
            baselines = mapOf(
                localOnly to baseline(localOnly, localSizeBytes = 40, localModifiedAtMillis = 1_000L),
                remoteOnly to baseline(remoteOnly, remoteEtag = "etag-1", remoteSizeBytes = 50),
            ),
        )

        assertEquals(0, plan.deleteLocalCount)
        assertEquals(0, plan.deleteRemoteCount)
        assertTrue(plan.items.any { it is SyncPlanItem.UploadNew && it.path == localOnly })
        assertTrue(plan.items.any { it is SyncPlanItem.DownloadNew && it.path == remoteOnly })
    }

    private fun local(
        path: SyncRelativePath,
        sizeBytes: Long,
        modifiedAtMillis: Long,
        sha256: String? = null,
        md5: String? = null,
    ) = LocalFileSnapshot(
        path = path,
        sizeBytes = sizeBytes,
        modifiedAtMillis = modifiedAtMillis,
        sha256 = sha256,
        md5 = md5,
    )

    private fun remote(
        path: SyncRelativePath,
        etag: String,
        sizeBytes: Long,
        lastModifiedMillis: Long = 1_000L,
    ) = RemoteObjectSnapshot(
        path = path,
        objectKey = "vault/${path.value}",
        etag = etag,
        sizeBytes = sizeBytes,
        lastModifiedMillis = lastModifiedMillis,
    )

    private fun baseline(
        path: SyncRelativePath,
        localSizeBytes: Long = 50,
        localModifiedAtMillis: Long = 1_000L,
        remoteEtag: String = "etag-1",
        remoteSizeBytes: Long = 50,
        syncedAtMillis: Long = 5_000L,
    ) = SyncBaseline(
        path = path,
        localSha256 = "sha-local",
        localSizeBytes = localSizeBytes,
        localModifiedAtMillis = localModifiedAtMillis,
        remoteEtag = remoteEtag,
        remoteSizeBytes = remoteSizeBytes,
        remoteModifiedAtMillis = 1_000L,
        syncedAtMillis = syncedAtMillis,
    )
}
