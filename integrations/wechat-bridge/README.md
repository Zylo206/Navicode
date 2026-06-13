# Navicode WeChat Bridge

Local TypeScript bridge skeleton for connecting WeChat messages to Navicode Runtime API.

## Install

```powershell
cd integrations/wechat-bridge
npm install
npm test
```

## Runtime API

Start Navicode first:

```powershell
$env:NAVICODE_RUNTIME_API_KEY = "test"
java -jar target/navicode-1.0-SNAPSHOT.jar serve --http --port 8080
```

Text submissions call `POST /v1/threads/{id}/turns` with `input` and, when available, the session `cwd` selected by `/cwd`.

Bridge config defaults to:

```json
{
  "runtimeBaseUrl": "http://127.0.0.1:8080",
  "runtimeApiKeyEnv": "NAVICODE_RUNTIME_API_KEY",
  "dataDir": "~/.navicode/wechat"
}
```

## Implemented Modules

- `src/navicode/runtime-client.ts`: Runtime API client and SSE parser.
- `src/navicode/thread-map.ts`: WeChat user to Runtime thread mapping.
- `src/message-splitter.ts`: WeChat-sized text chunking.
- `src/commands/router.ts`: `/help`, `/status`, `/clear`, `/stop`, `/cwd`.
- `src/wechat/login.ts`: ilink QR login and account persistence.
- `src/wechat/monitor.ts`: ilink `getupdates` long-polling, sync buffer persistence, deduplication, and session-expired handling.
- `src/wechat/send.ts`: ilink text/file sending and typing indicator support.
- `src/wechat/media.ts`: text extraction, direct media URL download, and local file upload helpers.
- `src/main.ts`: mockable text-to-runtime submission loop covered by `tests/submit-text.test.ts`.

## Use With WeChat

Start Navicode Runtime API first, then run QR setup:

```powershell
$env:NAVICODE_RUNTIME_API_KEY = "test"
npm start -- setup
```

After scanning and confirming in WeChat, start the bridge:

```powershell
npm start -- start
```

The bridge loads the latest saved account from `~/.navicode/wechat/accounts`, long-polls ilink messages, submits text to Runtime API, then sends split replies back to WeChat.

## Remaining

- Encrypted CDN media decryption for messages that do not expose a direct `cdn_url`.
- PowerShell and POSIX daemon scripts.
- End-to-end manual validation against a real bound WeChat account.
