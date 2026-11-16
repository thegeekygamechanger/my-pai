package app.pocketagent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.pocketagent.LocalWide

/** The three reading pages reached from the side navigation. */
object Docs {
    const val TERMS = "terms"
    const val PRIVACY = "privacy"
    const val LICENSES = "licenses"

    fun title(kind: String) = when (kind) {
        TERMS -> "Terms of use"
        PRIVACY -> "Privacy"
        else -> "Open source"
    }

    fun text(kind: String): String = when (kind) {
        TERMS -> TERMS_TEXT
        PRIVACY -> PRIVACY_TEXT
        else -> LICENSES_TEXT
    }

    private val TERMS_TEXT = """
Pocket Agent is free, open-source software. By using it you agree to the following.

## Your keys and your costs
You connect the app to AI services with your own API keys. Each request you send is made under your account with that service, and that service bills you for it. Keep an eye on your usage there; the cost shown in the app is only an estimate.

## The AI services
Replies come from the service you chose (Anthropic, OpenAI, OpenRouter, Groq, Google and others). Their own terms and usage policies apply to what you send them and what they return. Pocket Agent is not affiliated with any of them.

## What Work mode does
In Work mode the model can read and write files in the app's workspace folder, keep memory notes, search the web and run code on the service's side. It asks before changing things unless you choose "Full auto". You are responsible for what you ask it to do and for checking its work.

## Answers can be wrong
AI models make mistakes and can state false things with confidence. Do not rely on a reply for medical, legal, financial or safety decisions without checking it with a qualified person or a trusted source.

## Your content
What you write and attach is yours. Do not use the app to break the law or to harm others.

## No warranty
The software is provided "as is", without warranty of any kind. To the extent the law allows, the authors are not liable for any loss or damage arising from its use. The full licence text is under Open source.

## Changes
These terms may change with a new version of the app. The current text is always here.
""".trimIndent()

    private val PRIVACY_TEXT = """
Pocket Agent has no accounts, no ads and no analytics. There is no Pocket Agent server that holds your data.

## What stays on your device
Your chats, attachments, workspace files, memory notes and settings are stored on this device, in the app's private storage. Your API keys and sync code are encrypted: on Android with a key held in the Android Keystore, on a computer with a key in a file only your user account can read. Nothing is included in cloud backups.

## What leaves your device
- **Your messages and attachments** go straight from the app to the AI service you chose, over HTTPS, so it can reply. That service handles them under its own privacy policy.
- **Web search and code sandbox**, when you switch them on, run on the AI service's side.
- **Weather**, only if you enter a city: the app asks Open-Meteo for the current weather there, to shape the greeting and the colours of the first screen. Open-Meteo sees the city and your IP address.
- **Update checks** ask GitHub whether a newer release exists. GitHub sees your IP address, as any website does.

## GitHub and Gmail
If you connect GitHub or Gmail, the token or app password is stored encrypted on your device like your other keys. In Work mode the assistant can then read from that account, and what it reads is sent to the AI service as part of the conversation. It asks you every time before it sends an email or changes anything on GitHub. Disconnect at any time in Settings, Connections, and revoke the token or app password in your GitHub or Google account.

## Scheduled tasks
A scheduled task starts a chat by itself at the time you set, on the device where you created it. It uses your keys and your connections exactly as a chat you start yourself.

## Sync between your devices
Sync is off until you turn it on. When it is on, your devices send each other your chats, files, settings and keys through a relay server. Everything is encrypted on the device with a key made from your pairing code, which never goes to the relay, so the relay cannot read any of it. The relay keeps nothing: no database, no files, no message log. It only passes each message to your other devices that are connected at that moment. Anyone who has your pairing code can read your synced data, so show it only to your own devices.

## Sharing a chat
When you share a chat with a code, that one chat is sent, encrypted, to whoever opens the code while your share screen is open. They receive a copy of that chat only.

## Deleting your data
Delete a chat from History, or everything from Settings, Data. Removing the app removes all of it from the device. To remove a key from an AI service itself, revoke it in that service's dashboard.

## Children
The app is not directed at children. The AI services set their own minimum ages.
""".trimIndent()

    private val LICENSES_TEXT = """
Pocket Agent is open source under the MIT licence. Its source code is at github.com/thegeekygamechanger/pocket-agent.

## MIT License
Copyright (c) 2026 Pocket Agent contributors

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

## Built with
- **Kotlin** and **kotlinx.coroutines**, **kotlinx.serialization** (JetBrains), Apache License 2.0
- **Jetpack Compose**, **Material 3**, **Room**, **DataStore**, **Biometric** and other AndroidX libraries (Google), Apache License 2.0
- **Compose Multiplatform** (JetBrains), Apache License 2.0, with **Skia** (BSD 3-Clause) on computers
- **Material Symbols and Icons** (Google), Apache License 2.0
- **Inter** typeface (The Inter Project Authors), SIL Open Font License 1.1
- **TeX Gyre Heros** typeface (GUST e-foundry), GUST Font License. It is the freely licensed face used for headings; Helvetica itself cannot be included in an app.
- **OkHttp** and **Okio** (Square), Apache License 2.0
- **ZXing** core, for QR codes, Apache License 2.0
- **SQLite** (public domain)
- Weather data by **Open-Meteo.com**, CC BY 4.0
- On computers, a bundled **OpenJDK** runtime, GPL v2 with the Classpath Exception

The Apache License 2.0 is at apache.org/licenses/LICENSE-2.0.

Product names such as Claude, OpenAI, Gemini and Groq belong to their owners and are used only to say which service a key is for.
""".trimIndent()
}

@Composable
fun DocScreen(kind: String, onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val wide = LocalWide.current
    val blocks = remember(kind) { Markdown.parse(Docs.text(kind)) }
    Column(Modifier.fillMaxSize().background(cs.surface).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(if (wide) Icons.Rounded.Close else Icons.AutoMirrored.Rounded.ArrowBack, if (wide) "Close" else "Back") }
            Text(Docs.title(kind), Modifier.padding(start = 6.dp), style = MaterialTheme.typography.titleLarge)
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxSize().navigationBarsPadding().padding(horizontal = 20.dp)) {
                itemsIndexed(blocks) { _, b -> MdBlockView(b, Modifier.padding(vertical = 6.dp)) }
                item { Box(Modifier.padding(bottom = 32.dp)) }
            }
        }
    }
}
