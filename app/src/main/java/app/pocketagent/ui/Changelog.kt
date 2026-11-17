package app.pocketagent.ui

// Written by scripts/site.py from release-notes/. Do not edit by hand.

/** What changed in each version, newest first. */
internal val CHANGELOG_TEXT = """
## Version 1.1.0
- **MCP servers.** In Settings, Connections you can now add MCP servers: tools from other services, added by their address, with a token only if the server needs one. On a computer a server can also be a program on that computer. You choose which servers, and which of their tools, are switched on. The assistant asks before every call and shows the server, the tool and exactly what it will send. An MCP server is a third party and receives what is sent to it, so add only ones you trust. Tokens stay encrypted on the device they were typed on. It works with every AI service, Ollama included, and still needs no account.
- **@mcp in any chat.** Type @mcp, the same way as @github or @gmail, and your servers are listed above the message box. One that is answering says Connected; one that is not has a button to connect or reconnect it. Settings shows the same mark beside each server.
- **Read a file while it is being written.** When the assistant writes a file, its text now appears in the chat as it is written, so you can scroll and read. If you press Stop half-way, what had been written is kept in the chat instead of being thrown away (the half-finished file is not saved over a whole one).
- **Save files from Chat too.** Ask in an ordinary chat to "save this as notes.md" or "make a PDF of that" and it is saved to your Files, the same as in Work. Text files work with every AI service; PDFs, Word and Excel files and other kinds need a Claude key.
- **Download and Share in Files.** Every file has a Download button that puts a copy in your Downloads folder, and a Share button (on a computer it shows the file in its folder).
- **An even message bar.** The attach, microphone and send icons are one size and sit the same distance from the edges.
- **Shorter words.** Headings and the lines under them were trimmed across Settings, Connections, Sync and sharing.
- **What's new, in the app.** Settings, About has a What's new page with every version's changes. The website has the same list and a page on how My PAI compares with other open-source apps.

## Version 1.0.4
- **The microphone matches the other icons.** It is now a bare outlined icon the same size as the send arrow. While it listens it turns into moving bars in the theme's colour instead of filling a circle.
- **No more round grounds behind icon buttons.** The close and back button in Settings, the + in Scheduled tasks and in History, and the arrows on long rows of choices are plain icons now, like the ones in the chat.
- **An opening screen.** On Android, Windows and Linux the app now opens with its mark turning into place on the app's own colours, then its name, before lifting away to the chat. It lasts about a second and a half and is shown once per start.

## Version 1.0.3
- **Lighter buttons.** Send and Stop are now small bare icons with no circle behind them: an arrow in the theme's colour, and a grey stop square while a reply is being written (it still stops the reply; nothing turns red).
- **No rings in the top corner.** The + and three-dot buttons are plain icons that sit directly on the chat's colours.
- **A dot for replies you have not seen.** When a scheduled task such as your Morning news brief finishes, or a reply arrives while you are in another chat or another app, that chat gets a small dot in the side menu and in History. It goes as soon as you open the chat. On a phone the menu button shows a dot too, and History has an Unread filter.
- **A lighter app on Windows and Linux.** The computer app now runs with a leaner Java set-up. Measured on Linux, idle on a chat: total memory about 265 MB instead of 365 MB, and about a third of the processor work it used to do while sitting idle (the slow background colours and the turning mark redraw about thirty times a second, and rest while the window is behind another). Start time is unchanged.

## Version 1.0.2
- **Incognito chat.** Open the new three-dot menu beside + and choose Incognito chat. It is in no history list and no search, is never sent to your linked devices, cannot be shared, writes no memory notes, and is erased the moment you leave it (or the next time the app starts, if it was closed while open). The AI service you use still receives the messages, as with any chat.
- **A three-dot menu in every chat.** Share with a code moved there, next to Incognito chat and Model and tools. The + button, the menu button and the menu itself take their colours from the theme you chose, light or dark.
- **Filters in History.** New, Old, In progress, Last 24 hours, Pinned, Chat and Work, in a row under the search box.
- **A living mark.** The mark beside the name in the side menu turns slowly and its spark breathes; the name is set in the app's Helvetica.

## Version 1.0.1
- **A cleaner first screen.** The suggested prompts in the middle of an empty chat are gone, in Chat and in Work: just the greeting and the message box.
- **The + button waits until there is something to leave.** On an empty chat it is dimmed, because that chat is already a new one. Once a message has been sent it starts a fresh chat as before.
- **Ollama.** Add Ollama as a service and chat with models that run on your own computer: no key, just the address it listens on (already filled in on a computer; on a phone, your computer's address on the same Wi-Fi). Tools in Work depend on the model you run.
- **Arrows on long rows of choices.** On a computer, a row with more choices than fit, such as the list of AI services, has an arrow at each end to move along it.

## Version 1.0.0
My PAI is your personal AI: one free, open-source app for Android, Windows and Linux that works with your own API keys. No ads, no subscription, no account.

- **Chat and Work.** Ask anything in Chat. In Work, hand over a task and it goes step by step with files in a private workspace, the web, and your GitHub and Gmail if you connect them, asking before it changes anything.
- **Makes and converts files.** With a Claude key, Work turns notes into a PDF, builds Word and Excel files, and draws charts and diagrams, and saves them to your workspace.
- **Your own keys.** Claude, OpenAI, Gemini, OpenRouter, Groq, Mistral, DeepSeek and other compatible services. On Auto, each message goes to the model that suits it.
- **Your chats in the side menu**, with a long-press menu to pin, rename, share or delete.
- **Share a chat with a four-character code**, and sync between your devices, end to end encrypted through a relay that stores nothing.
- **Scheduled tasks** that run by themselves and notify you.
- **Voice typing, app lock, light and dark themes.**
- **Send feedback** from Settings.

This is the first release under the name My PAI. It installs as a new app: an earlier build under the old name is not updated by it and can be removed.
""".trimIndent()
