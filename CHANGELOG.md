# Changelog

## 0.3.0 (unreleased)

### Runtime compatibility

- A JamJet runtime built after jamjet PR #169 refuses a workflow with a node kind nothing executes, and nothing executes a `wait` node. 0.2.x registered its recorded-call workflows (`spring-ai-chat`, `langchain4j-durable-agent`, `langchain4j-chat-memory`) with one `wait` node, so on such a runtime every advised call fails closed with `400 invalid workflow IR`. They are now registered as v2.0.0 with one `external_fn` node on a queue of the application's own (`<workflow-id>.calls`; `langchain4j-chat-memory.memory` for chat memory).
- The durability advisor and the LangChain4j durable agent claim that node's work item when the call starts, renew the lease while it runs, and complete it with the call's outcome when it ends (or fail it, when the call failed), echoing `lease_fence` and `idempotency_key`. The execution is `running` while the call runs and `completed` or `failed` after it; on 0.5.x it was completed as soon as it started. The node does not retry, so an application that stops mid-call leaves a failed execution, not an open one. The `spring_ai.completed` and `langchain4j.completed` events are recorded as before.
- Calls that start at the same time can claim each other's node (a claim hands out the oldest item on the queue). A node claimed for a call in the same JVM goes to that call; one claimed for a call in another instance is held and completed from that call's outcome event, or failed if none arrives within an hour (for the advisor, at least `spring.jamjet.approval.timeout` plus 30 minutes).
- If the runtime blocks the call's node under its policy, or holds it for approval, the call is refused before the model runs with `JamjetGovernanceException`, also with `on-runtime-error=fail-open`.
- On a runtime without `external_fn` nodes (0.5.x) the v1.0.0 `wait` workflows are registered instead, unchanged, with a warning. `RecordedCallWorkflow.VERSION` is now `2.0.0`; `LEGACY_VERSION` is `1.0.0`.
- New client methods for the work-item protocol: `claimWorkItem`, `completeWorkItem`, `failWorkItem`, `heartbeatWorkItem`.
- CI runs the integration tests against a runtime built from jamjet `main` as well as against the `jamjetdev/jamjet:0.5.0` image.

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

- **Fixed:** the client only read `ENGRAM_TOKEN`, so a token set as `ENGRAM_AUTH_TOKEN` (the name Engram uses for its server token) was never sent. It now reads `engram.api-token`, then `ENGRAM_AUTH_TOKEN`, then `ENGRAM_TOKEN`, trims the value, accepts only visible ASCII (refusing anything else without printing it), and sends it as `Authorization: Bearer`. A warning is logged when the token would go over plain HTTP to a host other than localhost, 127.0.0.0/8 or [::1]. Error text that could hold a header value (the JDK prints the whole header when it refuses one) is never logged or shown in health details, also for a client the application builds itself.
- **Fixed:** after a connection failure the advisor did not really carry on: jamjet-sdk 0.5.0 interrupts the calling thread on any I/O error, so the model call that followed on the same thread failed. The starter clears that interrupt (a real one is kept). The same applies to the chat memory repository and the health indicator.
- **Deprecated:** `ENGRAM_TOKEN`. It still works and logs a warning. Use `ENGRAM_AUTH_TOKEN` or `engram.api-token`.
- **Added:** a startup check. The starter calls `GET /health` (without the token unless the answer is 401, 403 or 407) and refuses to start if the server is not the Rust `jamjet-engram-server` (for example the Python Engram server, another service, a redirect, or a server that rejects the token), with `EngramServerMismatchException` saying what it found. An unreachable, 5xx or rate-limiting server only logs a warning. `engram.verify-on-startup=false` turns it off.
- **Changed:** `EngramContextAdvisor` and `EngramChatMemoryRepository` throw `EngramServerMismatchException` when a route answers 404 or 405, instead of logging and carrying on with no memory. Server errors and connection failures still degrade as before.
- **Changed:** `engram.spring-ai.chat-memory-repository` is now `off` (default), `save-only` or `on` (`false` and `true` still work), and the repository is no longer registered by default. `jamjet-engram-server` 0.5.0 does not serve `GET` or `DELETE /v1/memory/messages/{conversation_id}`, so in 0.2.0 the repository saved conversations but always read back an empty history. With `off`, Spring AI's own `ChatMemoryRepository` applies if you have one, and a startup warning says conversations are not saved to Engram when the application has a `ChatMemory`. `save-only` keeps 0.2.0's saves and returns an empty history with one warning. `on` against 0.5.0 now throws on reads. `EngramProperties.SpringAi.isChatMemoryRepository()` is replaced by `getChatMemoryRepository()`. `EngramContextAdvisor` is not affected.
- The starter supports the Rust Engram server only. The Python Engram server (`jamjet-engram`) serves different routes.

### Build

- `scripts/check_release_version.py` fails if the project version is already on Maven Central for any published module, if a module's version differs from the parent's, if the runtime client's User-Agent version is stale, or if a Markdown file shows `dev.jamjet` coordinates that are not on Central and are not what this build releases. CI runs it on every push with `--allow-released-tag` (a version already on Central passes only when its tag `v<version>` is an ancestor of the commit, so main does not go red between a release and the next bump), and `mvn deploy` runs it strictly before anything is uploaded. A full-reactor `mvn deploy` is required: `-pl`, `-rf` and `-Dexec.skip` skip it.

## 0.2.0 (2026-04-12, Engram modules and parent only)

- `engram-spring-boot-starter` and `engram-spring-boot-autoconfigure`: Spring AI `ChatMemoryRepository` backed by Engram (`EngramChatMemoryRepository`), auto-configured by default.

## 0.1.0 (2026-03-28)

- First release: `jamjet-spring-boot-starter`, `jamjet-spring-boot-autoconfigure`, `jamjet-spring-boot-starter-test`, `langchain4j-jamjet`.
