package com.example.looma.data.index

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.looma.data.assistant.history.AskConversationEntity
import com.example.looma.data.assistant.history.AskHistoryDao
import com.example.looma.data.assistant.history.AskMessageEntity
import com.example.looma.data.search.SearchHistoryDao
import com.example.looma.data.sync.SyncEntryEntity
import com.example.looma.data.sync.SyncStateDao
import com.example.looma.data.sync.SyncVaultStateEntity
import com.example.looma.domain.model.VaultId
import kotlinx.coroutines.sync.Mutex

@Database(
    entities = [
        IndexedNoteEntity::class,
        NoteSearchSignatureEntity::class,
        NoteTagEntity::class,
        NoteLinkEntity::class,
        SearchHistoryEntity::class,
        SyncEntryEntity::class,
        SyncVaultStateEntity::class,
        AskConversationEntity::class,
        AskMessageEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
internal abstract class NoteIndexDatabase : RoomDatabase() {

    internal val indexWriteMutex = Mutex()
    internal val indexVersions = mutableMapOf<VaultId, Long>()
    internal var wikiSnapshot: WikiIndexSnapshot? = null

    abstract fun noteIndexDao(): NoteIndexDao

    abstract fun searchHistoryDao(): SearchHistoryDao

    abstract fun syncStateDao(): SyncStateDao

    abstract fun askHistoryDao(): AskHistoryDao

    companion object {
        private const val NAME = "looma_index.db"

        @Volatile
        private var instance: NoteIndexDatabase? = null

        fun get(context: Context): NoteIndexDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                NoteIndexDatabase::class.java,
                NAME,
            )
                .build()
                .also { instance = it }
        }
    }
}
