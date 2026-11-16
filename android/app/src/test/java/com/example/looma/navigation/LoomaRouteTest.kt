package com.example.looma.navigation

import androidx.navigation3.runtime.NavKey
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NotePath
import com.example.looma.domain.model.Vault
import com.example.looma.domain.model.VaultId
import com.example.looma.feature.vault.VaultSelection
import com.example.looma.feature.vault.VaultSetupUiState
import com.example.looma.feature.vault.VaultState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LoomaRouteTest {
    @Test
    fun `losing vault access resets the whole stack even when setup is already on top`() {
        val vault = Vault(VaultId("vault"), "Vault", "/vault")
        val state = VaultSetupUiState(vaultState = VaultState.Unavailable(vault, "access revoked"))

        assertEquals(
            LoomaRoute.VaultSetup,
            state.vaultStackReset(listOf(LoomaRoute.Home, LoomaRoute.Settings, LoomaRoute.VaultSetup)),
        )
        assertNull(state.vaultStackReset(listOf(LoomaRoute.VaultSetup)))
    }

    @Test
    fun `setup navigates home only for the vault that completed activation`() {
        val old = Vault(VaultId("old"), "Old vault", "/old")
        val activated = Vault(VaultId("new"), "New vault", "/new")
        val state = VaultSetupUiState(
            vaultState = VaultState.Ready(old),
            selection = VaultSelection.Activated(activated),
        )
        val stack = listOf(LoomaRoute.Home, LoomaRoute.Settings, LoomaRoute.VaultSetup)

        assertNull(state.vaultStackReset(stack))
        assertEquals(LoomaRoute.Home, state.copy(vaultState = VaultState.Ready(activated)).vaultStackReset(stack))
    }

    @Test
    fun `renaming a note retargets every visit in its vault without changing visit identity`() {
        val original = NoteId(VaultId("vault"), NotePath("folder/A.md"))
        val renamed = original.copy(path = NotePath("folder/Renamed.md"))
        val firstVisit = LoomaRoute.Note.from(original, isNew = true)
        val linkedNote = LoomaRoute.Note.from(original.copy(path = NotePath("other/A.md")))
        val secondVisit = LoomaRoute.Note.from(original)
        val otherVault = LoomaRoute.Note.from(original.copy(vaultId = VaultId("other")))
        val stack = mutableListOf<NavKey>(LoomaRoute.Home, firstVisit, linkedNote, secondVisit, otherVault)

        stack.retargetNoteVisits(original, renamed, secondVisit.visitId)

        assertEquals(
            listOf(
                LoomaRoute.Home,
                firstVisit.copy(relativePath = "folder/Renamed.md", isNew = false, retargetVersion = 1),
                linkedNote,
                secondVisit.copy(relativePath = "folder/Renamed.md"),
                otherVault,
            ),
            stack,
        )
    }
}
