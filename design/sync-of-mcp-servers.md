# Design note: syncing the list of MCP servers

Status: built in 1.1.1. Code: `McpSync` in `ai/Mcp.kt`, `Graph.mergeMcp`, and two lines in `sync/SyncEngine.kt`.
Checked by `PowerTest` and `./gradlew :desktop:syncCheck`.

## What travels

For each MCP server that is reached by an address: its name, its address, whether it has a token (yes or no), and
when it was last changed. Also the ids of servers that were removed, and when.

## What never travels

- **Tokens.** A token stays in the vault of the device it was typed on. A server that arrives from another device
  is marked "needs its token typed in here".
- **Programs.** A server that is a program on a computer (its command line) belongs to that computer.
- **Tools and switches.** Each device asks the server for its tools itself and keeps its own on and off switches.

## How a server arrives

Listed, switched off, with no tools. Nothing is contacted until the person connects it on that device. A changed
address also switches the server off there until it is connected again.

## The change to SyncEngine.kt

The list rides inside the message that already carries shared settings (`set`), which is sealed like everything
else, as one more field (`mcp`). On receipt it is merged before the rest of that message is looked at. The merge is
per server: the newer copy of each server wins, and a removal wins over any older copy. If the receiving device
turns out to hold a server or a removal the sender did not know of, it sends the settled list back. Older versions
of the app ignore the field.
