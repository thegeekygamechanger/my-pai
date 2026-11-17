# My PAI

Your personal AI. Chat with it, or hand it work and let it get on with it. For Android, Windows and Linux, with your own API keys.

**[Download the latest release](https://github.com/thegeekygamechanger/my-pai/releases/latest)**

No account, ever: there is no sign-in, no email, no phone number and no tracking. Devices are linked only by a code or a QR picture.

## What it does

- **Your own keys.** Claude, OpenAI, OpenRouter, Groq, Gemini, Mistral, DeepSeek, Together, Fireworks, Cerebras, xAI, NVIDIA, Hugging Face, or any OpenAI-compatible endpoint. Or no key at all: point it at Ollama and use models that run on your own computer. No account with us, no ads, no tracking.
- **Chat and Work.** In Work mode the model works in steps with tools: files in a private workspace, memory notes, a task list, web search, GitHub and Gmail if you connect them, and (Claude models) a sandbox where it runs code to make and convert files: Markdown to PDF, a Word or Excel file, a chart or a diagram. What it makes there is saved into your workspace and shown in the chat. It asks before it changes anything. An ordinary chat can save a file too: ask it to "save this as notes.md" or "make a PDF of that". A file being written shows in the chat as it grows, and Stop keeps what was written so far.
- **MCP servers.** Add tools from other services by their address (Settings, Connections), with a pasted token only if the server needs one; on a computer a server can also be a local program. Type `@mcp` in a chat to pick one, the same way as `@github` and `@gmail`. You switch servers and single tools on or off, and every call is shown to you (server, tool, arguments) for a yes or no first. A server is a third party and receives what is sent to it. It speaks the 2026-07-28 revision of the Model Context Protocol and falls back to the handshake of the 2025 revisions.
- **In control.** Every call that changes or sends something is shown first, with what it would change; allow it once or for the task in hand, never for good. A file change can be undone from its step in the chat. Text a tool brings back that reads like orders to the assistant is flagged and never acted on silently. Settings, Activity lists every tool call, and each chat shows what it has used.
- **Your computer's tools on your phone.** On a computer, lend the servers you mark to your linked devices: the phone calls them through the same encrypted pairing as sync. With sync on, the names and addresses of your MCP servers follow you to every device; tokens never travel.
- **My PAI as an MCP server.** On a computer, other agents on that computer can read your files, memory notes and tasks if you turn it on: read-only, loopback only, with a token, and every request asks you.
- **Routines, a backup model, offline mode.** A scheduled task can run several steps one after another. A backup model takes over when a service fails or hits a limit, and says so. Offline mode keeps everything on your device and your own network.
- **Share a setup.** Hand your servers, routines and instructions to someone with a four-character code; tokens and keys are never included.
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

## Contributing

Problems, ideas and pull requests are welcome. Start with [CONTRIBUTING.md](CONTRIBUTING.md): it has the rules the app keeps (no accounts, nothing stored on a server, ask before acting), how to build and test, and how to send a change. Questions go to [Discussions](https://github.com/thegeekygamechanger/my-pai/discussions), security problems to [SECURITY.md](SECURITY.md), and everyone follows the [Code of Conduct](CODE_OF_CONDUCT.md). The people who made it are in [CONTRIBUTORS.md](CONTRIBUTORS.md).

The reasoning and the remaining risks of the larger features are written down in [`design/`](design): the [bridge between your own devices](design/phone-to-computer-bridge.md), [My PAI as an MCP server](design/my-pai-as-mcp-server.md) and [syncing the list of MCP servers](design/sync-of-mcp-servers.md).

What changed in each version is on the [What's new](https://thegeekygamechanger.github.io/my-pai/whats-new.html) page, and [How it compares](https://thegeekygamechanger.github.io/my-pai/compare.html) sets My PAI beside other open-source apps.

## Licence

MIT. See [LICENSE](LICENSE) and [NOTICE](NOTICE) for bundled fonts and libraries.

My PAI is not affiliated with Anthropic, OpenAI, Google or any other AI service; their product names belong to their owners.
