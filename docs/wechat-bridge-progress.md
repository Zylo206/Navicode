# WeChat Bridge Progress

## 2026-06-13

- Read required repo context: `AGENTS.md`, `CLAUDE.md`, `README.md`, Runtime API docs, `Main.java`, `Agent.java`, Runtime API/store, `TaskRunner`, renderer interfaces, and Runtime API tests.
- Checked `wechat-claude-code` README and source shape; kept the same daemon bridge boundary instead of moving WeChat logic into the Java CLI.
- Stage 1 progress: added Runtime API thread metadata, per-thread serial turn sessions, optional turn `cwd`, `turn.queued`, and thread-level Agent reuse in `serve --http`.
- Stage 2 progress: added `POST /v1/threads/{id}/cancel` and `RuntimeEventRenderer` for headless event output, including `message.delta`, `tool.calls`, `diff.summary`, and conservative headless HITL rejection.
- Stage 4 progress: created `integrations/wechat-bridge` TypeScript skeleton with config, redacted logger, Runtime API client, thread map, command router, message splitter, and mock tests.
- Stage 5 progress: added `docs/wechat-bridge.md` and bridge README documenting scope, security defaults, commands, data directories, and remaining real ilink work.
- Stage 5 progress: `/cwd` now stores a bridge session directory and passes it to Runtime API turns, where the headless Agent applies it to its tool registry.
- Stage 4 progress: added a bridge `submitTextForUser` mock closed-loop test covering create thread, submit text turn, read `message.delta`, split output, and persist the WeChat user thread binding.
- Hardening: fixed Runtime API constructor overload ambiguity found by Maven compile, and expanded Runtime event JSON escaping for low control characters.
- Verification attempted: `mvn test -Dtest=RuntimeApiServerTest -DskipTests=false`.
- Verification result: blocked by sandboxed Maven dependency resolution for `maven-resources-plugin:3.3.1`; escalation request for networked Maven resolution was rejected. JUnit execution through Maven still needs verification in an environment with dependencies available.
- Verification attempted: `mvn -o test -Dtest=RuntimeApiServerTest -DskipTests=false`.
- Verification result: offline Maven still fails because plugin dependency metadata is not fully resolvable from local cache.
- Verification attempted: `mvn test "-Dtest=CliCommandParserTest,MemoryManagerTest,RuntimeApiServerTest" -DskipTests=false`.
- Verification result: blocked by the same sandboxed Maven dependency resolution for `maven-resources-plugin:3.3.1`.
- Verification attempted: `mvn -q -DskipTests package`.
- Verification result: passed; main and test sources compile, including `RuntimeApiServerTest`.
- Verification attempted: JShell Runtime API smoke test against `target/navicode-1.0-SNAPSHOT-shaded.jar`.
- Verification result: passed; verified same-thread sequential turns keep runner state, events include `turn.queued`, `turn.started`, `message.delta`, `turn.completed`, optional `cwd` reaches the runner, and `POST /cancel` produces `turn.cancelled`.
- Verification attempted: `npm.cmd test` in `integrations/wechat-bridge`.
- Verification result: still blocked for the normal npm path because `tsc` is unavailable until bridge dependencies are installed.
- Verification attempted: `npm.cmd run build` in `integrations/wechat-bridge`.
- Verification result: blocked by the same missing local `tsc`.
- Verification attempted: Node 22 source test fallback with `--experimental-loader` mapping `.js` specifiers to `.ts` sources and `--experimental-transform-types`.
- Verification result: passed all 9 bridge tests: commands, message splitter, Runtime client, and `submitTextForUser` mock closed loop.
- Verification attempted: `npm.cmd install` in `integrations/wechat-bridge`.
- Verification result: install timed out without creating `node_modules` or `package-lock.json`; escalation request for networked npm install was rejected. Bridge tests still need verification once dependencies are available.
- Verification attempted: `git diff --check`.
- Verification result: passed; only Git line-ending warnings for existing Java working-copy normalization were reported.
- Next: run the Maven JUnit commands once plugin resolution is available, run bridge `npm install`, `npm test`, and `npm run build` once TypeScript is installed, then fix any compile/test issues.

## 2026-06-13 ilink modules

- Implemented `src/wechat/login.ts`, `monitor.ts`, `send.ts`, and `media.ts`, plus supporting `api.ts`, `accounts.ts`, `sync-buf.ts`, and `types.ts`.
- Wired `src/main.ts` with `npm start -- setup` for QR binding and `npm start -- start` for loading the latest account, long-polling ilink messages, submitting text to Runtime API, and sending split replies back to WeChat.
- Media support now handles direct `cdn_url` downloads into `~/.navicode/wechat/downloads` and upload URL based local file sending helpers. Encrypted CDN media without direct URL remains a known gap.
- Verification attempted: `npm.cmd run build`.
- Verification result: passed.
- Verification attempted: `npm.cmd test`.
- Verification result: passed all 13 bridge tests, covering command routing, message splitting, Runtime client, submit text loop, QR login persistence, monitor dedupe/sync buffer, sendText payloads, and direct media download.
- Next: test against a real bound WeChat account, then add encrypted CDN media decrypt and daemon scripts.
