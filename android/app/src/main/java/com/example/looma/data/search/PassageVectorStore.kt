package com.example.looma.data.search

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NotePath
import com.example.looma.domain.model.NoteRevision
import com.example.looma.domain.model.VaultId
import com.example.looma.domain.search.PassageAnchor
import com.example.looma.embedding.EmbeddingGemma
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

internal class PassageVectorStore(context: Context, private val file: File) :
    SQLiteOpenHelper(context, file.absolutePath, null, 1) {
    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE documents (vault TEXT, path TEXT, revision TEXT NOT NULL, PRIMARY KEY(vault,path))")
        db.execSQL("CREATE TABLE vectors (vault TEXT, path TEXT, input TEXT, bytes BLOB NOT NULL, PRIMARY KEY(vault,path,input))")
        db.execSQL("CREATE TABLE passages (vault TEXT, path TEXT, start INTEGER, end INTEGER, section TEXT NOT NULL, input TEXT NOT NULL, PRIMARY KEY(vault,path,start))")
        db.execSQL("CREATE INDEX passage_inputs ON passages(vault,path,input)")
        db.execSQL("CREATE TABLE dirty (vault TEXT, path TEXT, ticket TEXT NOT NULL, PRIMARY KEY(vault,path))")
        db.execSQL("CREATE TABLE initialized (vault TEXT PRIMARY KEY)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
        error("Unsupported vector database version $oldVersion")

    private fun any(query: String, vararg args: String): Boolean =
        file.isFile && readableDatabase.rawQuery(query, args).use { it.moveToFirst() }

    fun initialized(vault: VaultId) = any("SELECT 1 FROM initialized WHERE vault=?", vault.value)
    fun hasDocuments(vault: VaultId) = any("SELECT 1 FROM documents WHERE vault=? LIMIT 1", vault.value)
    fun hasPending(vault: VaultId) = any("SELECT 1 FROM dirty WHERE vault=? LIMIT 1", vault.value)
    fun finish(vault: VaultId) = writableDatabase.execSQL("INSERT OR IGNORE INTO initialized VALUES(?)", arrayOf(vault.value))
    fun current(id: NoteId, rev: NoteRevision.Known) =
        any("SELECT 1 FROM documents WHERE vault=? AND path=? AND revision=?", id.vaultId.value, id.path.relativePath, rev.sha256)

    private inline fun transaction(action: (SQLiteDatabase) -> Unit) {
        val db = writableDatabase
        db.beginTransactionNonExclusive()
        try {
            action(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun mark(vault: VaultId, paths: Collection<NotePath>) {
        if (paths.isEmpty()) return
        val ticket = UUID.randomUUID().toString()
        transaction { db ->
            for (path in paths) db.execSQL("INSERT OR REPLACE INTO dirty VALUES(?,?,?)", arrayOf(vault.value, path.relativePath, ticket))
        }
    }

    fun pending(vault: VaultId): Map<NotePath, String> = buildMap {
        readableDatabase.rawQuery("SELECT path,ticket FROM dirty WHERE vault=?", arrayOf(vault.value)).use {
            while (it.moveToNext()) put(NotePath(it.getString(0)), it.getString(1))
        }
    }

    fun acknowledge(id: NoteId, ticket: String) {
        writableDatabase.delete("dirty", "vault=? AND path=? AND ticket=?", arrayOf(id.vaultId.value, id.path.relativePath, ticket))
    }

    fun remove(id: NoteId) = transaction { db ->
        val args = arrayOf(id.vaultId.value, id.path.relativePath)
        for (table in listOf("passages", "vectors", "documents")) db.delete(table, "vault=? AND path=?", args)
    }

    fun prune(vault: VaultId, keep: Set<NoteId>, checkActive: () -> Unit) {
        val retired = readableDatabase.rawQuery("SELECT path FROM documents WHERE vault=?", arrayOf(vault.value)).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val id = NoteId(vault, NotePath(cursor.getString(0)))
                    if (id !in keep) add(id)
                }
            }
        }
        retired.forEach { checkActive(); remove(it) }
    }

    suspend fun replace(id: NoteId, revision: NoteRevision.Known, chunks: List<SemanticChunk>,
        key: (SemanticChunk) -> String, vector: suspend (SemanticChunk, String) -> FloatArray,
        checkActive: () -> Unit, verify: suspend () -> Unit) {
        val vault = id.vaultId.value
        val path = id.path.relativePath
        val args = arrayOf(vault, path)
        val present = readableDatabase.rawQuery("SELECT input FROM vectors WHERE vault=? AND path=?", args).use {
            buildSet { while (it.moveToNext()) add(it.getString(0)) }
        }.toMutableSet()
        val keys = ArrayList<String>(chunks.size)
        val batch = mutableListOf<Pair<String, ByteArray>>()
        fun flush() {
            if (batch.isEmpty()) return
            transaction { db ->
                for ((input, bytes) in batch) db.execSQL("INSERT OR IGNORE INTO vectors VALUES(?,?,?,?)", arrayOf(vault,path,input,bytes))
            }
            batch.clear()
        }
        for (chunk in chunks) {
            checkActive()
            val input = key(chunk)
            keys += input
            if (present.add(input)) {
                val values = vector(chunk, input)
                require(values.size == EmbeddingGemma.DIMENSIONS && values.all { it.isFinite() })
                val bytes = ByteBuffer.allocate(values.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
                bytes.asFloatBuffer().put(values)
                batch += input to bytes.array()
                if (batch.size >= 32) flush()
            }
        }
        flush()
        verify()
        transaction { db ->
            db.delete("passages", "vault=? AND path=?", args)
            db.compileStatement("INSERT INTO passages VALUES(?,?,?,?,?,?)").use { insert ->
                insert.bindString(1, vault)
                insert.bindString(2, path)
                chunks.forEachIndexed { index, chunk ->
                    checkActive()
                    insert.bindLong(3, chunk.start.toLong())
                    insert.bindLong(4, chunk.end.toLong())
                    insert.bindString(5, chunk.section)
                    insert.bindString(6, keys[index])
                    insert.executeInsert()
                }
            }
            db.execSQL("INSERT OR REPLACE INTO documents VALUES(?,?,?)", arrayOf(vault, path, revision.sha256))
            db.execSQL("DELETE FROM vectors WHERE vault=? AND path=? AND input NOT IN (SELECT input FROM passages WHERE vault=? AND path=?)",
                arrayOf(vault, path, vault, path))
        }
    }

    fun rank(vault: VaultId, ids: Collection<NoteId>, query: FloatArray, checkActive: () -> Unit): Map<NoteId, ScoredPassage> = buildMap<NoteId, ScoredPassage> {
        for (group in ids.chunked(400)) {
            checkActive()
            val slots = group.joinToString(",") { "?" }
            val args = (listOf(vault.value) + group.map { it.path.relativePath }).toTypedArray()
            val sql = """SELECT p.path,d.revision,p.start,p.end,p.section,v.bytes FROM passages p
                JOIN documents d USING(vault,path) JOIN vectors v USING(vault,path,input)
                WHERE p.vault=? AND p.path IN ($slots) ORDER BY p.path,p.start"""
            readableDatabase.rawQuery(sql, args).use { cursor ->
                while (cursor.moveToNext()) {
                    checkActive()
                    val values = ByteBuffer.wrap(cursor.getBlob(5)).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                    if (values.remaining() != query.size) continue
                    var score = 0.0
                    for (value in query) score += value.toDouble() * values.get()
                    val id = NoteId(vault, NotePath(cursor.getString(0)))
                    if (score.isFinite() && score > (get(id)?.score ?: Double.NEGATIVE_INFINITY)) {
                        val anchor = PassageAnchor(cursor.getString(1), cursor.getInt(2), cursor.getInt(3))
                        put(id, ScoredPassage(anchor, cursor.getString(4), score))
                    }
                }
            }
        }
    }
}
