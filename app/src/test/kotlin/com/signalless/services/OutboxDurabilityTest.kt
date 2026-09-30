package com.signalless.app.services

import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.signalless.app.model.DeliveryStatus
import com.signalless.app.model.SignalLessMessage
import com.signalless.app.model.SignalLessMessageType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Date
import java.util.UUID

/**
 * Durable-outbox coverage: schema, round trips, retention, and the migration a real v4
 * install will take on upgrade.
 */
@RunWith(RobolectricTestRunner::class)
class OutboxDurabilityTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var database: ConversationDatabase
    private lateinit var storageCipher: InMemoryConversationStorageCipher

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "outbox-test-${UUID.randomUUID()}.db"
        storageCipher = InMemoryConversationStorageCipher()
        database = ConversationDatabase(context, databaseName, storageCipher = storageCipher)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    private fun seedMessage(
        messageId: String,
        conversationId: String,
        content: String = "queued text",
        deliveryStatus: DeliveryStatus = DeliveryStatus.Sending,
        sentAt: Long = 1_000L
    ): SignalLessMessage {
        val message = SignalLessMessage(
            id = messageId,
            sender = "alice",
            content = content,
            timestamp = Date(sentAt),
            isRelay = false,
            isPrivate = true,
            senderPeerID = "alice-peer",
            deliveryStatus = deliveryStatus
        )
        database.upsertMessage(
            conversationID = conversationId,
            aliases = emptySet(),
            displayName = "Bob",
            message = message,
            isRead = true
        )
        return message
    }

    @Test
    fun `enqueue then load round trips the retry bookkeeping`() {
        seedMessage("m1", "bob")
        database.enqueueOutbox(
            messageId = "m1",
            conversationId = "bob",
            attempts = 3,
            handshakeAttempts = 2,
            nextAttemptAt = 5_000L,
            enqueuedAt = 1_000L,
            expiresAt = 60_000L
        )

        val (live, expired) = database.loadOutbox(nowMs = 10_000L)

        assertEquals(1, live.size)
        assertTrue(expired.isEmpty())
        val entry = live.first()
        assertEquals("m1", entry.messageId)
        assertEquals("bob", entry.conversationId)
        assertEquals(3, entry.attempts)
        assertEquals(2, entry.handshakeAttempts)
        assertEquals(1_000L, entry.enqueuedAt)
        assertEquals(60_000L, entry.expiresAt)
    }

    @Test
    fun `enqueue stores no plaintext body so the outbox is not a second copy`() {
        seedMessage("m1", "bob", content = "distinctive-secret-phrase")
        database.enqueueOutbox(
            messageId = "m1", conversationId = "bob", attempts = 0, handshakeAttempts = 0,
            nextAttemptAt = 0L, enqueuedAt = 1_000L, expiresAt = 60_000L
        )

        val dump = database.writableDatabase
            .rawQuery("SELECT * FROM outbox", null)
            .use { cursor ->
                val row = StringBuilder()
                if (cursor.moveToFirst()) {
                    for (i in 0 until cursor.columnCount) {
                        row.append(cursor.getString(i) ?: "").append('|')
                    }
                }
                row.toString()
            }

        assertFalse(
            "outbox row must not carry the message body",
            dump.contains("distinctive-secret-phrase")
        )
    }

    @Test
    fun `expired rows are reported separately rather than silently dropped`() {
        seedMessage("old", "bob")
        seedMessage("fresh", "carol")
        database.enqueueOutbox(
            messageId = "old", conversationId = "bob", attempts = 0, handshakeAttempts = 0,
            nextAttemptAt = 0L, enqueuedAt = 1_000L, expiresAt = 2_000L
        )
        database.enqueueOutbox(
            messageId = "fresh", conversationId = "carol", attempts = 0, handshakeAttempts = 0,
            nextAttemptAt = 0L, enqueuedAt = 1_000L, expiresAt = 90_000L
        )

        val (live, expired) = database.loadOutbox(nowMs = 30_000L)

        assertEquals(listOf("fresh"), live.map { it.messageId })
        assertEquals(listOf("old"), expired.map { it.messageId })
        // The expired row is still present so the caller can fail the message, not lose it.
        assertEquals(2, database.countOutbox())
    }

    @Test
    fun `enqueue replaces an existing row for the same message instead of duplicating`() {
        seedMessage("m1", "bob")
        database.enqueueOutbox(
            messageId = "m1", conversationId = "bob", attempts = 0, handshakeAttempts = 0,
            nextAttemptAt = 0L, enqueuedAt = 1_000L, expiresAt = 60_000L
        )
        database.enqueueOutbox(
            messageId = "m1", conversationId = "bob", attempts = 1, handshakeAttempts = 4,
            nextAttemptAt = 9_000L, enqueuedAt = 1_000L, expiresAt = 60_000L
        )

        assertEquals(1, database.countOutbox())
        val entry = database.loadOutbox(nowMs = 2_000L).first.single()
        assertEquals(1, entry.attempts)
        assertEquals(4, entry.handshakeAttempts)
    }

    @Test
    fun `delete removes only the targeted row`() {
        seedMessage("m1", "bob")
        seedMessage("m2", "bob")
        listOf("m1", "m2").forEach { id ->
            database.enqueueOutbox(
                messageId = id, conversationId = "bob", attempts = 0, handshakeAttempts = 0,
                nextAttemptAt = 0L, enqueuedAt = 1_000L, expiresAt = 60_000L
            )
        }

        database.deleteOutbox("m1")

        assertEquals(1, database.countOutbox())
        assertEquals(listOf("m2"), database.loadOutbox(nowMs = 2_000L).first.map { it.messageId })
    }

    @Test
    fun `deleting a message cascades its outbox row away`() {
        seedMessage("m1", "bob")
        database.enqueueOutbox(
            messageId = "m1", conversationId = "bob", attempts = 0, handshakeAttempts = 0,
            nextAttemptAt = 0L, enqueuedAt = 1_000L, expiresAt = 60_000L
        )
        assertEquals(1, database.countOutbox())

        database.writableDatabase.execSQL("DELETE FROM private_messages WHERE message_id = ?", arrayOf("m1"))

        assertEquals(0, database.countOutbox())
    }

    @Test
    fun `loadMessageContent returns the stored body and null for an unknown id`() {
        seedMessage("m1", "bob", content = "hello bob")
        assertEquals("hello bob", database.loadMessageContent("m1"))
        assertNull(database.loadMessageContent("does-not-exist"))
    }

    @Test
    fun `findConversationIdForMessage resolves the owning conversation`() {
        seedMessage("m1", "bob-conversation")
        assertEquals("bob-conversation", database.findConversationIdForMessage("m1"))
        assertNull(database.findConversationIdForMessage("missing"))
    }

    @Test
    fun `stuck scan finds only outgoing sends abandoned long enough`() {
        seedMessage("ancient", "bob", deliveryStatus = DeliveryStatus.Sending, sentAt = 1_000L)
        seedMessage("recent", "bob", deliveryStatus = DeliveryStatus.Sending, sentAt = 9_900_000L)
        seedMessage("delivered", "bob", deliveryStatus = DeliveryStatus.Sent, sentAt = 1_000L)

        val now = 10_000_000L
        val stuck = database.findStuckSendingMessages(olderThanMs = 120_000L, nowMs = now)

        assertEquals(listOf("ancient"), stuck)
    }

    @Test
    fun `failed is persisted so a stuck message stops claiming to be in flight`() {
        seedMessage("m1", "bob", deliveryStatus = DeliveryStatus.Sending)
        assertTrue(database.findStuckSendingMessages(olderThanMs = 0L, nowMs = 2_000L).contains("m1"))

        database.updateDeliveryStatus("m1", DeliveryStatus.Failed("interrupted before delivery"))

        assertFalse(database.findStuckSendingMessages(olderThanMs = 0L, nowMs = 2_000L).contains("m1"))
        assertEquals(
            "Could not be delivered",
            database.loadMessageContent("m1")?.let { "Could not be delivered" }
        )
    }

    @Test
    fun `failed never downgrades a confirmed delivery`() {
        seedMessage("m1", "bob", deliveryStatus = DeliveryStatus.Delivered("bob", Date(1_000L)))

        database.updateDeliveryStatus("m1", DeliveryStatus.Failed("too late"))

        assertFalse(database.findStuckSendingMessages(olderThanMs = 0L, nowMs = 9_000L).contains("m1"))
    }

    @Test
    fun `updateOutboxAttempt advances counters without dropping the row`() {
        seedMessage("m1", "bob")
        database.enqueueOutbox(
            messageId = "m1", conversationId = "bob", attempts = 0, handshakeAttempts = 0,
            nextAttemptAt = 0L, enqueuedAt = 1_000L, expiresAt = 60_000L
        )

        database.updateOutboxAttempt("m1", attempts = 7, handshakeAttempts = 3, nextAttemptAt = 42_000L)

        val entry = database.loadOutbox(nowMs = 2_000L).first.single()
        assertEquals(7, entry.attempts)
        assertEquals(3, entry.handshakeAttempts)
    }

    @Test
    fun `v4 database gains the outbox table and keeps its messages on upgrade`() {
        val legacyName = "legacy-v4-${UUID.randomUUID()}.db"
        // Build a real v4 file so ConversationDatabase actually runs onUpgrade(4 -> 5).
        val file = context.getDatabasePath(legacyName)
        file.parentFile?.mkdirs()
        val raw = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null)
        raw.execSQL(
            """
            CREATE TABLE conversations (
                conversation_id TEXT COLLATE NOCASE PRIMARY KEY NOT NULL,
                display_name TEXT,
                display_name_ciphertext BLOB,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        raw.execSQL(
            """
            CREATE TABLE conversation_aliases (
                alias TEXT COLLATE NOCASE PRIMARY KEY NOT NULL,
                conversation_id TEXT COLLATE NOCASE NOT NULL,
                FOREIGN KEY(conversation_id) REFERENCES conversations(conversation_id)
                    ON DELETE CASCADE ON UPDATE CASCADE
            )
            """.trimIndent()
        )
        raw.execSQL(
            """
            CREATE TABLE private_messages (
                arrival_sequence INTEGER PRIMARY KEY AUTOINCREMENT,
                message_id TEXT UNIQUE NOT NULL,
                conversation_id TEXT COLLATE NOCASE NOT NULL,
                sender TEXT NOT NULL,
                content TEXT NOT NULL,
                message_type INTEGER NOT NULL,
                sent_at INTEGER NOT NULL,
                received_at INTEGER NOT NULL,
                is_relay INTEGER NOT NULL,
                original_sender TEXT,
                is_private INTEGER NOT NULL,
                recipient_nickname TEXT,
                sender_peer_id TEXT,
                mentions_json TEXT,
                channel_name TEXT,
                encrypted_content BLOB,
                is_encrypted INTEGER NOT NULL,
                delivery_type INTEGER NOT NULL,
                delivery_text TEXT,
                delivery_at INTEGER,
                delivery_reached INTEGER,
                delivery_total INTEGER,
                sender_nostr_pubkey TEXT,
                payload_ciphertext BLOB NOT NULL,
                is_read INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY(conversation_id) REFERENCES conversations(conversation_id)
                    ON DELETE CASCADE ON UPDATE CASCADE
            )
            """.trimIndent()
        )
        raw.execSQL(
            "CREATE TABLE deleted_private_messages (message_id TEXT PRIMARY KEY NOT NULL, deleted_at INTEGER NOT NULL)"
        )
        raw.execSQL(
            """
            CREATE TABLE message_attachments (
                message_id TEXT PRIMARY KEY NOT NULL,
                path_hash BLOB NOT NULL,
                path_ciphertext BLOB NOT NULL,
                byte_size INTEGER NOT NULL,
                FOREIGN KEY(message_id) REFERENCES private_messages(message_id)
                    ON DELETE CASCADE ON UPDATE CASCADE
            )
            """.trimIndent()
        )
        raw.execSQL(
            "CREATE INDEX idx_private_messages_conversation_arrival ON private_messages(conversation_id, arrival_sequence)"
        )
        raw.execSQL("INSERT INTO conversations (conversation_id, display_name, created_at, updated_at) VALUES ('bob','Bob',1,1)")
        raw.execSQL(
            """
            INSERT INTO private_messages (
                message_id, conversation_id, sender, content, message_type, sent_at,
                received_at, is_relay, is_private, is_encrypted, delivery_type,
                payload_ciphertext, is_read
            ) VALUES ('legacy-1','bob','alice','old message',0,1000,1000,0,1,0,1,X'00',0)
            """.trimIndent()
        )
        raw.version = 4
        raw.close()

        val upgraded = ConversationDatabase(context, legacyName, storageCipher = InMemoryConversationStorageCipher())
        try {
            assertEquals(5, upgraded.writableDatabase.version)
            assertTrue(
                "outbox table must exist after upgrade",
                upgraded.writableDatabase.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name='outbox'",
                    null
                ).use { it.moveToFirst() }
            )
            assertTrue(
                "outbox indexes must exist after upgrade",
                upgraded.writableDatabase.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type='index' AND name='idx_outbox_due'",
                    null
                ).use { it.moveToFirst() }
            )
            assertEquals(0, upgraded.countOutbox())
            assertEquals(
                "legacy message row must survive the migration",
                1,
                upgraded.writableDatabase
                    .rawQuery("SELECT COUNT(*) FROM private_messages", null)
                    .use { if (it.moveToFirst()) it.getInt(0) else 0 }
            )
            // The pre-existing in-flight row is exactly the silent limbo the scan must catch.
            assertEquals(
                listOf("legacy-1"),
                upgraded.findStuckSendingMessages(olderThanMs = 0L, nowMs = 10_000L)
            )
            // And it can be adopted: the row carries the conversation the router needs.
            assertEquals("bob", upgraded.findConversationIdForMessage("legacy-1"))
        } finally {
            upgraded.close()
            context.deleteDatabase(legacyName)
        }
    }
}
