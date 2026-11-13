package com.example.looma.feature.note

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

internal class SaveScheduler(
    private val scope: CoroutineScope,
    private val debounceMillis: Long = DEBOUNCE_MILLIS,
    private val maxWaitMillis: Long = MAX_WAIT_MILLIS,
    private val onSave: suspend () -> Unit,
) {
    private var editSerial = 0L
    private var period = 0L
    private var debounceJob: Job? = null
    private var maxWaitJob: Job? = null

    fun onEdit() {
        val serial = ++editSerial
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(debounceMillis.milliseconds)
            if (serial == editSerial) fire()
        }
        if (maxWaitJob == null) {
            val token = period
            maxWaitJob = scope.launch {
                delay(maxWaitMillis.milliseconds)
                if (token == period) fire()
            }
        }
    }

    fun cancel() {
        period++
        editSerial++
        debounceJob?.cancel()
        maxWaitJob?.cancel()
        debounceJob = null
        maxWaitJob = null
    }

    private fun fire() {
        period++
        editSerial++
        debounceJob = null
        maxWaitJob = null
        scope.launch { onSave() }
    }

    companion object {
        const val DEBOUNCE_MILLIS = 2_000L
        const val MAX_WAIT_MILLIS = 15_000L
    }
}
