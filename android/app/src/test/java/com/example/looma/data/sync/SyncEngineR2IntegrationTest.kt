package com.example.looma.data.sync

import com.example.looma.data.s3.S3Client
import com.example.looma.data.s3.S3PutCondition
import com.example.looma.data.s3.createLoomaHttpClient
import com.example.looma.data.settings.S3SyncConfig
import com.example.looma.domain.model.NotePath
import com.example.looma.domain.model.StalledRelocation
import com.example.looma.domain.model.Vault
import com.example.looma.domain.model.VaultId
import com.example.looma.domain.sync.RemotePrefix
import com.example.looma.domain.sync.SyncPhase
import com.example.looma.domain.sync.SyncPlanItem
import com.example.looma.domain.sync.SyncRunOutcome
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class SyncEngineR2IntegrationTest {

    private fun loadConfigOrSkip(prefix: String): S3SyncConfig {
        val endpoint = System.getenv("R2_TEST_ENDPOINT")?.trim().orEmpty()
        val bucket = System.getenv("R2_TEST_BUCKET")?.trim().orEmpty()
        val accessKey = System.getenv("R2_TEST_ACCESS_KEY")?.trim().orEmpty()
        val secretKey = System.getenv("R2_TEST_SECRET_KEY")?.trim().orEmpty()
        val region = System.getenv("R2_TEST_REGION")?.trim()?.ifBlank { "auto" } ?: "auto"
        assumeTrue(
            "Skipping R2 integration test: set R2_TEST_ENDPOINT, R2_TEST_BUCKET, R2_TEST_ACCESS_KEY, R2_TEST_SECRET_KEY",
            endpoint.isNotEmpty() && bucket.isNotEmpty() && accessKey.isNotEmpty() && secretKey.isNotEmpty(),
        )
        return S3SyncConfig(
            endpoint = endpoint,
            region = region,
            bucket = bucket,
            accessKey = accessKey,
            secretKey = secretKey,
            remotePrefix = prefix,
            pathStyle = true,
        )
    }

    private fun withR2SyncEnv(block: suspend (R2SyncEnv) -> Unit) = runBlocking {
        val prefix = "looma-test-${System.currentTimeMillis()}-${UUID.randomUUID()}/"
        val config = loadConfigOrSkip(prefix)
        val httpClient = createLoomaHttpClient()
        val s3Client = S3Client(httpClient)
        val tempDirs = mutableListOf<File>()
        try {
            block(R2SyncEnv(config, RemotePrefix(prefix), s3Client, tempDirs))
        } finally {
            runCatching {
                val objects = s3Client.listAllObjects(config.toEndpointConfig(), RemotePrefix(prefix))
                for (obj in objects) {
                    runCatching { s3Client.deleteObject(config.toEndpointConfig(), obj.key) }
                }
            }
            tempDirs.forEach { it.deleteRecursively() }
            httpClient.close()
        }
    }

    @Test
    fun initialSyncAndIdempotencyAndSpecialFilenames() = withR2SyncEnv { env ->
        val deviceA = env.createDevice("vault-a")
        val binaryBytes = ByteArray(1024) { (it * 31 + 7).toByte() }

        deviceA.writeText("note.md", "# Hello\nBody")
        deviceA.writeText("folder/nested/deep.md", "Nested content")
        deviceA.writeBytes("assets/image.bin", binaryBytes)
        deviceA.writeBytes("empty.md", ByteArray(0))
        deviceA.writeText("中文笔记/测试 + 100% #tag.md", "CJK + special chars")

        val preview1 = deviceA.preview()
        assertTrue(preview1.plan.isInitialSync)
        assertEquals(5, preview1.plan.uploadCount)
        assertEquals(0, preview1.plan.downloadCount)

        val summary1 = deviceA.sync()
        assertEquals(SyncRunOutcome.SUCCEEDED, summary1.outcome)
        assertEquals(5, summary1.uploaded)
        assertEquals(0, summary1.downloaded)
        assertEquals(0, summary1.deletedLocal)
        assertEquals(0, summary1.deletedRemote)
        assertEquals(0, summary1.failed)
        assertTrue(deviceA.hasCompletedInitialSync())

        val preview2 = deviceA.preview()
        assertFalse(preview2.plan.isInitialSync)
        assertEquals(0, preview2.plan.items.size)

        val summary2 = deviceA.sync()
        assertEquals(SyncRunOutcome.SUCCEEDED, summary2.outcome)
        assertEquals(0, summary2.uploaded)
        assertEquals(0, summary2.downloaded)
        assertEquals(0, summary2.deletedLocal)
        assertEquals(0, summary2.deletedRemote)
        assertEquals(0, summary2.failed)
    }

    @Test
    fun secondDeviceInitialPullMatchesSha256AndIsIdempotent() = withR2SyncEnv { env ->
        val deviceA = env.createDevice("vault-a")
        val deviceB = env.createDevice("vault-b")
        val binaryBytes = ByteArray(2048) { (it xor 0x5A).toByte() }

        deviceA.writeText("root.md", "Root note")
        deviceA.writeText("sub/child.md", "Child note")
        deviceA.writeBytes("img/pic.png", binaryBytes)
        deviceA.writeBytes("zero.md", ByteArray(0))
        assertEquals(SyncRunOutcome.SUCCEEDED, deviceA.sync().outcome)

        val pullSummary = deviceB.sync()
        assertEquals(SyncRunOutcome.SUCCEEDED, pullSummary.outcome)
        assertEquals(4, pullSummary.downloaded)
        assertEquals(0, pullSummary.uploaded)
        assertEquals(0, pullSummary.failed)
        assertTrue(deviceB.hasCompletedInitialSync())

        for (relPath in listOf("root.md", "sub/child.md", "img/pic.png", "zero.md")) {
            assertEquals(deviceA.sha256(relPath), deviceB.sha256(relPath))
        }
        assertTrue(deviceB.noteAccess.changedNotes.containsAll(listOf(NotePath("root.md"), NotePath("sub/child.md"), NotePath("zero.md"))))

        val secondPull = deviceB.sync()
        assertEquals(SyncRunOutcome.SUCCEEDED, secondPull.outcome)
        assertEquals(0, secondPull.uploaded)
        assertEquals(0, secondPull.downloaded)
        assertEquals(0, secondPull.deletedLocal)
        assertEquals(0, secondPull.deletedRemote)
    }

    @Test
    fun incrementalBidirectionalSync() = withR2SyncEnv { env ->
        val deviceA = env.createDevice("vault-a")
        val deviceB = env.createDevice("vault-b")

        deviceA.writeText("shared.md", "v1")
        assertEquals(SyncRunOutcome.SUCCEEDED, deviceA.sync().outcome)
        assertEquals(SyncRunOutcome.SUCCEEDED, deviceB.sync().outcome)

        deviceA.writeText("shared.md", "v2 from A")
        val syncA = deviceA.sync()
        assertEquals(SyncRunOutcome.SUCCEEDED, syncA.outcome)
        assertEquals(1, syncA.uploaded)

        val syncB1 = deviceB.sync()
        assertEquals(SyncRunOutcome.SUCCEEDED, syncB1.outcome)
        assertEquals(1, syncB1.downloaded)
        assertEquals("v2 from A", deviceB.readText("shared.md"))

        deviceB.writeText("from-b.md", "Created on B")
        val syncB2 = deviceB.sync()
        assertEquals(SyncRunOutcome.SUCCEEDED, syncB2.outcome)
        assertEquals(1, syncB2.uploaded)

        val syncA2 = deviceA.sync()
        assertEquals(SyncRunOutcome.SUCCEEDED, syncA2.outcome)
        assertEquals(1, syncA2.downloaded)
        assertEquals("Created on B", deviceA.readText("from-b.md"))
    }

    @Test
    fun deletePropagationAndInitialSyncNeverDeletes() = withR2SyncEnv { env ->
        val deviceA = env.createDevice("vault-a")
        val deviceB = env.createDevice("vault-b")
        val deviceC = env.createDevice("vault-c")

        deviceA.writeText("keep.md", "keep")
        deviceA.writeText("dir/gone.md", "to delete")
        deviceA.writeBytes("dir/att.bin", byteArrayOf(1, 2, 3))
        assertEquals(SyncRunOutcome.SUCCEEDED, deviceA.sync().outcome)
        assertEquals(SyncRunOutcome.SUCCEEDED, deviceB.sync().outcome)

        deviceC.writeText("local-only.md", "should not be deleted on initial sync")
        val previewC = deviceC.preview()
        assertTrue(previewC.plan.isInitialSync)
        assertEquals(0, previewC.plan.deleteLocalCount)
        assertEquals(0, previewC.plan.deleteRemoteCount)
        val syncC = deviceC.sync()
        assertEquals(SyncRunOutcome.SUCCEEDED, syncC.outcome)
        assertEquals(0, syncC.deletedLocal)
        assertEquals(0, syncC.deletedRemote)
        assertTrue(deviceC.file("local-only.md").exists())
        assertTrue(deviceC.file("dir/gone.md").exists())

        deviceA.delete("dir/gone.md")
        deviceA.delete("dir/att.bin")
        val deleteSyncA = deviceA.sync()
        assertEquals(SyncRunOutcome.SUCCEEDED, deleteSyncA.outcome)
        assertEquals(2, deleteSyncA.deletedRemote)

        val deleteSyncB = deviceB.sync()
        assertEquals(SyncRunOutcome.SUCCEEDED, deleteSyncB.outcome)
        assertEquals(2, deleteSyncB.deletedLocal)
        assertFalse(deviceB.file("dir/gone.md").exists())
        assertFalse(deviceB.file("dir/att.bin").exists())
        assertFalse(deviceB.file("dir").exists())
        val trashDir = File(deviceB.root, ".looma/trash")
        assertTrue(trashDir.exists())
        assertTrue(trashDir.walkTopDown().any { it.isFile && it.readText() == "to delete" })
    }

    @Test
    fun conflictResolutionPreservesBothVersionsAndConverges() = withR2SyncEnv { env ->
        val deviceA = env.createDevice("vault-a")
        val deviceB = env.createDevice("vault-b")

        deviceA.writeText("conflict.md", "base")
        assertEquals(SyncRunOutcome.SUCCEEDED, deviceA.sync().outcome)
        assertEquals(SyncRunOutcome.SUCCEEDED, deviceB.sync().outcome)

        deviceA.writeText("conflict.md", "edit from A")
        deviceB.writeText("conflict.md", "edit from B")

        assertEquals(SyncRunOutcome.SUCCEEDED, deviceA.sync().outcome)

        val conflictSyncB = deviceB.sync()
        assertEquals(SyncRunOutcome.SUCCEEDED_WITH_CONFLICTS, conflictSyncB.outcome)
        assertEquals(1, conflictSyncB.conflicts)
        assertEquals("edit from A", deviceB.readText("conflict.md"))

        val conflictFiles = deviceB.root.listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith("conflict (conflicted copy ") && it.name.endsWith(".md") }
        assertEquals(1, conflictFiles.size)
        val conflictFile = conflictFiles.single()
        assertEquals("edit from B", conflictFile.readText())

        val syncA2 = deviceA.sync()
        assertEquals(SyncRunOutcome.SUCCEEDED, syncA2.outcome)
        assertEquals(1, syncA2.downloaded)
        assertEquals("edit from B", deviceA.readText(conflictFile.name))

        val idempotentA = deviceA.sync()
        val idempotentB = deviceB.sync()
        assertEquals(0, idempotentA.uploaded + idempotentA.downloaded + idempotentA.conflicts)
        assertEquals(0, idempotentB.uploaded + idempotentB.downloaded + idempotentB.conflicts)
    }

    @Test
    fun preconditionRaceRecordsFailureWithoutOverwritingRemote() = withR2SyncEnv { env ->
        val deviceA = env.createDevice("vault-a")
        deviceA.writeText("race.md", "v1")
        assertEquals(SyncRunOutcome.SUCCEEDED, deviceA.sync().outcome)

        deviceA.writeText("race.md", "v2 from A")
        var injectedExternalWrite = false
        val summary = deviceA.sync(
            onProgress = { phase, _, _ ->
                if (phase == SyncPhase.SYNCING && !injectedExternalWrite) {
                    injectedExternalWrite = true
                    env.s3Client.uploadBytes(
                        config = env.config.toEndpointConfig(),
                        objectKey = env.prefix.value + "race.md",
                        bytes = "external-winner".toByteArray(),
                        contentType = "text/markdown; charset=utf-8",
                        condition = S3PutCondition.Unconditional,
                    )
                }
            },
        )

        assertTrue(injectedExternalWrite)
        assertEquals(SyncRunOutcome.FAILED, summary.outcome)
        assertEquals(1, summary.failed)
        assertEquals(0, summary.uploaded)

        val verifyDest = File(deviceA.root.parentFile, "verify-remote.md")
        env.s3Client.downloadToFile(
            config = env.config.toEndpointConfig(),
            objectKey = env.prefix.value + "race.md",
            destinationFile = verifyDest,
        )
        assertEquals("external-winner", verifyDest.readText())
    }

    @Test
    fun hasCompletedInitialSyncStaysFalseOnFailureAndBecomesTrueOnSuccess() = withR2SyncEnv { env ->
        val deviceA = env.createDevice("vault-a")
        deviceA.writeText("first.md", "local-content")

        var injectedConflict = false
        val failedInitial = deviceA.sync(
            onProgress = { phase, _, _ ->
                if (phase == SyncPhase.SYNCING && !injectedConflict) {
                    injectedConflict = true
                    env.s3Client.uploadBytes(
                        config = env.config.toEndpointConfig(),
                        objectKey = env.prefix.value + "first.md",
                        bytes = "remote-racer".toByteArray(),
                        contentType = "text/markdown; charset=utf-8",
                        condition = S3PutCondition.Unconditional,
                    )
                }
            },
        )

        assertEquals(SyncRunOutcome.FAILED, failedInitial.outcome)
        assertEquals(1, failedInitial.failed)
        assertFalse(deviceA.hasCompletedInitialSync())

        val recovered = deviceA.sync()
        assertEquals(SyncRunOutcome.SUCCEEDED_WITH_CONFLICTS, recovered.outcome)
        assertEquals(0, recovered.failed)
        assertTrue(deviceA.hasCompletedInitialSync())
    }

    @Test
    fun adoptMatchedAvoidsConflictCopiesAndEstablishesBaselines() = withR2SyncEnv { env ->
        val deviceA = env.createDevice("vault-a")
        val deviceB = env.createDevice("vault-b")

        deviceA.writeText("same.md", "identical body")
        deviceB.writeText("same.md", "identical body")
        deviceA.writeBytes("same.bin", byteArrayOf(10, 20, 30, 40))
        deviceB.writeBytes("same.bin", byteArrayOf(10, 20, 30, 40))

        assertEquals(SyncRunOutcome.SUCCEEDED, deviceA.sync().outcome)

        val previewB = deviceB.preview()
        assertTrue(previewB.plan.isInitialSync)
        assertEquals(2, previewB.plan.matchedCount)
        assertEquals(0, previewB.plan.conflictCount)
        assertTrue(previewB.plan.items.all { it is SyncPlanItem.AdoptMatched })

        val syncB = deviceB.sync()
        assertEquals(SyncRunOutcome.SUCCEEDED, syncB.outcome)
        assertEquals(0, syncB.conflicts)
        assertEquals(0, syncB.uploaded)
        assertEquals(0, syncB.downloaded)
        assertTrue(deviceB.hasCompletedInitialSync())

        val conflictFiles = deviceB.root.listFiles().orEmpty()
            .filter { it.name.contains("conflicted copy") }
        assertTrue(conflictFiles.isEmpty())

        val entries = deviceB.dao.entriesFor(deviceB.vault.id.value, env.targetKey)
        assertEquals(2, entries.size)
    }

    @Test
    fun hiddenAndJunkFilesAreNeverUploaded() = withR2SyncEnv { env ->
        val deviceA = env.createDevice("vault-a")

        deviceA.writeText("visible.md", "public")
        deviceA.writeText(".looma/trash/old.md", "trashed")
        deviceA.writeText(".obsidian/workspace.json", "{}")
        deviceA.writeText(".hidden-file.md", "hidden")
        deviceA.writeText("draft.tmp", "temp")

        val summary = deviceA.sync()
        assertEquals(SyncRunOutcome.SUCCEEDED, summary.outcome)
        assertEquals(1, summary.uploaded)

        val remoteKeys = env.s3Client.listAllObjects(env.config.toEndpointConfig(), env.prefix)
            .map { it.key.removePrefix(env.prefix.value) }
        assertEquals(listOf("visible.md"), remoteKeys)
    }

    private class R2SyncEnv(
        val config: S3SyncConfig,
        val prefix: RemotePrefix,
        val s3Client: S3Client,
        private val tempDirs: MutableList<File>,
    ) {
        val targetKey: String = config.toEndpointConfig().targetKey(prefix)

        fun createDevice(name: String): SimulatedDevice {
            val dir = Files.createTempDirectory("looma-r2-$name-").toFile().canonicalFile
            tempDirs += dir
            val vault = Vault(
                id = VaultId(UUID.randomUUID().toString()),
                name = name,
                rootPath = dir.absolutePath,
            )
            val dao = InMemorySyncStateDao()
            val noteAccess = RecordingSyncNoteAccess()
            val engine = SyncEngine(
                s3Client = s3Client,
                syncStateDao = dao,
                noteRepository = noteAccess,
            )
            return SimulatedDevice(vault, dir, config, dao, noteAccess, engine)
        }
    }

    private class SimulatedDevice(
        val vault: Vault,
        val root: File,
        private val config: S3SyncConfig,
        val dao: InMemorySyncStateDao,
        val noteAccess: RecordingSyncNoteAccess,
        private val engine: SyncEngine,
    ) {
        fun file(relativePath: String): File = File(root, relativePath)

        fun writeText(relativePath: String, content: String) {
            val target = file(relativePath)
            target.parentFile?.mkdirs()
            target.writeText(content)
        }

        fun writeBytes(relativePath: String, bytes: ByteArray) {
            val target = file(relativePath)
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
        }

        fun readText(relativePath: String): String = file(relativePath).readText()

        fun delete(relativePath: String) {
            file(relativePath).delete()
        }

        fun sha256(relativePath: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = file(relativePath).readBytes()
            return digest.digest(bytes).joinToString("") { "%02x".format(it) }
        }

        suspend fun preview(): SyncPreview = engine.preview(vault, config) {}

        suspend fun sync(
            onProgress: suspend (SyncPhase, Int, Int) -> Unit = { _, _, _ -> },
        ): SyncRunSummary = engine.sync(
            vault = vault,
            config = config,
            checkVaultStillActive = {},
            onProgress = onProgress,
        )

        suspend fun hasCompletedInitialSync(): Boolean {
            val targetKey = config.toEndpointConfig().targetKey(
                RemotePrefix.normalizeOrNull(config.remotePrefix)!!,
            )
            return dao.getVaultState(vault.id.value, targetKey)?.hasCompletedInitialSync == true
        }
    }

    private class RecordingSyncNoteAccess : SyncNoteAccess {
        val changedNotes = mutableListOf<NotePath>()
        val removedNotes = mutableListOf<NotePath>()

        override suspend fun prepareForSync(vault: Vault): StalledRelocation? = null

        override suspend fun applyExternalNoteChanges(
            vault: Vault,
            changedNotes: List<NotePath>,
            removedNotes: List<NotePath>,
        ) {
            this.changedNotes += changedNotes
            this.removedNotes += removedNotes
        }
    }

    private class InMemorySyncStateDao : SyncStateDao {
        private val entries = ConcurrentHashMap<Triple<String, String, String>, SyncEntryEntity>()
        private val vaultStates = ConcurrentHashMap<Pair<String, String>, SyncVaultStateEntity>()
        private val stateFlows = ConcurrentHashMap<Pair<String, String>, MutableStateFlow<SyncVaultStateEntity?>>()

        override suspend fun entriesFor(vaultId: String, targetKey: String): List<SyncEntryEntity> =
            entries.values.filter { it.vaultId == vaultId && it.targetKey == targetKey }

        override suspend fun upsertEntry(entry: SyncEntryEntity) {
            entries[Triple(entry.vaultId, entry.targetKey, entry.path)] = entry
        }

        override suspend fun upsertEntries(entries: List<SyncEntryEntity>) {
            for (entry in entries) {
                upsertEntry(entry)
            }
        }

        override suspend fun deleteEntry(vaultId: String, targetKey: String, path: String) {
            entries.remove(Triple(vaultId, targetKey, path))
        }

        override fun observeVaultState(vaultId: String, targetKey: String): Flow<SyncVaultStateEntity?> {
            val key = vaultId to targetKey
            return stateFlows.getOrPut(key) { MutableStateFlow(vaultStates[key]) }.map { it }
        }

        override suspend fun getVaultState(vaultId: String, targetKey: String): SyncVaultStateEntity? =
            vaultStates[vaultId to targetKey]

        override suspend fun upsertVaultState(state: SyncVaultStateEntity) {
            val key = state.vaultId to state.targetKey
            vaultStates[key] = state
            stateFlows.getOrPut(key) { MutableStateFlow(state) }.value = state
        }
    }
}
