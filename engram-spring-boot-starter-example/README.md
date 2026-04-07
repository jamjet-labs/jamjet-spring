# Engram Spring Boot Starter — Example App

A reference Spring Boot application demonstrating how to give your AI agents persistent memory using **Engram** + **Spring AI**.

This is a minimal customer support bot that remembers user preferences and facts across conversations — without you writing a single line of memory code.

## What it does

- **POST /chat** accepts a `user_id` and a `message`
- Calls OpenAI via Spring AI's `ChatClient`
- The `EngramContextAdvisor` (auto-wired) injects relevant memory facts into the prompt
- The exchange is persisted back into Engram for future calls
- **GET /actuator/health** includes Engram's health under the `engram` key

The whole "memory" of the bot is one annotation: `@SpringBootApplication`. The Engram starter wires everything for you.

## Prerequisites

1. **An Engram server running** in REST mode:
   ```bash
   cargo install jamjet-engram-server
   engram serve --mode rest --port 9090 --db ~/.engram/example.db
   ```

2. **An OpenAI API key:**
   ```bash
   export OPENAI_API_KEY=sk-...
   ```

3. **Java 21+** and Maven 3.9+

## Run it

```bash
cd engram-spring-boot-starter-example
mvn spring-boot:run
```

## Try it

```bash
# First message — teach the bot about Alice
curl -X POST localhost:8080/chat \
  -H "Content-Type: application/json" \
  -d '{"user_id": "alice", "message": "I prefer vegetarian food and live in Austin"}'

# Second message — different topic, but the bot remembers Alice
curl -X POST localhost:8080/chat \
  -H "Content-Type: application/json" \
  -d '{"user_id": "alice", "message": "What should I eat for dinner tonight?"}'
```

The second response should reflect Alice's vegetarian preference and Austin location — without you passing them again. That's the Engram context advisor at work.

## Health check

```bash
curl http://localhost:8080/actuator/health
```

You should see something like:

```json
{
  "status": "UP",
  "components": {
    "engram": {
      "status": "UP",
      "details": {
        "service": "engram"
      }
    }
  }
}
```

## What's auto-wired for you

When you add the dependency:

```xml
<dependency>
    <groupId>dev.jamjet</groupId>
    <artifactId>engram-spring-boot-starter</artifactId>
    <version>0.1.1</version>
</dependency>
```

Spring Boot auto-configures:

| Bean | What it does |
|------|--------------|
| `EngramClient` | HTTP client for the Engram REST API |
| `EngramContextAdvisor` | Spring AI advisor that injects memory into prompts |
| `ChatClientCustomizer` | Wires the advisor into every ChatClient |
| `EngramHealthIndicator` | Spring Boot Actuator health (if Actuator is on the classpath) |

You can disable any of them via `application.yml`:

```yaml
engram:
  enabled: false                # disable everything
  health:
    enabled: false              # disable just the health indicator
  spring-ai:
    auto-wire: false            # keep the advisor bean but don't auto-attach it
```

## Configuration reference

```yaml
engram:
  enabled: true                              # default true
  base-url: http://localhost:9090            # Engram REST server URL
  api-token: ${ENGRAM_TOKEN:}                # optional Bearer token
  timeout-seconds: 30                        # HTTP timeout
  default-user-id: alice                     # used when chat requests don't pass a user ID
  default-org-id: default                    # multi-tenant scope
  health:
    enabled: true                            # Actuator health indicator
  spring-ai:
    auto-wire: true                          # attach advisor to default ChatClient builder
```

## How the advisor decides what memory to inject

Per request, the advisor:

1. Reads the user prompt text
2. Reads the user ID from `engram.user.id` advisor param (or `default-user-id`)
3. Calls `EngramClient.context(query, userId, orgId, tokenBudget, "system_prompt")`
4. Prepends the returned XML-tagged memory block as a system message

You can override per-call:

```java
String response = chatClient.prompt()
    .user(message)
    .advisors(a -> a
        .param(EngramContextAdvisor.USER_ID_KEY, "alice")
        .param(EngramContextAdvisor.TOKEN_BUDGET_KEY, 2000)
    )
    .call()
    .content();
```

## Learn more

- Engram comparison & landscape: [java-ai-memory.dev](https://java-ai-memory.dev)
- JamJet docs: [docs.jamjet.dev](https://docs.jamjet.dev)
- Source: [github.com/jamjet-labs/jamjet-spring](https://github.com/jamjet-labs/jamjet-spring)
