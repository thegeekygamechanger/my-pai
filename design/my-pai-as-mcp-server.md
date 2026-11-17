# Design and security review: My PAI as an MCP server

Status: built in 1.1.1, on computers only. Code: `app/src/main/java/app/pocketagent/ai/McpHost.kt`. Checked by `PowerTest`.

## What it is for

Other agents on the same computer (an editor, a command-line assistant) can read what My PAI holds for the person:
workspace files, memory notes and the list of scheduled tasks. It lets those tools pick up where My PAI left off
without copying files around.

## What it offers

Five tools, all read-only: `list_files`, `read_file`, `list_memory`, `read_memory`, `list_tasks`. There is no tool
that writes, deletes, sends, runs anything or reads a chat, a key or a setting. MCP resources, prompts and sampling
are not offered.

## Rules it keeps

- **Off by default.** It runs only after the person turns on "Let them read My PAI" in Settings, Connections, and
  stops the moment that is turned off or the app closes.
- **This computer only.** It listens on the loopback address (127.0.0.1), never on the network, and refuses a
  request whose Host is not this computer. Phones do not serve at all.
- **A token, not an account.** A random 192-bit token is made on the device and kept encrypted with the other
  secrets. A request without it gets nothing. The person can replace it at any time. There is no sign-in of any kind.
- **Web pages are refused.** A request with an Origin header (which browsers add and other programs do not) is
  turned away even with the right token, so a page open in a browser cannot reach in.
- **Every call asks.** The app shows what the program wants ("read the file notes/plan.md") with Deny, Allow once
  and For 10 minutes. No answer in a minute counts as no. Nothing is ever allowed for good.
- **Inside the folders only.** Paths are resolved by the same code as the app's own file tools, which refuse
  anything outside the workspace or the memory folder.
- **Logged.** Each call is in the activity log, marked as asked by another program.
- **Bounded.** A request body is at most 1 MB, a result at most 100,000 characters, four connections at a time.

## Protocol

Streamable HTTP, one POST per request, JSON answers. It answers the 2026-07-28 revision (no handshake, no
sessions) and also the `initialize` handshake of the 2025 revisions, so older clients work. The server is a few
dozen lines on a plain socket: nothing new is bundled with the app.

## Risks that remain, stated plainly

- **Any program running as the person can read the token if it can read the app's key file**, as it could already
  read the app's data. The server does not make such a program stronger; the question on screen still stands in
  its way.
- **"For 10 minutes" trusts every caller with the token for that time**, not just the program that asked. It is a
  convenience the person chooses each time.
- **What is read leaves My PAI.** A file handed to another agent is then in that agent's hands and goes wherever it
  sends it (often to an AI service). The question on screen says a program is asking; it cannot say what the
  program will do next.
- **The question needs the app in front.** If the window is hidden the request waits a minute and is refused.

## Decisions for the owner

- Should there be a setting for which folders are offered, so a part of the workspace can be kept back?
- Should phones serve too? Today they do not, because nothing else on a phone could ask.
