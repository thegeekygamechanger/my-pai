package app.pocketagent.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "chats", indices = [Index("uid", unique = true)])
data class Chat(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The same on every linked device; [id] is only this device's row number. */
    val uid: String = "",
    /** When this row last changed, on the device that changed it. The newer copy wins when devices sync. */
    val rev: Long = 0,
    val title: String = "",
    val preview: String = "",
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean = false,
    /** false = normal chat, true = agent mode */
    val agent: Boolean = false,
    /** Which service the model belongs to: a [app.pocketagent.ai.Provider] id. */
    val provider: String = "anthropic",
    val model: String,
    val effort: String,
    val web: Boolean = true,
    val code: Boolean = false,
    val memory: Boolean = true,
    val fast: Boolean = false,
    /** "ask", "auto_read" or "full_auto" */
    val permission: String = "auto_read",
    val inTok: Long = 0,
    val outTok: Long = 0,
    val cacheReadTok: Long = 0,
    val cacheWriteTok: Long = 0,
    val costMicros: Long = 0,
    /** Hash of the system prompt and tool set the stored reasoning blocks belong to. */
    val prefixFp: String = "",
    /** The agent's current checklist, as the JSON it last sent. */
    val tasksJson: String = "",
)

@Entity(
    tableName = "messages",
    indices = [Index("chatId"), Index("uid", unique = true)],
    foreignKeys = [ForeignKey(entity = Chat::class, parentColumns = ["id"], childColumns = ["chatId"], onDelete = ForeignKey.CASCADE)],
)
data class Msg(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uid: String = "",
    val rev: Long = 0,
    val chatId: Long,
    /** "user" and "assistant" are sent to the service; "note" is shown on screen only. */
    val role: String,
    /** user/assistant: the JSON array of content blocks. note: plain text. */
    val content: String,
    /** Also the order of the conversation: strictly increasing within a chat. */
    val createdAt: Long,
    /** The service's id for a reply, used to line a streaming reply up with its saved copy. */
    val apiId: String = "",
    val model: String = "",
    /** assistant: the stop reason. note: "error" or "info". */
    val stopReason: String = "",
    val inTok: Long = 0,
    val outTok: Long = 0,
)

/** Remembers that something was deleted, so a linked device that still has it does not bring it back. */
@Entity(tableName = "tombs")
data class Tomb(@PrimaryKey val uid: String, val kind: String, val at: Long)

data class UsageTotals(val inTok: Long?, val outTok: Long?, val cacheReadTok: Long?, val costMicros: Long?, val chats: Int)
data class Stamp(val uid: String, val rev: Long)

@Dao
interface ChatDao {
    @Query("SELECT * FROM chats ORDER BY pinned DESC, updatedAt DESC")
    fun chats(): Flow<List<Chat>>

    @Query("SELECT DISTINCT c.* FROM chats c LEFT JOIN messages m ON m.chatId = c.id AND m.role != 'note' WHERE c.title LIKE :q OR m.content LIKE :q ORDER BY c.pinned DESC, c.updatedAt DESC LIMIT 200")
    fun search(q: String): Flow<List<Chat>>

    @Query("SELECT * FROM chats WHERE id = :id")
    fun chatFlow(id: Long): Flow<Chat?>

    @Query("SELECT * FROM chats WHERE id = :id")
    suspend fun chat(id: Long): Chat?

    @Query("SELECT * FROM chats WHERE uid = :uid")
    suspend fun chatByUid(uid: String): Chat?

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY createdAt, id")
    fun messagesFlow(chatId: Long): Flow<List<Msg>>

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY createdAt, id")
    suspend fun messages(chatId: Long): List<Msg>

    @Query("SELECT * FROM messages WHERE uid = :uid")
    suspend fun msgByUid(uid: String): Msg?

    @Query("SELECT uid, rev FROM messages WHERE chatId = :chatId")
    suspend fun msgStamps(chatId: Long): List<Stamp>

    @Query("SELECT MAX(createdAt) FROM messages WHERE chatId = :chatId")
    suspend fun lastTime(chatId: Long): Long?

    @Query("SELECT * FROM chats ORDER BY updatedAt DESC")
    suspend fun allChats(): List<Chat>

    @Insert suspend fun insert(chat: Chat): Long
    @Update suspend fun update(chat: Chat)
    @Insert suspend fun insert(msg: Msg): Long
    @Update suspend fun update(msg: Msg)

    @Query("DELETE FROM chats WHERE id = :id") suspend fun deleteChat(id: Long)
    @Query("DELETE FROM chats") suspend fun deleteAllChats()
    /** Removes a message and everything after it in the conversation. */
    @Query("DELETE FROM messages WHERE chatId = :chatId AND createdAt >= :fromTime") suspend fun deleteFrom(chatId: Long, fromTime: Long)
    @Query("SELECT uid FROM messages WHERE chatId = :chatId AND createdAt >= :fromTime") suspend fun uidsFrom(chatId: Long, fromTime: Long): List<String>
    @Query("DELETE FROM messages WHERE id = :id") suspend fun deleteMsg(id: Long)

