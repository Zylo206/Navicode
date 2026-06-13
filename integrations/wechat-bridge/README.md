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
- `src/main.ts`: mockable text-to-runtime submission loop covered by `tests/submit-text.test.ts`.

## Not Yet Implemented

Real WeChat ilink modules are intentionally left for the next phase:

- QR setup and account persistence.
- Long-poll monitor.
- Text/file/image sending.
- Media download/upload.
- daemon scripts.
