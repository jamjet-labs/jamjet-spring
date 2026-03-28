package dev.jamjet.example;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Example Spring Boot application demonstrating JamJet durable execution.
 *
 * <p>The JamJet starter auto-configures a {@code JamjetDurabilityAdvisor}
 * that wraps every ChatClient call with durable execution — crash recovery,
 * event sourcing, and execution tracking come for free.
 *
 * <pre>{@code
 * # Start JamJet runtime
 * docker run -p 7700:7700 ghcr.io/jamjet-labs/jamjet:latest
 *
 * # Run this application
 * OPENAI_API_KEY=sk-... mvn spring-boot:run -pl jamjet-spring-boot-starter-example
 * }</pre>
 */
@SpringBootApplication
public class DurableAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(DurableAgentApplication.class, args);
    }

    @Bean
    ChatClient chatClient(ChatClient.Builder builder) {
        // JamjetDurabilityAdvisor is auto-injected via ChatClientCustomizer — no manual setup
        return builder.build();
    }

    @Bean
    CommandLineRunner demo(ChatClient chatClient) {
        return args -> {
            System.out.println("=== JamJet Durable Agent Demo ===\n");

            String result = chatClient.prompt("Summarize the latest trends in AI agents in 3 bullet points")
                    .call()
                    .content();

            System.out.println(result);
            System.out.println("\n=== This call was durable — crash recovery and event sourcing enabled ===");
        };
    }
}
