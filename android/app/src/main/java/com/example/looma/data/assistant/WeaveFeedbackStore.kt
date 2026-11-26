package com.example.looma.data.assistant

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NotePath
import java.io.File

internal enum class WeaveFeedbackState { Pinned, Hidden }

internal class WeaveFeedbackStore(
    context: Context,
    private val file: File = context.noBackupFilesDir.resolve("weave-feedback.db"),
) : SQLiteOpenHelper(context, file.absolutePath, null, 1) {
    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS weave_feedback (vault TEXT NOT NULL, source_path TEXT NOT NULL, target_path TEXT NOT NULL, state TEXT NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY(vault, source_path, target_path))",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int): Unit =
        error("Unsupported weave feedback database version $oldVersion")

    fun statesFor(source: NoteId): Map<NotePath, WeaveFeedbackState> {
        if (!file.isFile) return emptyMap()
        return buildMap {
            readableDatabase.rawQuery(
                "SELECT target_path, state FROM weave_feedback WHERE vault=? AND source_path=?",
                arrayOf(source.vaultId.value, source.path.relativePath),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val stateName = cursor.getString(1)
                    val state = WeaveFeedbackState.entries.firstOrNull { it.name == stateName } ?: continue
                    put(NotePath(cursor.getString(0)), state)
                }
            }
        }
    }

    fun setState(source: NoteId, target: NotePath, state: WeaveFeedbackState) {
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO weave_feedback VALUES(?,?,?,?,?)",
            arrayOf<Any>(source.vaultId.value, source.path.relativePath, target.relativePath, state.name, System.currentTimeMillis()),
        )
    }

    fun clearState(source: NoteId, target: NotePath) {
        writableDatabase.delete(
            "weave_feedback",
            "vault=? AND source_path=? AND target_path=?",
            arrayOf(source.vaultId.value, source.path.relativePath, target.relativePath),
        )
    }

    fun clearHidden(source: NoteId): Int = writableDatabase.delete(
        "weave_feedback",
        "vault=? AND source_path=? AND state=?",
        arrayOf(source.vaultId.value, source.path.relativePath, WeaveFeedbackState.Hidden.name),
    )
}
