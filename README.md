# jamjet-spring-boot-starter

> **Two JamJet starters.** This repo is `dev.jamjet:jamjet-spring-boot-starter`: Spring AI and LangChain4j calls recorded on a JamJet runtime you run as a server, over HTTP. If you want the JVM runtime embedded in your application with no separate server, that is a different artifact, `dev.jamjet:jamjet-runtime-spring-boot-starter`, from [jamjet-runtime-java](https://github.com/jamjet-labs/jamjet-runtime-java).

Spring Boot starter for [JamJet](https://jamjet.dev). Add one dependency and every call through a `ChatClient` built from the auto-configured `ChatClient.Builder` is recorded on the JamJet runtime as an execution with an event log, with optional human approval before the model is called.

> **Upgrading from 0.1.0:** 0.1.0 called `/api/v1/...` routes the runtime never served. The runtime answered them with its web UI (`200 text/html`), the advisors logged a warning and carried on, and nothing was recorded. 0.2.1 calls the runtime's real routes and refuses calls it cannot record (see [When the runtime is unavailable](#when-the-runtime-is-unavailable)). LangChain4j chat memory is now keyed by a namespace (see [LangChain4j Integration](#langchain4j-integration-langchain4j-jamjet)), so memory written by a pre-release build without one is not found and conversations start empty. Those executions stay on the runtime; read their `chat_memory` events with `GET /executions/{id}/events` if you need to carry history over. There is no 0.2.0 of the JamJet modules on Maven Central: an earlier Engram release already used that version, so the first release with these fixes is 0.2.1.

## Quickstart

### 1. Add the dependency

```xml
<dependency>
    <groupId>dev.jamjet</groupId>
    <artifactId>jamjet-spring-boot-starter</artifactId>
    <version>0.2.1</version>
</dependency>
```

### 2. Start the JamJet runtime

```bash
docker run -p 7700:8080 -e JAMJET_DEV_MODE=1 jamjetdev/jamjet:0.5.0
```

Dev mode turns off authentication. This image keeps state in memory (it sets `STORAGE_BACKEND=memory`); the `jamjet-server` binary defaults to a SQLite file in its working directory, which survives restarts. Outside dev mode, set `spring.jamjet.api-token`.

### 3. Configure

```properties
spring.jamjet.runtime-url=http://localhost:7700
```

### 4. Use the ChatClient as normal

```java
@SpringBootApplication
public class MyApp {

    @Bean
    ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build(); // JamJet advisors are added by auto-configuration
    }

    @Bean
    CommandLineRunner demo(ChatClient chatClient) {
        return args -> System.out.println(
                chatClient.prompt("Summarize AI trends").call().content());
    }
}
```

## What gets recorded

The auto-configured `ChatClientCustomizer`s add these advisors to every `ChatClient` built from the auto-configured `ChatClient.Builder` bean. A client built another way (`ChatClient.create(model)`, `ChatClient.builder(model)`) gets none of them, so it is neither recorded nor gated; add the advisor beans to it yourself with `.defaultAdvisors(...)`.

| Advisor | What it does on the runtime |
|---------|-----------------------------|
| `JamjetDurabilityAdvisor` | Registers the `spring-ai-chat` workflow once (`POST /workflows`), starts one execution per call (`POST /executions`) before the model is called, and appends a `spring_ai.completed` event with the outcome (`completed` or `failed`). |
| `JamjetAuditAdvisor` | Appends the prompt and the response as `spring_ai.prompt` and `spring_ai.response` events. Turn the text off with `spring.jamjet.audit.include-prompts=false` / `include-responses=false`. |
| `JamjetApprovalAdvisor` | Opt in with `spring.jamjet.approval.enabled=true`. Pauses calls that ask for approval until a reviewer decides (see below). |

Read a call's record back with `GET /executions/{id}/events`, or from Java:

```java
List<ExecutionEvent> events = runtimeClient.listEvents(executionId);
```

The model call runs in your JVM. The runtime holds the record: the workflow's single node is a `wait` on the `spring_ai.completed` event, which the advisor sends when the call ends. The runtime does not re-run or replay Spring AI calls.

## When the runtime is unavailable

With the default `spring.jamjet.on-runtime-error=fail-closed`, what happens depends on when the runtime fails:

| When | What fails to record | Result |
|------|----------------------|--------|
| Before the model is called | workflow registration, starting the execution, the `spring_ai.prompt` event, the approval request | The call is refused with `JamjetGovernanceException` and the model is not called. |
| After the model returned | the `spring_ai.response` or `spring_ai.completed` event | `JamjetRecordingException`: the model call completed (any effect has happened) and only its record is missing. The runtime's error is the cause. |
| After the model threw | the `failed` outcome | The model's own exception, with a `JamjetRecordingException` attached as suppressed. |

A `runtime-url` that does not point at the runtime API counts as a failure: any non-JSON answer is an error, never data.

To let calls run unrecorded instead, opt in:

```properties
spring.jamjet.on-runtime-error=fail-open
```

Fail-open logs a warning for every call it lets through.

### Streaming

In fail-closed mode a streaming call (`.stream()`) is refused before the model runs unless you allow it:

```properties
spring.jamjet.allow-streaming=true
```

An allowed stream records the whole streamed text as `spring_ai.response` and an outcome of `completed`, `failed` or `cancelled`. A stream's record can only be written after its output was delivered, so a fail-closed record failure on a stream arrives as an error at the end of the stream, after the chunks.

## Human approval

```properties
spring.jamjet.approval.enabled=true
spring.jamjet.approval.timeout=30m
```

Mark a call as needing approval:

```java
chatClient.prompt("Send the wire")
        .advisors(a -> a.param(JamjetApprovalAdvisor.REQUIRES_APPROVAL_KEY, true))
        .call()
        .content();
```

The call records `spring_ai.approval_requested` on its execution and blocks until a reviewer decides through the approval endpoints:

```bash
# Calls waiting for a decision, each with its prompt and content_digest
curl -u reviewer:secret http://localhost:8080/jamjet/approvals/pending

# Decide
curl -u reviewer:secret -X POST http://localhost:8080/jamjet/approvals/{executionId} \
  -H 'Content-Type: application/json' \
  -d '{"decision":"approved","content_digest":"<from /pending>","comment":"checked"}'
```

**A decision is bound to the request that was reviewed.** `/pending` shows the request (one line per message with its role, plus media, tool calls, tool responses and the tools offered; invisible and reordering characters escaped; cut at 2000 characters with a `[truncated]` marker) and a `content_digest` over the request the ChatClient hands the model: every message's role and text, media (MIME type and a hash of the bytes, or the URL), tool calls, tool responses, every readable property of the options object (so provider-specific options such as a tool choice or a seed are covered), the tool names and definitions, and the tool context. Not covered, because an advisor cannot see them: default options and tools configured on the `ChatModel` itself (for example `spring.ai.openai.chat.options.*`), which the model merges inside its own call. Set options that matter for approval on the ChatClient or the request, not on the model. A decision must echo that digest: without it the answer is 400, with a different one 409, and nothing is recorded. The approval advisor is ordered `LOWEST_PRECEDENCE - 1`, the last advisor slot in Spring AI 1.0.x: an advisor with the same order runs before it (so its changes are reviewed), and one ordered `LOWEST_PRECEDENCE` sorts after Spring AI's own model-call advisor and never runs. After approval, Spring AI 1.0.x's model-call advisor appends the output-format instructions (from `.entity(...)`, or from a `spring.ai.chat.client.output.format` param) to the user message on the `.call()` path; the digest and preview already include that text. The `.stream()` path appends nothing.

**What an approval does not cover.** Spring AI runs the tool-calling loop inside the model call: after approval the model can call the offered tools, and those calls are not individually reviewed here. To gate the actions themselves, run them as runtime-governed tools with the runtime's own approval policy (`require_approval_for`), which holds each call until it is approved.

Only one decision applies to a call: a second or late one gets 409 and records nothing.

**Reviewers must be authenticated.** The approver recorded on the runtime is the authenticated principal of the request; the body cannot name it. This applies to `GET /pending` as well as to decisions, because the list shows prompts and initiators. A request with no principal, an anonymous authentication, or an authentication that is not authenticated gets 401 and releases nothing. A remember-me login is refused too unless `spring.jamjet.approval.allow-remember-me=true`, so a reviewer must have signed in this session. **Separation of duties.** The principal that triggered a call cannot approve it (403). Only JamJet sets it, from the security context; a caller-supplied `jamjet.approval.initiator` param is ignored and logged. It is captured before any thread hop: on the calling thread for `.call()`, at subscription for `.stream()` (and from the Reactor context on WebFlux). It is lost when the call runs on a thread that does not carry the caller's `SecurityContext`, for example an `@Async` method or a plain executor (use `DelegatingSecurityContextExecutor` or `SecurityContextHolder` propagation), or a blocking `.call()` inside a WebFlux handler. When the initiator is unknown, the request and decision events say so (`initiator_known: false`) and the call can be rejected but not approved, unless `spring.jamjet.approval.allow-unknown-initiator=true`. The check compares principal names exactly, so make sure one person has one name across your authentication sources. **Reviewers need the approver authority.** Being logged in is not enough: the controller itself requires `ROLE_JAMJET_APPROVER` (configurable with `spring.jamjet.approval.required-authority`) for both `/pending` and decisions, and answers 403 without it. A principal that is not a Spring Security `Authentication` has no authorities the controller can read and is refused. The endpoints are registered only when Spring Security is on the classpath. Restrict them in your filter chain as well, for example:

```java
@Bean
SecurityFilterChain approvals(HttpSecurity http) throws Exception {
    return http
            .securityMatcher("/jamjet/approvals/**")
            .authorizeHttpRequests(a -> a.anyRequest().hasRole("JAMJET_APPROVER"))
            .httpBasic(Customizer.withDefaults())
            .csrf(csrf -> csrf.disable()) // see the note below
            .build();
}
```

Turning CSRF off is acceptable here only because the decision endpoint accepts `application/json` bodies only, so a cross-site form post is refused (415) even though browsers resend cached Basic credentials. If reviewers sign in with a session or form login, keep CSRF on and send the token with each POST.

For local development, `spring.jamjet.approval.controller.allow-unauthenticated=true` registers the endpoints even without Spring Security and logs a warning at startup. It switches off every reviewer check, also when Spring Security is present: no principal is required, the authority check is skipped, and anonymous and remember-me principals are accepted. Decisions from anyone who did not really authenticate are recorded with approver `unauthenticated` and `authenticated: false`. Anyone who can reach the application can then approve its calls.

**Approvals the runtime holds are not decided here.** The runtime records the identity of the token that sends a decision and enforces separation of duties on it, so the application never forwards decisions with its own token. `/pending` lists any requests the runtime holds for a waiting call's execution, read-only, with their `action_digest` and the command to run with your own runtime token, for example `jamjet approve exec_... --decision approved --node-id pay --action-digest <digest>`.

A decision is appended to the call's execution as a `spring_ai.approval_decision` event (approver, whether authenticated, the initiator when known, comment) before the call is released. If it cannot be recorded, the endpoint answers with the runtime's status (or 502) and message, and the waiting call is released as rejected. Only `"approved"` lets a call through. A call that times out is always rejected (`default-decision` is deprecated; any value other than `rejected` fails at startup). If a decision is recorded but the call stopped waiting before it could be delivered, the endpoint answers 409 and appends a `spring_ai.approval_decision_void` event, so the record never claims a decision that did not apply.

On a `ChatClient` built from the auto-configured `ChatClient.Builder`, a call marked as requiring approval never runs ungated: `JamjetApprovalGuard` is auto-configured on its own, independently of `durability-enabled` and `approval.enabled`, and refuses the call before the model runs when no approval advisor is on the client. A client built any other way (`ChatClient.create(model)`, `ChatClient.builder(model)`, your own `ChatClient.Builder` bean, or with `spring.ai.chat.client.enabled=false`) gets no guard, and the marker does nothing there; add `JamjetApprovalGuard` and `JamjetApprovalAdvisor` to it yourself. A call whose execution could not be started (the runtime failed open) is rejected by the approval advisor.

## Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `spring.jamjet.runtime-url` | `http://localhost:7700` | JamJet runtime URL |
| `spring.jamjet.api-token` | none | Bearer token for the runtime. The token decides the tenant; there is no separate tenant setting (`spring.jamjet.tenant-id` is ignored and logs a warning) |
| `spring.jamjet.durability-enabled` | `true` | Enable the starter |
| `spring.jamjet.on-runtime-error` | `fail-closed` | `fail-closed` refuses calls the runtime cannot record; `fail-open` runs them unrecorded |
| `spring.jamjet.workflow-id` | `spring-ai-chat` | Workflow the durability advisor registers |
| `spring.jamjet.audit.enabled` | `true` | Add the audit advisor |
| `spring.jamjet.audit.include-prompts` | `true` | Record prompt text |
| `spring.jamjet.audit.include-responses` | `true` | Record response text and token usage |
| `spring.jamjet.approval.enabled` | `false` | Add the approval advisor and endpoints |
| `spring.jamjet.approval.timeout` | `30m` | How long a call waits for a decision (`500ms`, `30s`, `30m`, `2h`, ISO-8601; a bare number is minutes) |
| `spring.jamjet.approval.default-decision` | `rejected` | Deprecated. A timed-out call is always rejected; any other value fails at startup |
| `spring.jamjet.approval.required-authority` | `ROLE_JAMJET_APPROVER` | Authority a reviewer must hold to list or decide approvals |
| `spring.jamjet.approval.allow-unknown-initiator` | `false` | Allow approving a call whose initiator could not be determined |
| `spring.jamjet.approval.allow-remember-me` | `false` | Accept a remember-me login as a reviewer |
| `spring.jamjet.approval.controller.allow-unauthenticated` | `false` | Local development only: register the approval endpoints without Spring Security and switch off every reviewer check (principal, authority, anonymous, remember-me) |
| `spring.jamjet.connect-timeout-seconds` | `10` | Connection timeout |
| `spring.jamjet.read-timeout-seconds` | `120` | Read timeout |

## Modules

| Module | Description |
|--------|-------------|
| `jamjet-spring-boot-autoconfigure` | Auto-configuration, advisors, approval endpoints and the runtime client |
| `jamjet-spring-boot-starter` | Starter POM (add this to your project) |
| `jamjet-spring-boot-starter-test` | JUnit 5 replay testing and a runtime Testcontainer |
| `langchain4j-jamjet` | LangChain4j durable agent and chat memory store |
| `engram-spring-boot-starter` | Engram memory for Spring AI (see [Engram memory](#engram-memory-engram-spring-boot-starter)) |
| `engram-spring-boot-autoconfigure` | Auto-configuration behind the Engram starter |
| `jamjet-spring-boot-starter-example` | Example application (not published) |
| `engram-spring-boot-starter-example` | Engram example application (not published) |

## Requirements

- Java 21+
- Spring Boot 3.4+
- Spring AI 1.0+
- JamJet runtime 0.5.0+ (Docker image `jamjetdev/jamjet` or the `jamjet-server` binary)

## Testing (`jamjet-spring-boot-starter-test`)

```xml
<dependency>
    <groupId>dev.jamjet</groupId>
    <artifactId>jamjet-spring-boot-starter-test</artifactId>
    <version>0.2.1</version>
    <scope>test</scope>
</dependency>
```

**Replay tests**: load an execution recorded on a runtime you already run. Point the extension at it with the `spring.jamjet.runtime-url` system property (default `http://localhost:7700`):

```java
@ExtendWith(JamjetReplayExtension.class)
class MyAgentTest {

    @Test
    @ReplayExecution("exec_abc123")
    void agentProducesConsistentOutput(RecordedExecution execution) {
        AgentAssertions.assertThat(execution)
            .completedSuccessfully()
            .hasEvent("node_completed")
            .completedWithin(30, TimeUnit.SECONDS);
    }
}
```

```bash
mvn test -Dspring.jamjet.runtime-url=http://localhost:7700
```

`@WithJamjetRuntime` starts a fresh, empty runtime container for the test class and sends no token, so it cannot replay an execution recorded elsewhere. Use it when the test creates its own executions.

**Runtime container**: `JamjetTestContainer` runs `jamjetdev/jamjet:0.5.0` in dev mode.

**Deterministic stubs**: no LLM calls needed:

```java
var stub = DeterministicModelStub.builder()
    .onPromptContaining("weather", "Sunny, 72F")
    .defaultResponse("I don't know")
    .build();
```

## Observability

The autoconfiguration registers two helper beans. The advisors do not call them, so no metrics or spans appear until your own code does.

**Micrometer**: `JamjetMicrometerBridge` is registered when a `MeterRegistry` bean exists (on by default, set `spring.jamjet.observability.micrometer=false` to turn off). Metric names use `spring.jamjet.observability.metric-prefix` (default `jamjet`).

```java
@Autowired JamjetMicrometerBridge metrics;

metrics.recordExecutionCompleted("completed", Duration.ofSeconds(4));
metrics.recordToolCall("search", Duration.ofMillis(120));
metrics.recordExecutionCost(0.012);
```

Meters it records: `jamjet.execution.duration`, `jamjet.execution.count`, `jamjet.node.duration`, `jamjet.node.retries`, `jamjet.tool.calls`, `jamjet.tool.duration`, `jamjet.execution.cost.usd`, `jamjet.audit.events`.

**OpenTelemetry**: `JamjetOtelBridge` is registered only with `spring.jamjet.observability.opentelemetry=true` and an OpenTelemetry `Tracer` bean.

```java
@Autowired JamjetOtelBridge tracing;

Span exec = tracing.startExecutionSpan("exec_1", "my-workflow");
Span tool = tracing.startToolSpan(exec, "search");
tracing.endSpan(tool, 0.0);
tracing.endSpan(exec, 0.012);
```

Span names you build this way: `jamjet.execution`, `jamjet.node.{id}`, `jamjet.tool.{name}`, `jamjet.model.call`.

## LangChain4j Integration (`langchain4j-jamjet`)

```xml
<dependency>
    <groupId>dev.jamjet</groupId>
    <artifactId>langchain4j-jamjet</artifactId>
    <version>0.2.1</version>
</dependency>
```

**Recorded AI services**: wrap any `AiServices` proxy. Each method call starts an execution of the `langchain4j-durable-agent` workflow and records a `langchain4j.completed` event with the result or the error:

```java
var jamjet = new JamjetConfig().runtimeUrl("http://localhost:7700");

MyAssistant raw = AiServices.builder(MyAssistant.class)
    .chatModel(model).tools(tools).build();

MyAssistant recorded = jamjet.wrap(raw, MyAssistant.class);
```

Arguments and results are recorded by default. `new JamjetConfig().recordContent(false)` records only the method, the argument count, the outcome and, on failure, the exception type.

**Chat memory on the runtime**: each (namespace, memory id) is one execution of the `langchain4j-chat-memory` workflow; every update appends the full message list as a `chat_memory` event and reads take the latest one. Set the namespace to your application's name, so two applications on one runtime tenant do not read each other's memory under the same id (LangChain4j's default id is `"default"`):

```java
var jamjet = new JamjetConfig()
    .runtimeUrl("http://localhost:7700")
    .memoryNamespace("support-bot");

var memory = MessageWindowChatMemory.builder()
    .chatMemoryStore(jamjet.chatMemoryStore())
    .id("session-1")
    .maxMessages(20)
    .build();
```

The namespace separates applications; it is not access control. Anything holding a token for the tenant can read and append events on any execution.

The runtime has no lookup by input, so the first access to a memory id in a process scans `GET /executions` for it (linear in the tenant's execution count) and caches the result; a miss is cached for 30 seconds, so a new conversation scans once. Keep one writer per memory id: two replicas that both start the same new id each create an execution and the history splits, and after a restart the newest wins.

Both refuse to run unrecorded by default (`JamjetGovernanceException`); `new JamjetConfig().onRuntimeError(RuntimeErrorMode.FAIL_OPEN)` lets calls run and reads fall back to an empty memory instead.

## Engram memory (`engram-spring-boot-starter`)

```xml
<dependency>
    <groupId>dev.jamjet</groupId>
    <artifactId>engram-spring-boot-starter</artifactId>
    <version>0.2.1</version>
</dependency>
```

The starter talks to the **Rust Engram server** (`jamjet-engram-server` 0.5.x on crates.io, started with `engram serve --mode rest`). The Python Engram server (`jamjet-engram` on PyPI) serves different routes and is not supported yet.

```properties
engram.base-url=http://localhost:9090
```

**Token.** The client sends `Authorization: Bearer <token>` taken from `engram.api-token` (which Spring also binds from `ENGRAM_API_TOKEN`), else the `ENGRAM_AUTH_TOKEN` environment variable (the name Engram uses for its server token), else `ENGRAM_TOKEN`, which still works but is deprecated and logs a warning. The value is trimmed, and a token with a control character in it stops startup without being printed. `jamjet-engram-server` 0.5.0 does not check a token itself, so keep it on a private network or behind a proxy that does. The starter logs a warning when it would send a token over plain `http://` to a host other than localhost.

**Startup check.** On startup the starter calls `GET /health` once and expects the Rust server's answer. Against the Python server, another service, a redirect, or a server that rejects the token, the application fails to start with `EngramServerMismatchException`, which says what it found. A server that is unreachable, answers 5xx or rate-limits only logs a warning. Turn the check off with `engram.verify-on-startup=false`.

**Wrong routes fail loudly.** If a route answers 404 or 405 at runtime, `EngramContextAdvisor` and `EngramChatMemoryRepository` throw `EngramServerMismatchException` naming the route. A server error or a connection failure is still logged, and the call goes on without memory.

**Chat memory repository (off by default).** `EngramChatMemoryRepository` reads and deletes a conversation with `GET` and `DELETE /v1/memory/messages/{conversation_id}`. `jamjet-engram-server` 0.5.0 does not match those routes for any conversation id, so earlier versions returned an empty history without saying so. It is now registered only with `engram.spring-ai.chat-memory-repository=true`, and on 0.5.0 its reads throw `EngramServerMismatchException`. Turn it on once a fixed server is released. The context advisor (`POST /v1/memory/context`) is not affected.

## Building

```bash
mvn -Dgpg.skip verify            # unit tests
mvn -Dgpg.skip -Pit verify       # plus RuntimeContractIT against a real runtime (Docker)
JAMJET_IT_RUNTIME_URL=http://localhost:7700 mvn -Dgpg.skip -Pit verify   # use a runtime you started (dev mode)
python3 scripts/check_release_version.py   # fails if any module's version is already on Maven Central
```

## License

Apache License 2.0
