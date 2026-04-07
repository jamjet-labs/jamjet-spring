package dev.jamjet.engram.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Reference Spring Boot application demonstrating Engram + Spring AI integration.
 *
 * <p>This is a customer support bot that:
 * <ul>
 *   <li>Receives chat messages from users via REST</li>
 *   <li>Automatically retrieves relevant memory via the {@link
 *       dev.jamjet.engram.spring.EngramContextAdvisor}</li>
 *   <li>Calls OpenAI through Spring AI's ChatClient</li>
 *   <li>Stores new conversation facts back into Engram for next time</li>
 * </ul>
 *
 * <p>Running this requires:
 * <ol>
 *   <li>An Engram server: {@code engram serve --mode rest --port 9090}</li>
 *   <li>An OpenAI API key: {@code export OPENAI_API_KEY=sk-...}</li>
 *   <li>{@code mvn -pl engram-spring-boot-starter-example spring-boot:run}</li>
 * </ol>
 *
 * <p>Then chat:
 * <pre>{@code
 * curl -X POST localhost:8080/chat \
 *   -H "Content-Type: application/json" \
 *   -d '{"user_id": "alice", "message": "I prefer vegetarian food"}'
 *
 * curl -X POST localhost:8080/chat \
 *   -H "Content-Type: application/json" \
 *   -d '{"user_id": "alice", "message": "what should I eat for dinner?"}'
 * }</pre>
 *
 * <p>The second call will know about the dietary preference because Engram
 * extracted and stored it during the first call, and the
 * EngramContextAdvisor injected it into the second prompt.
 */
@SpringBootApplication
public class SupportBotApplication {

    public static void main(String[] args) {
        SpringApplication.run(SupportBotApplication.class, args);
    }
}
