package com.example.looma.domain.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPlannerEdgeCaseTest {

    private val md5A = "0123456789abcdef0123456789abcdef"
    private val md5B = "fedcba9876543210fedcba9876543210"
    private val md5C = "11112222333344445555666677778888"
    private val multipartEtag = "0123456789abcdef0123456789abcdef-2"

    @Test
    fun `3x3 existence matrix covers all combinations for initial and incremental sync`() {
        val path = SyncRelativePath("note.md")
        val loc = local(path, sizeBytes = 50, modifiedAtMillis = 1_000L, sha256 = "sha-1", md5 = md5A)
        val rem = remote(path, etag = md5A, sizeBytes = 50)
        val remDifferent = remote(path, etag = md5C, sizeBytes = 70)
        val base = baseline(path, localSha256 = "sha-1", localSizeBytes = 50, localModifiedAtMillis = 1_000L, remoteEtag = md5A, remoteSizeBytes = 50)

        for (initial in listOf(false, true)) {
            val clearPlan = SyncPlanner.plan(initial, emptyMap(), emptyMap(), mapOf(path to base))
            assertEquals(listOf(SyncPlanItem.ClearBaseline(path)), clearPlan.items)

            val uploadNewPlan = SyncPlanner.plan(initial, mapOf(path to loc), emptyMap(), emptyMap())
            assertEquals(listOf(SyncPlanItem.UploadNew(path, 50)), uploadNewPlan.items)

            val downloadNewPlan = SyncPlanner.plan(initial, emptyMap(), mapOf(path to rem), emptyMap())
            assertEquals(listOf(SyncPlanItem.DownloadNew(path, rem)), downloadNewPlan.items)

            val adoptNoBasePlan = SyncPlanner.plan(initial, mapOf(path to loc), mapOf(path to rem), emptyMap())
            assertEquals(listOf(SyncPlanItem.AdoptMatched(path, loc, rem)), adoptNoBasePlan.items)

            val conflictNoBasePlan = SyncPlanner.plan(initial, mapOf(path to loc), mapOf(path to remDifferent), emptyMap())
            assertEquals(listOf(SyncPlanItem.ResolveConflict(path, loc, remDifferent, baseline = null)), conflictNoBasePlan.items)

            val unchangedBothPlan = SyncPlanner.plan(initial, mapOf(path to loc), mapOf(path to rem), mapOf(path to base))
            assertTrue(unchangedBothPlan.items.isEmpty())
            assertEquals(1, unchangedBothPlan.unchangedCount)
        }

        val locChanged = loc.copy(sizeBytes = 60, modifiedAtMillis = 2_000L, sha256 = "sha-2", md5 = md5B)
        assertEquals(
            listOf(SyncPlanItem.DeleteLocal(path, "sha-1")),
            SyncPlanner.plan(false, mapOf(path to loc), emptyMap(), mapOf(path to base)).items,
        )
        assertEquals(
            listOf(SyncPlanItem.UploadNew(path, 60)),
            SyncPlanner.plan(false, mapOf(path to locChanged), emptyMap(), mapOf(path to base)).items,
        )
        assertEquals(
            listOf(SyncPlanItem.UploadNew(path, 50)),
            SyncPlanner.plan(true, mapOf(path to loc), emptyMap(), mapOf(path to base)).items,
        )

        assertEquals(
            listOf(SyncPlanItem.DeleteRemote(path, rem.objectKey, md5A)),
            SyncPlanner.plan(false, emptyMap(), mapOf(path to rem), mapOf(path to base)).items,
        )
        assertEquals(
            listOf(SyncPlanItem.DownloadNew(path, remDifferent)),
            SyncPlanner.plan(false, emptyMap(), mapOf(path to remDifferent), mapOf(path to base)).items,
        )
        assertEquals(
            listOf(SyncPlanItem.DownloadNew(path, rem)),
            SyncPlanner.plan(true, emptyMap(), mapOf(path to rem), mapOf(path to base)).items,
        )

        assertEquals(
            listOf(SyncPlanItem.UploadUpdate(path, 60, md5A)),
            SyncPlanner.plan(false, mapOf(path to locChanged), mapOf(path to rem), mapOf(path to base)).items,
        )
        assertEquals(
            listOf(SyncPlanItem.DownloadUpdate(path, remDifferent, "sha-1")),
            SyncPlanner.plan(false, mapOf(path to loc), mapOf(path to remDifferent), mapOf(path to base)).items,
        )
        assertEquals(
            listOf(SyncPlanItem.ResolveConflict(path, locChanged, remDifferent, base)),
            SyncPlanner.plan(false, mapOf(path to locChanged), mapOf(path to remDifferent), mapOf(path to base)).items,
        )
    }

    @Test
    fun `racily clean 2000ms boundary triggers hash and change inside window but not at boundary`() {
        val path = SyncRelativePath("racy.md")
        val modifiedAt = 10_000L
        val insideSyncedAt = modifiedAt + SyncPlanner.RACILY_CLEAN_WINDOW_MILLIS - 1L
        val boundarySyncedAt = modifiedAt + SyncPlanner.RACILY_CLEAN_WINDOW_MILLIS

        val loc = local(path, sizeBytes = 50, modifiedAtMillis = modifiedAt, sha256 = null, md5 = null)
        val rem = remote(path, etag = md5A, sizeBytes = 50)
        val insideBase = baseline(path, localSizeBytes = 50, localModifiedAtMillis = modifiedAt, remoteEtag = md5A, remoteSizeBytes = 50, syncedAtMillis = insideSyncedAt)
        val boundaryBase = baseline(path, localSizeBytes = 50, localModifiedAtMillis = modifiedAt, remoteEtag = md5A, remoteSizeBytes = 50, syncedAtMillis = boundarySyncedAt)

        assertTrue(SyncPlanner.needsLocalHash(loc, rem, insideBase))
        assertFalse(SyncPlanner.needsLocalHash(loc, rem, boundaryBase))

        val insidePlan = SyncPlanner.plan(false, mapOf(path to loc), mapOf(path to rem), mapOf(path to insideBase))
        assertEquals(listOf(SyncPlanItem.UploadUpdate(path, 50, md5A)), insidePlan.items)

        val boundaryPlan = SyncPlanner.plan(false, mapOf(path to loc), mapOf(path to rem), mapOf(path to boundaryBase))
        assertTrue(boundaryPlan.items.isEmpty())
        assertEquals(1, boundaryPlan.unchangedCount)
    }

    @Test
    fun `needsLocalHash checks ETag format, size, and baseline state`() {
        val path = SyncRelativePath("file.bin")
        val loc = local(path, sizeBytes = 100, modifiedAtMillis = 1_000L)
        val plainRemoteSameSize = remote(path, etag = md5A, sizeBytes = 100)
        val plainRemoteDiffSize = remote(path, etag = md5A, sizeBytes = 101)
        val multipartRemoteSameSize = remote(path, etag = multipartEtag, sizeBytes = 100)

        assertFalse(SyncPlanner.needsLocalHash(loc, null, null))
        assertTrue(SyncPlanner.needsLocalHash(loc, plainRemoteSameSize, null))
        assertFalse(SyncPlanner.needsLocalHash(loc, plainRemoteDiffSize, null))
        assertFalse(SyncPlanner.needsLocalHash(loc, multipartRemoteSameSize, null))

        val base = baseline(path, localSizeBytes = 100, localModifiedAtMillis = 1_000L, remoteEtag = md5B, remoteSizeBytes = 100, syncedAtMillis = 5_000L)
        assertFalse(SyncPlanner.needsLocalHash(loc, null, base))
        assertFalse(SyncPlanner.needsLocalHash(loc, remote(path, etag = md5B, sizeBytes = 100), base))
        assertTrue(SyncPlanner.needsLocalHash(loc, plainRemoteSameSize, base))
        assertFalse(SyncPlanner.needsLocalHash(loc, multipartRemoteSameSize, base))
        assertFalse(SyncPlanner.needsLocalHash(loc, plainRemoteDiffSize, base))

        assertTrue(SyncPlanner.needsLocalHash(loc.copy(sizeBytes = 99), null, base))
        assertTrue(SyncPlanner.needsLocalHash(loc.copy(modifiedAtMillis = 1_001L), null, base))
    }

    @Test
    fun `AdoptMatched triggers when local and remote converge to identical MD5 and size`() {
        val path = SyncRelativePath("converged.md")
        val upperMd5 = md5B.uppercase()
        val locChanged = local(path, sizeBytes = 60, modifiedAtMillis = 2_000L, sha256 = "sha-2", md5 = upperMd5)
        val locUnchanged = local(path, sizeBytes = 50, modifiedAtMillis = 1_000L, sha256 = "sha-1", md5 = md5A)
        val remChanged = remote(path, etag = md5B, sizeBytes = 60)
        val remUnchanged = remote(path, etag = md5A, sizeBytes = 50)
        val base = baseline(path, localSha256 = "sha-1", localSizeBytes = 50, localModifiedAtMillis = 1_000L, remoteEtag = md5A, remoteSizeBytes = 50)

        val localChangedConverged = SyncPlanner.plan(
            false,
            mapOf(path to locChanged.copy(md5 = md5A.uppercase())),
            mapOf(path to remUnchanged.copy(sizeBytes = 60)),
            mapOf(path to base.copy(remoteSizeBytes = 60)),
        )
        assertEquals(
            listOf(SyncPlanItem.AdoptMatched(path, locChanged.copy(md5 = md5A.uppercase()), remUnchanged.copy(sizeBytes = 60))),
            localChangedConverged.items,
        )

        val remConvergedToLocal = remote(path, etag = md5A, sizeBytes = 50)
        val remoteChangedConverged = SyncPlanner.plan(
            false,
            mapOf(path to locUnchanged),
            mapOf(path to remConvergedToLocal),
            mapOf(path to base.copy(remoteEtag = md5B)),
        )
        assertEquals(
            listOf(SyncPlanItem.AdoptMatched(path, locUnchanged, remConvergedToLocal)),
            remoteChangedConverged.items,
        )

        val bothChangedConverged = SyncPlanner.plan(
            false,
            mapOf(path to locChanged),
            mapOf(path to remChanged),
            mapOf(path to base),
        )
        assertEquals(
            listOf(SyncPlanItem.AdoptMatched(path, locChanged, remChanged)),
            bothChangedConverged.items,
        )

        val multipartNotAdopted = SyncPlanner.plan(
            false,
            mapOf(path to locChanged.copy(md5 = multipartEtag)),
            mapOf(path to remChanged.copy(etag = multipartEtag)),
            mapOf(path to base),
        )
        assertTrue(multipartNotAdopted.items.single() is SyncPlanItem.ResolveConflict)
    }

    @Test
    fun `RefreshBaselineStats emitted when mtime changed but sha256 matches baseline`() {
        val path = SyncRelativePath("touched.md")
        val loc = local(path, sizeBytes = 50, modifiedAtMillis = 2_500L, sha256 = "SHA-1", md5 = md5A)
        val rem = remote(path, etag = md5A, sizeBytes = 50)
        val base = baseline(path, localSha256 = "sha-1", localSizeBytes = 50, localModifiedAtMillis = 1_000L, remoteEtag = md5A, remoteSizeBytes = 50)

        val plan = SyncPlanner.plan(false, mapOf(path to loc), mapOf(path to rem), mapOf(path to base))

        assertEquals(listOf(SyncPlanItem.RefreshBaselineStats(path, loc, base)), plan.items)
        assertEquals(0, plan.unchangedCount)
    }

    @Test
    fun `case collision skips both paths without generating deletes`() {
        val upper = SyncRelativePath("Note.md")
        val lower = SyncRelativePath("note.md")
        val base = baseline(lower)

        val plan = SyncPlanner.plan(
            isInitialSync = false,
            localFiles = mapOf(upper to local(upper, sizeBytes = 50, modifiedAtMillis = 1_000L)),
            remoteObjects = mapOf(lower to remote(lower, etag = md5A, sizeBytes = 50)),
            baselines = mapOf(lower to base),
        )

        assertTrue(plan.items.isEmpty())
        assertEquals(
            listOf(
                SyncSkippedItem("Note.md", SyncSkipReason.CaseCollision),
                SyncSkippedItem("note.md", SyncSkipReason.CaseCollision),
            ),
            plan.skipped,
        )
    }

    @Test
    fun `extraSkipped FileTooLarge and InvalidPath prevent DeleteLocal and DeleteRemote`() {
        val tooLarge = SyncRelativePath("video.mp4")
        val invalidPath = SyncRelativePath("bad.md")

        val plan = SyncPlanner.plan(
            isInitialSync = false,
            localFiles = mapOf(tooLarge to local(tooLarge, 100, 1_000L)),
            remoteObjects = mapOf(invalidPath to remote(invalidPath, md5A, 100)),
            baselines = mapOf(
                tooLarge to baseline(tooLarge, localSizeBytes = 100, localModifiedAtMillis = 1_000L),
                invalidPath to baseline(invalidPath, remoteEtag = md5A, remoteSizeBytes = 100),
            ),
            extraSkipped = listOf(
                SyncSkippedItem(tooLarge.value, SyncSkipReason.FileTooLarge),
                SyncSkippedItem(invalidPath.value, SyncSkipReason.InvalidPath),
            ),
        )

        assertTrue(plan.items.isEmpty())
        assertEquals(
            listOf(
                SyncSkippedItem("video.mp4", SyncSkipReason.FileTooLarge),
                SyncSkippedItem("bad.md", SyncSkipReason.InvalidPath),
            ),
            plan.skipped,
        )
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
        localSha256: String = "sha-1",
        localSizeBytes: Long = 50,
        localModifiedAtMillis: Long = 1_000L,
        remoteEtag: String = md5A,
        remoteSizeBytes: Long = 50,
        syncedAtMillis: Long = 5_000L,
    ) = SyncBaseline(
        path = path,
        localSha256 = localSha256,
        localSizeBytes = localSizeBytes,
        localModifiedAtMillis = localModifiedAtMillis,
        remoteEtag = remoteEtag,
        remoteSizeBytes = remoteSizeBytes,
        remoteModifiedAtMillis = 1_000L,
        syncedAtMillis = syncedAtMillis,
    )
}
