# My PAI

Your personal AI. Chat with it, or hand it work and let it get on with it. For Android, Windows and Linux, with your own API keys.

**[Download the latest release](https://github.com/thegeekygamechanger/my-pai/releases/latest)**

## What it does

- **Your own keys.** Claude, OpenAI, OpenRouter, Groq, Gemini, Mistral, DeepSeek, Together, Fireworks, Cerebras, xAI, NVIDIA, Hugging Face, or any OpenAI-compatible endpoint. Or no key at all: point it at Ollama and use models that run on your own computer. No account with us, no ads, no tracking.
- **Chat and Work.** In Work mode the model works in steps with tools: files in a private workspace, memory notes, a task list, web search, GitHub and Gmail if you connect them, and (Claude models) a sandbox where it runs code to make and convert files: Markdown to PDF, a Word or Excel file, a chart or a diagram. What it makes there is saved into your workspace and shown in the chat. It asks before it changes anything.
- **Incognito chat.** A chat that is in no history, is never synced or shared, and is erased when you leave it.
- **Pictures and videos, with your own key.** With a Gemini, OpenAI or Pollinations key added, ask for a picture in any chat; short videos need a Gemini or Pollinations key. With none of those keys the feature is not shown at all.
- **Keeps working.** Several chats and pieces of work can run at once and carry on when you switch apps.
- **Scheduled tasks.** Have a chat or a piece of work run by itself at a set time.
- **Voice typing** (on computers it works offline, with no key), a greeting that knows the hour and your weather, light and dark themes.
- **Every device.** The same app on phone and computer, kept in step by end-to-end encrypted sync: pair by QR code, up to five devices. A single chat can be shared with a four-character code.
- **Updates itself** from this repository's releases.

## Where your data is

Chats, files and settings stay on your devices. Keys are encrypted (Android Keystore on phones; an owner-only key file on computers). Requests go straight from the app to the AI service you chose.

Sync passes through a small relay (`server/relay.mjs`) that stores nothing: no database, no files, no message log. Everything is encrypted on the device with a key derived from your pairing code, which never reaches the relay. See [Privacy](docs/privacy.html) and [Terms](docs/terms.html).

## Build

Needs JDK 21 and, for Android, the Android SDK.

```bash
./gradlew :app:assembleDebug            # Android
./gradlew :desktop:run                  # Linux or Windows, from source
./gradlew :desktop:createDistributable  # a self-contained desktop app
./gradlew :app:testDebugUnitTest        # tests
```

The Android app and the desktop app are the same source files (`app/src/main/java`); only what differs between a phone and a computer lives behind the `Platform` interface (`AndroidApp.kt`, `desktop/`).

Sync can be checked end to end with simulated devices:

```bash
PORT=18790 node server/relay.mjs &
./gradlew :desktop:syncCheck
```

To run your own relay, start `server/relay.mjs` (plain Node, no dependencies) behind HTTPS and build with `-Dpocketagent.relay=https://your.host` on the desktop, or change `SyncEngine.RELAY`.

The same relay can pass on the notes people write under Settings, Send feedback. That is off unless the relay is given an email service key and a mailbox in its environment (`BREVO_API_KEY`, `FEEDBACK_TO`, `FEEDBACK_FROM`, described at the top of `server/relay.mjs`); the app only shows the form when the relay says it is on. A note is handed to the email service and forgotten, and the mailbox address is in no reply and in no build of the app.

## Licence

MIT. See [LICENSE](LICENSE) and [NOTICE](NOTICE) for bundled fonts and libraries.

My PAI is not affiliated with Anthropic, OpenAI, Google or any other AI service; their product names belong to their owners.
