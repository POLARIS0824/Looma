package com.example.looma.data.settings

import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.viewModelScope
import com.example.looma.feature.settings.ImageBedSettingsViewModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsPreferencesImageBedTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private fun createDataStore() = DataStoreFactory.create(
        storage = OkioStorage(
            fileSystem = FileSystem.SYSTEM,
            serializer = PreferencesSerializer,
            producePath = { File(tmpFolder.newFolder(), "settings.preferences_pb").toOkioPath() },
        ),
    )

    @Test
    fun `setLinkFormat persists both linkFormat and customLinkTemplate`() = runTest {
        val mainDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(mainDispatcher)
        val dataStore = createDataStore()
        val repository = SettingsRepository(SettingsPreferences(dataStore))
        val viewModel = ImageBedSettingsViewModel(repository) { Result.success(Unit) }
        try {
            viewModel.setLinkFormat(ImageLinkFormat.Custom, "custom:{url}")
            runCurrent()
            val stored = repository.settings.first {
                it.imageBed.linkFormat == ImageLinkFormat.Custom
            }
            assertEquals(ImageLinkFormat.Custom, stored.imageBed.linkFormat)
            assertEquals("custom:{url}", stored.imageBed.customLinkTemplate)
        } finally {
            viewModel.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `rapid sequential updateS3ImageBedOptions calls do not lose updates`() = runTest {
        val dataStore = createDataStore()
        val repository = SettingsRepository(SettingsPreferences(dataStore))

        repository.updateS3ImageBedOptions { it.copy(namingTemplate = "{year}/{fileName}.{extName}") }
        repository.updateS3ImageBedOptions { it.copy(urlSuffix = "?w=800") }
        repository.updateS3ImageBedOptions { it.copy(encodeUrl = false) }
        repository.updateS3ImageBedOptions { it.copy(overwriteExisting = false) }
        repository.updateS3ImageBedOptions { it.copy(compressEnabled = true) }
        repository.updateS3ImageBedOptions { it.copy(compressQuality = 70) }
        repository.updateS3ImageBedOptions { it.copy(convertToWebp = true) }

        val config = repository.settings.first().imageBed
        assertEquals("{year}/{fileName}.{extName}", config.namingTemplate)
        assertEquals("?w=800", config.urlSuffix)
        assertEquals(false, config.encodeUrl)
        assertEquals(false, config.overwriteExisting)
        assertEquals(true, config.compressEnabled)
        assertEquals(70, config.compressQuality)
        assertEquals(true, config.convertToWebp)
    }

    @Test
    fun `updateS3ImageBedOptions does not remove or alter unreadable KEY_S3_CREDENTIALS_V1`() = runTest {
        val dataStore = createDataStore()
        val seededCiphertext = "unreadable-ciphertext-blob"
        dataStore.edit { prefs ->
            prefs[SettingsPreferences.KEY_S3_CREDENTIALS_V1] = seededCiphertext
        }
        val repository = SettingsRepository(SettingsPreferences(dataStore))

        repository.updateS3ImageBedOptions {
            it.copy(
                linkFormat = ImageLinkFormat.Html,
                compressEnabled = true,
                compressQuality = 65,
            )
        }

        val rawPrefs = dataStore.data.first()
        assertEquals(seededCiphertext, rawPrefs[SettingsPreferences.KEY_S3_CREDENTIALS_V1])
        val settings = repository.settings.first()
        assertTrue(settings.s3CredentialsUnavailable)
        assertEquals(ImageLinkFormat.Html, settings.imageBed.linkFormat)
        assertEquals(true, settings.imageBed.compressEnabled)
        assertEquals(65, settings.imageBed.compressQuality)
    }
}