    @Query("UPDATE chats SET title = :title WHERE id = :id") suspend fun rename(id: Long, title: String)
    @Query("UPDATE chats SET pinned = :pinned WHERE id = :id") suspend fun pin(id: Long, pinned: Boolean)
    @Query("UPDATE chats SET tasksJson = :json WHERE id = :id") suspend fun setTasks(id: Long, json: String)
    @Query("UPDATE chats SET prefixFp = :fp WHERE id = :id") suspend fun setFingerprint(id: Long, fp: String)
    @Query("UPDATE chats SET updatedAt = :now, preview = :preview WHERE id = :id") suspend fun touch(id: Long, now: Long, preview: String)
    @Query("UPDATE chats SET rev = :rev WHERE id = :id") suspend fun stamp(id: Long, rev: Long)

    @Query("UPDATE chats SET inTok = inTok + :i, outTok = outTok + :o, cacheReadTok = cacheReadTok + :cr, cacheWriteTok = cacheWriteTok + :cw, costMicros = costMicros + :cost WHERE id = :id")
    suspend fun addUsage(id: Long, i: Long, o: Long, cr: Long, cw: Long, cost: Long)

    @Query("SELECT SUM(inTok) AS inTok, SUM(outTok) AS outTok, SUM(cacheReadTok) AS cacheReadTok, SUM(costMicros) AS costMicros, COUNT(*) AS chats FROM chats")
    fun totals(): Flow<UsageTotals>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putTomb(tomb: Tomb)
    @Query("SELECT * FROM tombs") suspend fun tombs(): List<Tomb>
    @Query("SELECT * FROM tombs WHERE uid = :uid") suspend fun tomb(uid: String): Tomb?
    @Query("DELETE FROM tombs") suspend fun clearTombs()
}

@Database(entities = [Chat::class, Msg::class, Tomb::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): ChatDao
}

/**
 * The database as the rest of the app sees it. Every change made here is given a global id and a
 * timestamp and reported to [onChange], which is how linked devices hear about it. Changes that
 * arrive from another device are written to [inner] directly, so they are not echoed back.
 */
class TrackedDao(val inner: ChatDao, private val onChange: (Change) -> Unit) : ChatDao by inner {
    private fun now() = System.currentTimeMillis()
    private fun uid() = java.util.UUID.randomUUID().toString()

    private suspend fun chatChanged(id: Long) {
        inner.stamp(id, now())
        onChange(Change.ChatSaved(id))
    }

    override suspend fun insert(chat: Chat): Long {
        val id = inner.insert(chat.copy(uid = chat.uid.ifEmpty { uid() }, rev = now()))
        onChange(Change.ChatSaved(id))
        return id
    }
    override suspend fun update(chat: Chat) { inner.update(chat.copy(rev = now())); onChange(Change.ChatSaved(chat.id)) }
    override suspend fun rename(id: Long, title: String) { inner.rename(id, title); chatChanged(id) }
    override suspend fun pin(id: Long, pinned: Boolean) { inner.pin(id, pinned); chatChanged(id) }
    override suspend fun setTasks(id: Long, json: String) { inner.setTasks(id, json); chatChanged(id) }
    override suspend fun setFingerprint(id: Long, fp: String) { inner.setFingerprint(id, fp); chatChanged(id) }
    override suspend fun touch(id: Long, now: Long, preview: String) { inner.touch(id, now, preview); chatChanged(id) }
    override suspend fun addUsage(id: Long, i: Long, o: Long, cr: Long, cw: Long, cost: Long) { inner.addUsage(id, i, o, cr, cw, cost); chatChanged(id) }

    override suspend fun insert(msg: Msg): Long {
        // The conversation's order is its timestamps, so a new message is always later than the last one.
        val time = maxOf(msg.createdAt, (inner.lastTime(msg.chatId) ?: 0) + 1)
        val id = inner.insert(msg.copy(uid = msg.uid.ifEmpty { uid() }, rev = now(), createdAt = time))
        onChange(Change.MsgSaved(id, msg.chatId))
        return id
    }
    override suspend fun update(msg: Msg) { inner.update(msg.copy(rev = now())); onChange(Change.MsgSaved(msg.id, msg.chatId)) }

    override suspend fun deleteChat(id: Long) {
        val chat = inner.chat(id)
        inner.deleteChat(id)
        if (chat != null && chat.uid.isNotEmpty()) { inner.putTomb(Tomb(chat.uid, "chat", now())); onChange(Change.Deleted(listOf(chat.uid), "chat")) }
    }
    override suspend fun deleteAllChats() {
        val uids = inner.allChats().map { it.uid }.filter { it.isNotEmpty() }
        inner.deleteAllChats()
        val at = now()
        uids.forEach { inner.putTomb(Tomb(it, "chat", at)) }
        if (uids.isNotEmpty()) onChange(Change.Deleted(uids, "chat"))
    }
    override suspend fun deleteFrom(chatId: Long, fromTime: Long) {
        val uids = inner.uidsFrom(chatId, fromTime).filter { it.isNotEmpty() }
        inner.deleteFrom(chatId, fromTime)
        val at = now()
        uids.forEach { inner.putTomb(Tomb(it, "msg", at)) }
        if (uids.isNotEmpty()) onChange(Change.Deleted(uids, "msg"))
    }
}

/** Something this device just changed, for the sync engine to pass on. */
sealed interface Change {
    data class ChatSaved(val id: Long) : Change
    data class MsgSaved(val id: Long, val chatId: Long) : Change
    data class Deleted(val uids: List<String>, val kind: String) : Change
}
