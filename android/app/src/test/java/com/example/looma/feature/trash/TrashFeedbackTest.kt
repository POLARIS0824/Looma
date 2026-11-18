package com.example.looma.feature.trash

import com.example.looma.data.note.TrashBulkOutcome
import org.junit.Assert.assertEquals
import org.junit.Test

class TrashFeedbackTest {

    @Test
    fun `emptyTrashFeedback separates partial permanent deletion from restore copy`() {
        val failed = "That action failed"
        assertEquals(
            failed,
            emptyTrashFeedback(
                outcome = null,
                actionFailedText = failed,
                fullSuccess = { "Deleted $it permanently" },
                partial = { s, f -> "Deleted $s permanently, $f failed" },
            ),
        )
        assertEquals(
            failed,
            emptyTrashFeedback(
                outcome = TrashBulkOutcome(succeeded = 0, failed = 3),
                actionFailedText = failed,
                fullSuccess = { "Deleted $it permanently" },
                partial = { s, f -> "Deleted $s permanently, $f failed" },
            ),
        )
        assertEquals(
            "Deleted 3 permanently",
            emptyTrashFeedback(
                outcome = TrashBulkOutcome(succeeded = 3, failed = 0),
                actionFailedText = failed,
                fullSuccess = { "Deleted $it permanently" },
                partial = { s, f -> "Deleted $s permanently, $f failed" },
            ),
        )
        assertEquals(
            "Deleted 2 permanently, 1 failed",
            emptyTrashFeedback(
                outcome = TrashBulkOutcome(succeeded = 2, failed = 1),
                actionFailedText = failed,
                fullSuccess = { "Deleted $it permanently" },
                partial = { s, f -> "Deleted $s permanently, $f failed" },
            ),
        )
    }
}
