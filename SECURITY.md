# Security

## Reporting a problem

Please do not open a public issue for a security problem.

Report it privately through GitHub: on this repository open the **Security** tab and choose **Report a vulnerability** ([direct link](https://github.com/thegeekygamechanger/my-pai/security/advisories/new)). Only the maintainer sees the report.

Say what you found, how to reproduce it, the app version and the device. You will get an answer as soon as the maintainer has read it, normally within a few days. Please give a reasonable time for a fix to be released before you write about it publicly.

## Which versions are fixed

Only the latest release. The app tells you when a newer one is out, and a fix is shipped as a new release.

## What counts

Especially welcome are reports about:

- An API key, token or password leaving the device, or being written to a log, a backup or an export
- The relay learning anything about what passes through it, or being made to keep it
- Synced or shared data being readable without the pairing or sharing code
- A tool call that changes data, sends an email or reaches an MCP server without the person approving it
- Text from a web page, a file or an MCP server being able to make the app act without the person's yes

## What the app does not promise

The AI services and MCP servers you connect are third parties: what you send them is handled under their own policies. A problem in one of those services should be reported to that service.
