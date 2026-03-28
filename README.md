# jamjet-spring-boot-starter

Spring Boot starter for [JamJet](https://jamjet.dev) — add one dependency, your Spring AI agents become durable.

**Crash recovery, event sourcing, and execution tracking for any Spring AI application.**

## Quickstart

### 1. Add the dependency

```xml
<dependency>
    <groupId>dev.jamjet</groupId>
    <artifactId>jamjet-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

### 2. Start the JamJet runtime

```bash
docker run -p 7700:7700 ghcr.io/jamjet-labs/jamjet:latest
```

### 3. Configure

```properties
spring.jamjet.runtime-url=http://localhost:7700
```

That's it. Every `ChatClient` call is now backed by durable execution.

### 4. Use as normal

```java
@SpringBootApplication
public class MyApp {

    @Bean
    ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build(); // JamJet advisor auto-injected
    }

    @Bean
    CommandLineRunner demo(ChatClient chatClient) {
        return args -> {
            String result = chatClient.prompt("Summarize AI trends")
                    .call()
                    .content();
            System.out.println(result);
            // This call is durable — crash recovery enabled
        };
    }
}
```

## What you get

| Feature | How |
|---------|-----|
| **Crash recovery** | Executions resume from last checkpoint after restarts |
| **Event sourcing** | Every LLM call, tool invocation, and state change recorded |
| **Execution tracking** | Query execution status, events, and history via API |
| **Zero code changes** | Auto-configured as a Spring AI `BaseAdvisor` |
| **Graceful degradation** | Falls back to normal operation if runtime is unavailable |

## Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `spring.jamjet.runtime-url` | `http://localhost:7700` | JamJet runtime URL |
| `spring.jamjet.api-token` | — | API token for authentication |
| `spring.jamjet.tenant-id` | `default` | Tenant ID for multi-tenant isolation |
| `spring.jamjet.durability-enabled` | `true` | Enable/disable durable execution |
| `spring.jamjet.connect-timeout-seconds` | `10` | Connection timeout |
| `spring.jamjet.read-timeout-seconds` | `120` | Read timeout |

## Modules

| Module | Description |
|--------|-------------|
| `jamjet-spring-boot-autoconfigure` | Auto-configuration, advisor, and runtime client |
| `jamjet-spring-boot-starter` | Starter POM (add this to your project) |
| `jamjet-spring-boot-starter-example` | Example application |

## Requirements

- Java 21+
- Spring Boot 3.4+
- Spring AI 1.0+
- JamJet runtime (Docker or binary)

## Roadmap

- **Phase 2**: Audit trails + human-in-the-loop approval
- ✓ **Phase 3**: JUnit 5 replay testing + Micrometer/OpenTelemetry observability (COMPLETE)
- ✓ **Phase 4a**: LangChain4j integration (COMPLETE)
- **Phase 4b**: Quarkus and A2A bridge integrations

## Testing (`jamjet-spring-boot-starter-test`)

Add to your test dependencies:

```xml
<dependency>
    <groupId>dev.jamjet</groupId>
    <artifactId>jamjet-spring-boot-starter-test</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <scope>test</scope>
</dependency>
```

**Replay tests** — replay production executions in JUnit 5:

```java
@WithJamjetRuntime
class MyAgentTest {

    @Test
    @ReplayExecution("exec-abc123")
    void agentProducesConsistentOutput(RecordedExecution execution) {
        AgentAssertions.assertThat(execution)
            .completedSuccessfully()
            .usedTool("WebSearch")
            .completedWithin(30, TimeUnit.SECONDS)
            .costLessThan(0.50);
    }
}
```

**Deterministic stubs** — no LLM calls needed:

```java
var stub = DeterministicModelStub.builder()
    .onPromptContaining("weather", "Sunny, 72F")
    .defaultResponse("I don't know")
    .build();
```

## Observability

**Micrometer** (on by default when actuator present):

```properties
spring.jamjet.observability.micrometer=true
spring.jamjet.observability.metric-prefix=jamjet
```

Metrics: `jamjet.execution.duration`, `jamjet.execution.count`, `jamjet.node.duration`, `jamjet.tool.calls`, `jamjet.execution.cost.usd`

**OpenTelemetry** (opt-in):

```properties
spring.jamjet.observability.opentelemetry=true
```

Span hierarchy: `jamjet.execution` → `jamjet.node.{id}` → `jamjet.tool.{name}`

## LangChain4j Integration (`langchain4j-jamjet`)

Add to your dependencies:

```xml
<dependency>
    <groupId>dev.jamjet</groupId>
    <artifactId>langchain4j-jamjet</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

**Durable agents** — wrap any AiServices proxy:

```java
var client = new JamjetConfig()
    .runtimeUrl("http://localhost:7700")
    .buildClient();

MyAssistant raw = AiServices.builder(MyAssistant.class)
    .chatLanguageModel(model).tools(tools).build();

MyAssistant durable = JamjetDurableAgent.wrap(raw, MyAssistant.class, client);
// Every call is now tracked, audited, crash-recoverable
```

**Event-sourced chat memory:**

```java
var store = new JamjetChatMemoryStore(client);
var memory = MessageWindowChatMemory.builder()
    .chatMemoryStore(store)
    .id("session-1")
    .maxMessages(20)
    .build();
```

## License

Apache License 2.0
