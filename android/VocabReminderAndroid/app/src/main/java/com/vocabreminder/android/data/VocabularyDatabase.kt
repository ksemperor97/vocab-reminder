package com.vocabreminder.android.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

/**
 * Row-per-entity storage for the vocabulary store.
 *
 * Every entity row keeps its sync wire JSON in `body` (one serializer for disk
 * and network) plus the few columns the app filters on. History lookups by card
 * and the upload outbox are indexed queries instead of re-parsing whole
 * collections on every read.
 */
internal class VocabularyDatabase private constructor(private val owner: Context, name: String?) :
    SQLiteOpenHelper(owner, name, null, SCHEMA_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE cards (id TEXT PRIMARY KEY, lemma TEXT NOT NULL, deleted_at TEXT, created_at TEXT NOT NULL, body TEXT NOT NULL)")
        db.execSQL("CREATE INDEX idx_cards_lemma ON cards(lemma)")
        db.execSQL("CREATE TABLE contexts (id TEXT PRIMARY KEY, card_id TEXT NOT NULL, created_at TEXT NOT NULL, body TEXT NOT NULL)")
        db.execSQL("CREATE TABLE definition_candidates (id TEXT PRIMARY KEY, card_id TEXT NOT NULL, created_at TEXT NOT NULL, body TEXT NOT NULL)")
        db.execSQL("CREATE TABLE review_events (id TEXT PRIMARY KEY, card_id TEXT NOT NULL, response TEXT NOT NULL, client_created_at TEXT NOT NULL, body TEXT NOT NULL)")
        db.execSQL("CREATE TABLE recommendation_feedback (id TEXT PRIMARY KEY, card_id TEXT NOT NULL, lemma TEXT NOT NULL, action TEXT NOT NULL, created_at TEXT NOT NULL, body TEXT NOT NULL)")
        for (table in LINKED_TABLES) db.execSQL("CREATE INDEX idx_${table}_card_id ON $table(card_id)")
        db.execSQL("CREATE TABLE enrichment_states (card_id TEXT PRIMARY KEY, body TEXT NOT NULL)")
        db.execSQL("CREATE TABLE kv (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        // Rows changed locally and not yet acknowledged by the server.
        db.execSQL("CREATE TABLE outbox (collection TEXT NOT NULL, id TEXT NOT NULL, PRIMARY KEY (collection, id))")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun <T> transaction(block: (SQLiteDatabase) -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val result = block(db)
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    fun bodies(sql: String, vararg args: String): List<JSONObject> {
        readableDatabase.rawQuery(sql, args).use { cursor ->
            val result = ArrayList<JSONObject>(cursor.count)
            while (cursor.moveToNext()) result += JSONObject(cursor.getString(0))
            return result
        }
    }

    fun strings(sql: String, vararg args: String): List<String> {
        readableDatabase.rawQuery(sql, args).use { cursor ->
            val result = ArrayList<String>(cursor.count)
            while (cursor.moveToNext()) result += cursor.getString(0)
            return result
        }
    }

    fun rows(sql: String, vararg args: String): List<List<String>> {
        readableDatabase.rawQuery(sql, args).use { cursor ->
            val result = ArrayList<List<String>>(cursor.count)
            while (cursor.moveToNext()) result += (0 until cursor.columnCount).map { cursor.getString(it) }
            return result
        }
    }

    fun kv(key: String): String? {
        readableDatabase.rawQuery("SELECT value FROM kv WHERE key = ?", arrayOf(key)).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    fun putCard(db: SQLiteDatabase, card: VocabularyCard, body: JSONObject) {
        db.insertWithOnConflict("cards", null, ContentValues().apply {
            put("id", card.id)
            put("lemma", card.lemma.trim().lowercase())
            put("deleted_at", card.deletedAt?.toString())
            put("created_at", card.createdAt.toString())
            put("body", body.toString())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun putContext(db: SQLiteDatabase, context: StoredContext, body: JSONObject) =
        putLinked(db, "contexts", context.id, context.cardId, body, "created_at" to context.createdAt.toString())

    fun putCandidate(db: SQLiteDatabase, candidate: StoredDefinitionCandidate, body: JSONObject) =
        putLinked(db, "definition_candidates", candidate.id, candidate.cardId, body, "created_at" to candidate.createdAt.toString())

    fun putEvent(db: SQLiteDatabase, event: StoredReviewEvent, body: JSONObject) =
        putLinked(
            db, "review_events", event.id, event.cardId, body,
            "response" to event.response.name.lowercase(),
            "client_created_at" to event.clientCreatedAt.toString()
        )

    fun putFeedback(db: SQLiteDatabase, feedback: RecommendationFeedback, body: JSONObject) =
        putLinked(
            db, "recommendation_feedback", feedback.id, feedback.cardId, body,
            "lemma" to feedback.lemma, "action" to feedback.action, "created_at" to feedback.createdAt.toString()
        )

    private fun putLinked(
        db: SQLiteDatabase,
        table: String,
        id: String,
        cardId: String,
        body: JSONObject,
        vararg columns: Pair<String, String>
    ) {
        db.insertWithOnConflict(table, null, ContentValues().apply {
            put("id", id)
            put("card_id", cardId)
            columns.forEach { (name, value) -> put(name, value) }
            put("body", body.toString())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun putKv(db: SQLiteDatabase, key: String, value: String?) {
        if (value == null) db.delete("kv", "key = ?", arrayOf(key))
        else db.insertWithOnConflict("kv", null, ContentValues().apply {
            put("key", key)
            put("value", value)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun markOutbox(db: SQLiteDatabase, collection: String, id: String) {
        db.insertWithOnConflict("outbox", null, ContentValues().apply {
            put("collection", collection)
            put("id", id)
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun clear() = transaction { db ->
        for (table in ALL_TABLES) db.delete(table, null, null)
    }

    companion object {
        private const val SCHEMA_VERSION = 1
        const val FILE_NAME = "vocab_reminder.db"
        val LINKED_TABLES = listOf("contexts", "definition_candidates", "review_events", "recommendation_feedback")
        val ENTITY_TABLES = listOf("cards") + LINKED_TABLES
        private val ALL_TABLES = ENTITY_TABLES + listOf("enrichment_states", "kv", "outbox")

        @Volatile
        private var instance: VocabularyDatabase? = null

        // One helper per process so every store instance shares SQLite's locking.
        // (Robolectric gives each test a fresh application; follow it.)
        fun get(context: Context): VocabularyDatabase {
            val app = context.applicationContext
            instance?.takeIf { it.owner === app }?.let { return it }
            return synchronized(this) {
                instance?.takeIf { it.owner === app } ?: VocabularyDatabase(app, FILE_NAME).also {
                    instance?.close()
                    instance = it
                }
            }
        }
    }
}
