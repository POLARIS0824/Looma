package com.example.looma.feature.note

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SaveSchedulerTest {

    @Test
    fun `a burst saves once after the trailing debounce`() = runTest {
        val fires = mutableListOf<Long>()
        val scheduler = scheduler(backgroundScope) { fires += currentTime }

        scheduler.onEdit()
        advanceTimeBy(1_000)
        scheduler.onEdit()
        advanceTimeBy(1_999)
        runCurrent()
        assertTrue(fires.isEmpty())

        advanceTimeBy(2)
        runCurrent()

        assertEquals(listOf(3_000L), fires)
    }

    @Test
    fun `continuous edits save at the maximum wait`() = runTest {
        val fires = mutableListOf<Long>()
        val scheduler = scheduler(backgroundScope) { fires += currentTime }

        repeat(43) {
            scheduler.onEdit()
            advanceTimeBy(700)
            runCurrent()
        }

        assertEquals(listOf(15_000L), fires)
    }

    @Test
    fun `cancel drops the armed timers`() = runTest {
        var saves = 0
        val scheduler = scheduler(backgroundScope) { saves++ }

        scheduler.onEdit()
        advanceTimeBy(1_000)
        scheduler.cancel()
        advanceTimeBy(20_000)
        runCurrent()

        assertEquals(0, saves)
    }

    @Test
    fun `an edit during a save schedules another save`() = runTest {
        val release = CompletableDeferred<Unit>()
        var saves = 0
        val scheduler = scheduler(backgroundScope) {
            saves++
            if (saves == 1) release.await()
        }

        scheduler.onEdit()
        advanceTimeBy(2_001)
        runCurrent()
        assertEquals(1, saves)

        scheduler.onEdit()
        advanceTimeBy(2_001)
        runCurrent()
        assertEquals(2, saves)

        release.complete(Unit)
        runCurrent()
    }

    private fun scheduler(
        scope: CoroutineScope,
        onSave: suspend () -> Unit,
    ) = SaveScheduler(
        scope = scope,
        debounceMillis = 2_000,
        maxWaitMillis = 15_000,
        onSave = onSave,
    )
}
