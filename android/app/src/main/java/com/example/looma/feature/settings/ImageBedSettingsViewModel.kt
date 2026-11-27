package com.example.looma.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.looma.data.image.ImageUploader
import com.example.looma.data.settings.AppSettings
import com.example.looma.data.settings.ImageLinkFormat
import com.example.looma.data.settings.ImageUploadTarget
import com.example.looma.data.settings.S3ImageBedConfig
import com.example.looma.data.settings.SettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal data class ImageBedSettingsUiState(
    val settings: AppSettings = AppSettings(),
    val testState: S3ConnectionTestState = S3ConnectionTestState.Idle,
    val isLoaded: Boolean = false,
)

internal class ImageBedSettingsViewModel internal constructor(
    private val settingsRepository: SettingsRepository,
    private val testImageBedConnection: suspend (S3ImageBedConfig) -> Result<Unit>,
) : ViewModel() {
    constructor(
        settingsRepository: SettingsRepository,
        imageUploader: ImageUploader,
    ) : this(settingsRepository, imageUploader::testImageBedConnection)
    private val _testState = MutableStateFlow<S3ConnectionTestState>(S3ConnectionTestState.Idle)
    private var testJob: Job? = null

    val uiState: StateFlow<ImageBedSettingsUiState> = combine(
        settingsRepository.settings,
        _testState,
    ) { settings, testState ->
        ImageBedSettingsUiState(
            settings = settings,
            testState = testState,
            isLoaded = settings.isLoaded,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ImageBedSettingsUiState(),
    )

    fun setUploadTarget(target: ImageUploadTarget) {
        viewModelScope.launch {
            settingsRepository.setImageUploadTarget(target)
        }
    }

    suspend fun saveConfig(config: S3ImageBedConfig) {
        settingsRepository.setS3ImageBed(config)
        testJob?.cancel()
        _testState.value = S3ConnectionTestState.Idle
    }

    fun testConnection(config: S3ImageBedConfig) {
        testJob?.cancel()
        _testState.value = S3ConnectionTestState.Testing
        testJob = viewModelScope.launch {
            val result = testImageBedConnection(config)
            _testState.value = result.fold(
                onSuccess = { S3ConnectionTestState.Succeeded },
                onFailure = { error ->
                    val messageRes = s3ErrorMessageRes(error)
                    S3ConnectionTestState.Failed(
                        messageRes = messageRes,
                        detail = s3ErrorDetailFor(error, messageRes),
                    )
                },
            )
        }
    }

    fun resetTestState() {
        testJob?.cancel()
        _testState.value = S3ConnectionTestState.Idle
    }

    fun setNamingTemplate(template: String) {
        updateImageBed {
            it.copy(namingTemplate = template.trim().ifBlank { S3ImageBedConfig.DEFAULT_NAMING_TEMPLATE })
        }
    }

    fun setLinkFormat(format: ImageLinkFormat, customTemplate: String? = null) {
        updateImageBed { current ->
            if (customTemplate != null) {
                current.copy(
                    linkFormat = format,
                    customLinkTemplate = customTemplate.ifBlank { S3ImageBedConfig.DEFAULT_CUSTOM_LINK_TEMPLATE },
                )
            } else {
                current.copy(linkFormat = format)
            }
        }
    }

    fun setUrlSuffix(suffix: String) {
        updateImageBed { it.copy(urlSuffix = suffix.trim()) }
    }

    fun setEncodeUrl(enabled: Boolean) {
        updateImageBed { it.copy(encodeUrl = enabled) }
    }

    fun setOverwriteExisting(enabled: Boolean) {
        updateImageBed { it.copy(overwriteExisting = enabled) }
    }

    fun setCompressEnabled(enabled: Boolean) {
        updateImageBed { it.copy(compressEnabled = enabled) }
    }

    fun setCompressQuality(quality: Int) {
        updateImageBed { it.copy(compressQuality = quality.coerceIn(10, 100)) }
    }

    fun setConvertToWebp(enabled: Boolean) {
        updateImageBed { it.copy(convertToWebp = enabled) }
    }

    private fun updateImageBed(transform: (S3ImageBedConfig) -> S3ImageBedConfig) {
        viewModelScope.launch {
            settingsRepository.updateS3ImageBedOptions(transform)
        }
    }

    companion object {
        fun factory(
            settingsRepository: SettingsRepository,
            imageUploader: ImageUploader,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { ImageBedSettingsViewModel(settingsRepository, imageUploader) }
        }
    }
}
