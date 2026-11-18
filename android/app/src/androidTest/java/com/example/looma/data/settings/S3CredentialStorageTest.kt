package com.example.looma.data.settings

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class S3CredentialStorageTest {

    @Test
    fun credentialsRoundTripWithoutBeingStoredAsPlaintext() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = SettingsRepository(context)
        val original = repository.settings.first().imageBed
        val config = S3ImageBedConfig(
            endpoint = "https://storage.example.invalid",
            region = "test-region",
            bucket = "test-bucket",
            accessKey = "test-access-${UUID.randomUUID()}",
            secretKey = "test-secret-${UUID.randomUUID()}",
        )

        try {
            repository.setS3ImageBed(config)

            assertEquals(config, SettingsRepository(context).settings.first().imageBed)
            val persisted = File(context.filesDir, "datastore")
                .walkTopDown()
                .filter { it.isFile }
                .map { it.readText(Charsets.ISO_8859_1) }
                .toList()
            val exposedFields = listOfNotNull(
                "access key".takeIf { persisted.any { contents -> config.accessKey in contents } },
                "secret key".takeIf { persisted.any { contents -> config.secretKey in contents } },
            )
            assertEquals("S3 credentials exposed in persisted preferences", emptyList<String>(), exposedFields)
        } finally {
            repository.setS3ImageBed(original)
        }
    }
}
