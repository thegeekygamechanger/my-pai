# Design and security review: the bridge between your own devices

Status: built in 1.1.1. Code: `app/src/main/java/app/pocketagent/sync/Bridge.kt`. Checked by `./gradlew :desktop:syncCheck`.

## What it is for

Some tools only exist on a computer: a program run as an MCP server (files, a database, a build tool), or a server
whose token the person keeps on the computer alone. The bridge lets the person's phone, or another of their linked
devices, call those tools while My PAI is open on the computer.

## How it works

1. Nothing is lent by default. On the computer the person turns on "Lend tools to my linked devices" and marks,
   server by server, which ones are lent.
2. The computer tells its linked devices which tools it lends: server name, tool name, description and input schema.
   No token, no command line, no address.
3. On the asking device those tools appear to the model as `pc_<server>_<tool>`. They are outside tools, so every
   call is shown to the person first with the computer, the tool and the arguments ("For this task" is offered, as
   for MCP servers; nothing is ever allowed for good).
4. An approved call travels to the computer, which runs it through its own MCP client and sends back the text.

## What it rides on

The pairing that sync already uses: one room on the relay, every message sealed on the device with a key derived
from the pairing code, which never reaches the relay. The bridge adds four message kinds inside that sealed room
(`bt` what is lent, `bq` what do you lend, `bc` a call, `br` its result). It needs no change to the relay and no
new way in: no port is opened on the computer, no account exists, and the relay still stores nothing.

## Threats and what answers them

| Threat | Answer |
| --- | --- |
| Someone on the network or at the relay reads or alters a call | They see only sealed messages. A message that does not open with the room's key is dropped. |
| A recorded call is sent again later | Each call has a random id that is accepted once, and a time; a call older than two minutes is refused. |
| A linked device asks for something that was not lent | The computer checks every call: bridge on, server lent and on, tool on. Anything else is refused with a reason. |
| The asking device is used to run arbitrary commands | A call names a lent server and one of its tools. There is no message that carries a command, a path or an address. |
| The model on the phone is tricked (by a web page, a file) into calling a tool | The person sees every call before it runs. A result that reads like instructions is flagged by the shield, after which nothing runs unasked in that task. |
| A flood of calls | No more than 30 a minute are run; results are cut at 100,000 characters. |
| The person forgets what was done from where | Every bridged call is written to the computer's activity log with the device that asked. |
| Offline mode | With offline mode on, a device neither offers nor uses bridged tools. |

## Risks that remain, stated plainly

- **Whoever holds the pairing code is trusted.** A device that has the code is, by design, one of the person's own:
  it can already read every synced chat, file and key. With the bridge on, it can also call the lent tools. This is
  why the bridge is off until asked for, and why the code must only ever be shown to the person's own devices.
- **The approval happens on the asking device, not on the computer.** That is the point (the person is holding the
  phone), but it means the computer runs a lent tool without a second question. The lent set is therefore opt-in per
  server, and visible and revocable in one place.
- **A lent tool does whatever its server does.** Lending a file server lends those files. The app cannot make a
  server safer than it is.
- **Only while the app is open.** A phone cannot wake the computer; if My PAI is closed there, the call fails with a
  clear message.

## Decisions for the owner

- Should the computer also ask before running a bridged call, at least the first time for each tool? It is safer
  and less convenient. Today it does not.
- Should lending be limited to servers that are programs on the computer, leaving out servers reached by address?
  Today both can be lent.
