# Contributing to My PAI

Thank you for wanting to help. My PAI is a small project, so a short, clear change is the easiest kind to review and merge.

## Ways to help

- **Report a problem.** Open an [issue](https://github.com/thegeekygamechanger/my-pai/issues/new/choose) with what you did, what you expected and what happened, the app version (Settings, About) and the device.
- **Suggest something.** Start a thread in [Discussions](https://github.com/thegeekygamechanger/my-pai/discussions) first if the idea is large, so nobody builds something that cannot be merged.
- **Fix or build something.** Issues marked `good first issue` are a good place to start. Say in the issue that you are taking it.
- **Security problems** are reported privately, never in a public issue. See [SECURITY.md](SECURITY.md).

## Rules the app keeps

A change that breaks one of these will not be merged, however useful it is otherwise.

1. **No accounts.** No sign-in, sign-up, email, phone number, user id, analytics or tracking. Devices connect only by a pairing code or QR picture.
2. **The person's data stays on their devices.** The relay stores nothing: no database, no files, no log of messages. Do not add a server that holds user data.
3. **Secrets stay secret.** API keys, tokens and passwords are encrypted through `Vault`, and never appear in a log, an export, a backup or an error message. MCP server tokens are never synced.
4. **Ask before acting.** Anything that changes data or leaves the device (an email, a change on GitHub, a call to an MCP server) is shown to the person and needs a yes first.
5. **What a tool returns is information, not instructions.** Text from a web page, a file or an MCP server must never be treated as an order to the app.
6. **No secrets in the repository.** No keys, tokens, keystores, real email addresses or phone numbers, in code, tests, pictures or commit messages.

## Building and testing

You need JDK 21 and, for Android, the Android SDK.

```bash
./gradlew :app:assembleDebug            # Android
./gradlew :desktop:run                  # Linux or Windows, from source
./gradlew :app:testDebugUnitTest        # unit tests
PORT=18790 node server/relay.mjs &      # then, to check sync end to end:
./gradlew :desktop:syncCheck
```

The Android app and the desktop app are the same source files (`app/src/main/java`). Only what differs between a phone and a computer lives behind the `Platform` interface (`AndroidApp.kt` and `desktop/`), so a change usually has to work on both.

| Where | What |
| --- | --- |
| `app/src/main/java/app/pocketagent/ai` | Talking to AI services, tools, MCP, the run loop |
| `app/src/main/java/app/pocketagent/ui` | Every screen (Jetpack Compose) |
| `app/src/main/java/app/pocketagent/data` | The database, settings, the encrypted vault |
| `app/src/main/java/app/pocketagent/sync` | End-to-end encrypted sync and chat sharing |
| `desktop/` | What only computers need |
| `server/relay.mjs` | The relay (plain Node, no dependencies) |
| `scripts/site.py` | Builds the website in `docs/` and the in-app What's new page |

## Sending a pull request

- Keep it to one thing. Small pull requests are reviewed quickly; large ones wait.
- Add or update a test for what you changed (`app/src/test`, in the style of `RunLoopTest` and `McpTest`). Say in the pull request what you ran and what you could not test.
- Words on screen are plain and short. No jargon the person using the app would have to look up.
- Comments say why, not what.
- The pages in `docs/` and `ui/Changelog.kt` are generated: change `ui/Docs.kt`, `release-notes/` or `scripts/site.py` and run `python3 scripts/site.py`.
- Do not bump the version or edit release notes for a release; the maintainer does that.
- A new library needs a reason, a licence that fits MIT (Apache 2.0, BSD, MIT and similar), and a line in [NOTICE](NOTICE).

## Licence of your contribution

My PAI is under the [MIT licence](LICENSE). By sending a pull request you confirm that you wrote the change yourself, or have the right to submit it, and that you agree to it being published under the same licence. Do not paste code from a project whose licence does not allow that, and do not submit text or code you are not allowed to share.

## Being decent

Everyone taking part is expected to follow the [Code of Conduct](CODE_OF_CONDUCT.md).
