# Pocket Agent

Chat with AI, or hand it work and let it get on with it. For Android, Windows and Linux, with your own API keys.

**[Download the latest release](https://github.com/thegeekygamechanger/pocket-agent/releases/latest)**

## What it does

- **Your own keys.** Claude, OpenAI, OpenRouter, Groq, Gemini, Mistral, DeepSeek, Together, Fireworks, Cerebras, xAI, NVIDIA, Hugging Face, or any OpenAI-compatible endpoint. No account with us, no ads, no tracking.
- **Chat and Work.** In Work mode the model works in steps with tools: files in a private workspace, memory notes, a task list, web search and a code sandbox (Claude models), GitHub and Gmail if you connect them. It asks before it changes anything, and always before something leaves your device (an email, a change on GitHub).
- **Pictures and videos.** Ask for a picture in any chat: it is made with your Gemini, OpenAI or Pollinations key, or, with none of those, by Pollinations' free open-source service. Short videos need a Gemini or Pollinations key.
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

## Licence

MIT. See [LICENSE](LICENSE) and [NOTICE](NOTICE) for bundled fonts and libraries.

Pocket Agent is not affiliated with Anthropic, OpenAI, Google or any other AI service; their product names belong to their owners.
