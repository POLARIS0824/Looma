package com.example.looma.data.index

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.looma.data.search.SearchHistoryDao
import com.example.looma.domain.model.VaultId
import kotlinx.coroutines.sync.Mutex

@Database(
    entities = [
        IndexedNoteEntity::class,
        NoteSearchSignatureEntity::class,
        NoteTagEntity::class,
        NoteLinkEntity::class,
        SearchHistoryEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
internal abstract class NoteIndexDatabase : RoomDatabase() {

    internal val indexWriteMutex = Mutex()
    internal val indexVersions = mutableMapOf<VaultId, Long>()
    internal var wikiSnapshot: WikiIndexSnapshot? = null

    abstract fun noteIndexDao(): NoteIndexDao

    abstract fun searchHistoryDao(): SearchHistoryDao

    companion object {
        private const val NAME = "looma_index.db"

        private val REBUILD_INDEX = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS note_link")
                db.execSQL("DROP TABLE IF EXISTS note_tag")
                db.execSQL("DROP TABLE IF EXISTS note_search_signature")
                db.execSQL("DROP TABLE IF EXISTS indexed_note")
                db.execSQL("CREATE TABLE IF NOT EXISTS `indexed_note` (`vaultId` TEXT NOT NULL, `path` TEXT NOT NULL, `title` TEXT NOT NULL, `snippet` TEXT NOT NULL, `coverPath` TEXT, `modifiedAt` INTEGER NOT NULL, `sizeBytes` INTEGER NOT NULL, `indexedAt` INTEGER NOT NULL, PRIMARY KEY(`vaultId`, `path`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `note_search_signature` (`vaultId` TEXT NOT NULL, `notePath` TEXT NOT NULL, `signature` BLOB NOT NULL, PRIMARY KEY(`vaultId`, `notePath`), FOREIGN KEY(`vaultId`, `notePath`) REFERENCES `indexed_note`(`vaultId`, `path`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                db.execSQL("CREATE TABLE IF NOT EXISTS `note_tag` (`vaultId` TEXT NOT NULL, `notePath` TEXT NOT NULL, `tag` TEXT NOT NULL, `ordinal` INTEGER NOT NULL, PRIMARY KEY(`vaultId`, `notePath`, `tag`), FOREIGN KEY(`vaultId`, `notePath`) REFERENCES `indexed_note`(`vaultId`, `path`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_note_tag_vaultId_notePath` ON `note_tag` (`vaultId`, `notePath`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `note_link` (`vaultId` TEXT NOT NULL, `notePath` TEXT NOT NULL, `ordinal` INTEGER NOT NULL, `target` TEXT NOT NULL, `embedded` INTEGER NOT NULL, PRIMARY KEY(`vaultId`, `notePath`, `ordinal`), FOREIGN KEY(`vaultId`, `notePath`) REFERENCES `indexed_note`(`vaultId`, `path`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_note_link_vaultId_notePath` ON `note_link` (`vaultId`, `notePath`)")
            }
        }

        @Volatile
        private var instance: NoteIndexDatabase? = null

        fun get(context: Context): NoteIndexDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                NoteIndexDatabase::class.java,
                NAME,
            )
                .addMigrations(REBUILD_INDEX)
                .build()
                .also { instance = it }
        }
    }
}
