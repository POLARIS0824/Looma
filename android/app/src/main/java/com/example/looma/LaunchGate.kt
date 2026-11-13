package com.example.looma

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class LaunchGate {
    private val deadline = SystemClock.uptimeMillis() + 1_000L

    var isReleased by mutableStateOf(false)
        private set

    val remainingMillis: Long
        get() = (deadline - SystemClock.uptimeMillis()).coerceAtLeast(0L)

    fun release() {
        isReleased = true
    }
}
