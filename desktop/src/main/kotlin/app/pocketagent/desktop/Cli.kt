package app.pocketagent.desktop

import app.pocketagent.ai.Providers
import app.pocketagent.data.Chat
import app.pocketagent.data.Msg
import app.pocketagent.sync.SyncCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess

/**
 * A window-less desktop instance for testing sync against a real phone:
 *   Cli <data dir> <seconds>   turns sync on, prints the code, adds one chat, then reports what it holds every few seconds.
 * The Claude key comes from the POCKETAGENT_TEST_KEY environment variable, if set, and is synced like any other.
 */
fun main(args: Array<String>) {
    runBlocking {
    val g = desktopGraph(DesktopPlatform(File(args.getOrElse(0) { "build/cli-data" }).apply { mkdirs() }))
    g.start()
    while (g.settings.value?.deviceId.isNullOrEmpty()) delay(50)
    System.getenv("POCKETAGENT_TEST_KEY")?.takeIf { it.isNotBlank() }?.let { g.vault.write(Providers.vaultKey(Providers.ANTHROPIC), it) }
    g.updateShared { it.copy(name = "Sam", onboarded = true) }
    val code = g.sync.enable()
    println("CODE " + SyncCode.pretty(code))
    if (g.dao.allChats().none { it.title == "From the computer" }) {
        val now = System.currentTimeMillis()
        val id = g.dao.insert(Chat(title = "From the computer", createdAt = now, updatedAt = now, model = "claude-haiku-5-5", effort = "low"))
        g.dao.insert(Msg(chatId = id, role = "user", content = """[{"type":"text","text":"Hello from the desktop"}]""", createdAt = now))
        g.dao.insert(Msg(chatId = id, role = "assistant", content = """[{"type":"text","text":"This chat was written on the computer and synced to the phone."}]""", createdAt = now + 1))
    }
    val end = System.currentTimeMillis() + (args.getOrNull(1)?.toLongOrNull() ?: 120) * 1000
    while (System.currentTimeMillis() < end) {
        delay(3000)
        val chats = g.dao.allChats().filter { it.title.isNotBlank() }
        val parts = ArrayList<String>()
        for (c in chats) parts += c.title.take(30) + "(" + g.dao.messages(c.id).size + ")"
        println("STATE peers=${g.sync.status.value.peers.size} devices=${g.settings.value?.devices?.count { !it.removed }} live=${g.sync.remoteRuns.value.size} chats=" + parts.joinToString(" | "))
    }
    }
    exitProcess(0)
}
