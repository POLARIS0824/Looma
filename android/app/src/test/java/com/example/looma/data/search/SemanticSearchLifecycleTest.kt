package com.example.looma.data.search

import androidx.work.WorkInfo
import com.example.looma.data.inference.LocalModelSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SemanticSearchLifecycleTest {
    @Test
    fun selectWorkInfoPrioritizesActiveFailedCancelledThenLast() {
        assertNull(selectWorkInfo(emptyList<WorkInfo.State>()) { it })

        assertEquals(
            WorkInfo.State.RUNNING,
            selectWorkInfo(listOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.BLOCKED, WorkInfo.State.RUNNING)) { it },
        )
        assertEquals(
            WorkInfo.State.ENQUEUED,
            selectWorkInfo(listOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.ENQUEUED, WorkInfo.State.CANCELLED)) { it },
        )
        assertEquals(
            WorkInfo.State.FAILED,
            selectWorkInfo(listOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED, WorkInfo.State.CANCELLED)) { it },
        )
        assertEquals(
            WorkInfo.State.FAILED,
            selectWorkInfo(listOf(WorkInfo.State.FAILED, WorkInfo.State.SUCCEEDED)) { it },
        )
        assertEquals(
            WorkInfo.State.CANCELLED,
            selectWorkInfo(listOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.CANCELLED)) { it },
        )
        assertEquals(
            WorkInfo.State.CANCELLED,
            selectWorkInfo(listOf(WorkInfo.State.CANCELLED, WorkInfo.State.SUCCEEDED)) { it },
        )
        assertEquals(
            WorkInfo.State.SUCCEEDED,
            selectWorkInfo(listOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.SUCCEEDED)) { it },
        )
    }

    @Test
    fun repeatedCancelAndRestartDoesNotDeadlockAndClosesCancelledSessions() = runBlocking {
        val session = LocalModelSession()
        val indexMutex = Mutex()
        var openCalls = 0
        var closeCalls = 0

        withTimeout(5_000) {
            repeat(6) {
                val started = CompletableDeferred<Unit>()
                val job = launch(Dispatchers.Default) {
                    indexMutex.withLock {
                        try {
                            session.use(LocalModelSession.Model.Embedding, null, { closeCalls++ }) {
                                openCalls++
                                started.complete(Unit)
                                CompletableDeferred<Unit>().await()
                            }
                        } finally {
                            withContext(NonCancellable) {
                                session.release(LocalModelSession.Model.Embedding)
                            }
                        }
                    }
                }
                started.await()
                job.cancelAndJoin()
            }

            var finalCompleted = false
            indexMutex.withLock {
                try {
                    session.use(LocalModelSession.Model.Embedding, null, { closeCalls++ }) {
                        openCalls++
                        finalCompleted = true
                    }
                } finally {
                    withContext(NonCancellable) {
                        session.release(LocalModelSession.Model.Embedding)
                    }
                }
            }
            assertEquals(true, finalCompleted)
        }

        assertEquals(7, openCalls)
        assertEquals(7, closeCalls)
    }
}
