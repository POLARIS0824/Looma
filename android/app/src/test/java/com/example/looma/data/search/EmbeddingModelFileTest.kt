package com.example.looma.data.search

import com.example.looma.embedding.EmbeddingBackend
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EmbeddingModelFileTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun deleteOnlyRemovesRequestedBackendModelAndPartial() = runTest {
        val dir = tempFolder.newFolder("models")
        val cpuBytes = "cpu-model-payload".toByteArray()
        val npuBytes = "npu-model-payload-for-sm8650".toByteArray()
        val cpuSpec = spec("cpu.litertlm", cpuBytes)
        val npuSpec = spec("npu.litertlm", npuBytes)

        dir.resolve(cpuSpec.fileName).writeBytes(cpuBytes)
        dir.resolve("${cpuSpec.fileName}.partial").writeBytes(byteArrayOf(1, 2, 3))
        dir.resolve(npuSpec.fileName).writeBytes(npuBytes)
        dir.resolve("${npuSpec.fileName}.partial").writeBytes(byteArrayOf(4, 5, 6))

        val modelFile = EmbeddingModelFile(dir) { cpuSpec }
        assertNotNull(modelFile.open(cpuSpec))
        assertNotNull(modelFile.open(npuSpec))

        modelFile.delete(cpuSpec)

        assertFalse(modelFile.exists(cpuSpec))
        assertNull(modelFile.open(cpuSpec))
        assertFalse(dir.resolve("${cpuSpec.fileName}.partial").exists())

        assertTrue(modelFile.exists(npuSpec))
        assertNotNull(modelFile.open(npuSpec))
        assertTrue(dir.resolve("${npuSpec.fileName}.partial").exists())

        modelFile.delete(npuSpec)

        assertFalse(modelFile.exists(npuSpec))
        assertNull(modelFile.open(npuSpec))
        assertFalse(dir.resolve("${npuSpec.fileName}.partial").exists())
    }

    @Test
    fun catalogResolvesSupportedAndUnsupportedNpuSocs() {
        assertTrue(EmbeddingModelCatalog.supportsNpu("SM8650"))
        assertEquals(EmbeddingBackend.NPU, EmbeddingModelCatalog.effectiveBackend(EmbeddingBackend.NPU, "sm8650"))
        assertEquals(
            "embeddinggemma-2-text-270m_Qualcomm_SM8650.litertlm",
            EmbeddingModelCatalog.resolve(EmbeddingBackend.NPU, "SM8650").fileName,
        )

        assertFalse(EmbeddingModelCatalog.supportsNpu("unknown_soc"))
        assertEquals(EmbeddingBackend.CPU, EmbeddingModelCatalog.effectiveBackend(EmbeddingBackend.NPU, "unknown_soc"))
        assertEquals(EmbeddingModelCatalog.CPU_MODEL, EmbeddingModelCatalog.resolve(EmbeddingBackend.NPU, "unknown_soc"))
        assertEquals(EmbeddingModelCatalog.CPU_MODEL, EmbeddingModelCatalog.resolve(EmbeddingBackend.CPU, "sm8650"))
    }

    private fun spec(fileName: String, content: ByteArray): EmbeddingModelSpec {
        val digest = MessageDigest.getInstance("SHA-256").digest(content)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return EmbeddingModelSpec(
            fileName = fileName,
            bytes = content.size.toLong(),
            sha256 = digest,
            url = "https://example.invalid/$fileName",
        )
    }
}
