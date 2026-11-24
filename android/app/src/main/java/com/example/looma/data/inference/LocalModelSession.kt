package com.example.looma.data.inference

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class LocalModelSession {
    enum class Model { Embedding, Generation }

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var active: Model? = null
    private var close: (() -> Unit)? = null
    private var idle: Job? = null

    suspend fun <T> use(model: Model, idleMillis: Long?, onClose: () -> Unit, block: suspend () -> T): T =
        mutex.withLock {
            idle?.cancel()
            if (active != model) {
                closeActive()
                active = model
                close = onClose
            }
            try {
                block()
            } catch (error: Throwable) {
                runCatching { closeActive() }.exceptionOrNull()?.let(error::addSuppressed)
                throw error
            } finally {
                if (active == model && idleMillis != null) idle = scope.launch {
                    delay(idleMillis)
                    mutex.withLock { closeActive() }
                }
            }
        }

    suspend fun release(model: Model) = mutex.withLock {
        if (active == model) {
            idle?.cancel()
            closeActive()
        }
    }

    private fun closeActive() {
        val closing = close
        close = null
        active = null
        closing?.invoke()
    }
}
