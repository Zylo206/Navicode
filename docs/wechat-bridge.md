# Navicode WeChat Bridge

## Scope

This bridge is for personal remote control of a local Navicode runtime from WeChat. It follows the same boundary as `wechat-claude-code`:

```text
WeChat personal account
  <-> ilink Bot API
  <-> Navicode WeChat Bridge daemon
  <-> Navicode Runtime API
  <-> Agent.run(...)
```

It is not a WeChat Official Account or WeCom production integration. Public or multi-user deployment should use Tencent's official callback platform instead of this personal-account bridge.

## Runtime API

Start Navicode in headless mode:

```powershell
$env:NAVICODE_RUNTIME_API_KEY = "your-local-key"
java -jar target/navicode-1.0-SNAPSHOT.jar serve --http --port 8080
```

The Runtime API only listens on `127.0.0.1` and requires either:

- `Authorization: Bearer <NAVICODE_RUNTIME_API_KEY>`
- `X-Navicode-API-Key: <NAVICODE_RUNTIME_API_KEY>`

The bridge uses:

- `POST /v1/threads`
- `POST /v1/threads/{id}/turns`
- `POST /v1/threads/{id}/cancel`
- `GET /v1/threads/{id}/events?after=<event_id>`

`POST /v1/threads/{id}/turns` accepts `input` and optional `cwd`. When `cwd` is present, the Runtime API records it on the thread and applies it to the headless Agent tool registry for that turn.

Events currently include:

- `thread.created`
- `turn.queued`
- `turn.started`
- `message.delta`
- `tool.calls`
- `diff.summary`
- `approval.required`
- `turn.completed`
- `turn.failed`
- `turn.cancelled`

## Bridge Layout

The bridge skeleton lives in:

```text
integrations/wechat-bridge/
```

Data belongs under:

```text
~/.navicode/wechat/
  accounts/
  sessions/
  downloads/
  logs/
  config.json
  get_updates_buf
```

Do not commit account credentials, downloaded private files, logs, or runtime session state.

## Commands

The MVP command router supports:

```text
/help
/status
/clear
/stop
/cwd <path>
```

`/stop` calls Runtime API cancellation for the current thread. `/clear` removes the current WeChat user to Runtime thread binding so the next normal message starts a new Runtime thread. `/cwd` stores the working directory in the bridge session and sends it with future Runtime turns.

## Security Defaults

- The bridge only connects to a local `http://127.0.0.1`, `localhost`, or `::1` Runtime API.
- Runtime API keys are read from an environment variable and should not be written to logs.
- Logs redact token, authorization, secret, and API key shaped fields.
- `allowedRoots` constrains `/cwd` and later file sending.
- Headless HITL requests are rejected by default; WeChat does not silently approve dangerous actions.
- File sending should be limited to `allowedRoots` and `~/.navicode/wechat/downloads/`.

## Current Status

Implemented:

- Runtime API thread-level serial turn execution.
- Runtime API optional turn `cwd` propagation.
- Runtime API `cancel` endpoint.
- Runtime event renderer for headless Agent output.
- TypeScript bridge for config, runtime client, thread map, message splitting, and command routing.
- ilink QR login and account persistence under `~/.navicode/wechat/accounts`.
- ilink `getupdates` monitor with sync buffer persistence, message deduplication, and session-expired handling.
- WeChat text sending, file/image sending helpers, and typing keepalive.
- Direct URL media download into `~/.navicode/wechat/downloads`.
- Mock tests for Runtime client, message splitter, commands, and the text submit/read/split loop.

Remaining:

- Encrypted CDN media decryption for messages that do not expose a direct `cdn_url`.
- PowerShell and POSIX daemon scripts.
- Automatic file push for generated local files.
- End-to-end manual test with a bound WeChat account.

## Bridge Commands

Install dependencies and verify:

```powershell
cd integrations/wechat-bridge
npm install
npm test
```

Bind a WeChat account:

```powershell
$env:NAVICODE_RUNTIME_API_KEY = "your-local-key"
npm start -- setup
```

Start the bridge after Navicode Runtime API is running:

```powershell
npm start -- start
```
