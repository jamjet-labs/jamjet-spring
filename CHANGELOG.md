# Changelog

## 0.2.1 (unreleased)

The first release of the JamJet modules since 0.1.0, and a fix release for the Engram modules. Every module is 0.2.1. There is no 0.2.0 of `jamjet-spring-boot-starter`, `jamjet-spring-boot-autoconfigure`, `jamjet-spring-boot-starter-test` or `langchain4j-jamjet`: the parent and the Engram modules were published as 0.2.0 on 2026-04-12, and Maven Central does not accept a version twice.

Built against Spring Boot 3.4 and Spring AI 1.0.

### JamJet starter and LangChain4j (changes since 0.1.0)

- 0.1.0 called `/api/v1/...` routes the runtime never served. The runtime answered with its web UI (`200 text/html`), the advisors logged a warning, and nothing was recorded. The client now calls the runtime's real routes and treats any non-JSON answer as an error.
- New `spring.jamjet.on-runtime-error`, default `fail-closed`: a call the runtime cannot record is refused before the model runs. `fail-open` lets it run unrecorded with a warning.
- The durability advisor registers one valid `spring-ai-chat` workflow, starts an execution per call before the model runs, and records the outcome. The audit and approval advisors are now added to auto-configured `ChatClient`s.
- Approval decisions are bound to the request the reviewer saw (`content_digest`), recorded on the runtime, need an authenticated reviewer with `ROLE_JAMJET_APPROVER`, and the caller cannot approve its own call. A paused call is never left waiting on a runtime error.
- LangChain4j chat memory is keyed by a namespace (`JamjetConfig.memoryNamespace`), and both the store and the durable agent fail closed by default.
- `JamjetTestContainer` runs `jamjetdev/jamjet:0.5.0` (it pointed at an image that does not exist), and the replay module reads the runtime's event shape.
- Testcontainers 1.20.4 to 1.21.4. 1.20.4 only spoke Docker API 1.32, which current Docker Engine refuses ("client version 1.32 is too old"), so `JamjetTestContainer` and `@WithJamjetRuntime` could not start a container there.

### Engram starter

- **Fixed:** the client never sent a token unless `engram.api-token` was set, because it only read `ENGRAM_TOKEN`. It now reads `engram.api-token`, then `ENGRAM_AUTH_TOKEN` (the name Engram uses for its server token), then `ENGRAM_TOKEN`, and sends it as `Authorization: Bearer`.
- **Deprecated:** `ENGRAM_TOKEN`. It still works and logs a warning. Use `ENGRAM_AUTH_TOKEN` or `engram.api-token`.
- **Added:** a startup check. The starter calls `GET /health` once and refuses to start if the server is not the Rust `jamjet-engram-server` (for example the Python Engram server, another service, or a server that rejects the token), with `EngramServerMismatchException` saying what it found. An unreachable server only logs a warning. `engram.verify-on-startup=false` turns it off.
- **Changed:** `EngramContextAdvisor` and `EngramChatMemoryRepository` throw `EngramServerMismatchException` when a route answers 404 or 405, instead of logging and carrying on with no memory. Server errors and connection failures still degrade as before.
- **Known issue:** `jamjet-engram-server` 0.5.0 does not serve `GET` or `DELETE /v1/memory/messages/{conversation_id}`, so `EngramChatMemoryRepository` cannot read or delete history on it. Reads now throw instead of returning an empty history. Set `engram.spring-ai.chat-memory-repository=false` until a fixed server is released. `EngramContextAdvisor` is not affected.
- The starter supports the Rust Engram server only. The Python Engram server (`jamjet-engram`) serves different routes.

### Build

- `scripts/check_release_version.py` fails if the project version is already on Maven Central for any published module, if a module's version differs from the parent's, if the runtime client's User-Agent version is stale, or if a Markdown file shows `dev.jamjet` coordinates that are not on Central and are not what this build releases. CI runs it on every push, and `mvn deploy` runs it before anything is uploaded.

## 0.2.0 (2026-04-12, Engram modules and parent only)

- `engram-spring-boot-starter` and `engram-spring-boot-autoconfigure`: Spring AI `ChatMemoryRepository` backed by Engram (`EngramChatMemoryRepository`), auto-configured by default.

## 0.1.0 (2026-03-28)

- First release: `jamjet-spring-boot-starter`, `jamjet-spring-boot-autoconfigure`, `jamjet-spring-boot-starter-test`, `langchain4j-jamjet`.
