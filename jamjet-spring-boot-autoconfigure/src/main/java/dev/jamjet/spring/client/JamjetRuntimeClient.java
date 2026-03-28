package dev.jamjet.spring.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.jamjet.spring.JamjetProperties;
import dev.jamjet.spring.client.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * HTTP client for the JamJet runtime REST API.
 *
 * <p>Virtual-thread-friendly — uses {@link java.net.http.HttpClient} with a
 * virtual-thread-per-task executor. Safe to call from platform threads,
 * virtual threads, or structured concurrency scopes.
 */
public class JamjetRuntimeClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(JamjetRuntimeClient.class);
    private static final String USER_AGENT = "jamjet-spring-boot-starter/0.1.0";

    private final JamjetProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public JamjetRuntimeClient(JamjetProperties properties) {
        this.properties = properties;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.httpClient = HttpClient.newBuilder()
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .connectTimeout(Duration.ofSeconds(properties.getConnectTimeoutSeconds()))
                .build();
    }

    // ── Health ────────────────────────────────────────────────────────────────

    /** Check if the JamJet runtime is healthy and reachable. */
    public boolean isHealthy() {
        try {
            var result = get("/health", new TypeReference<Map<String, Object>>() {});
            return "ok".equals(result.get("status"));
        } catch (Exception e) {
            log.debug("Health check failed", e);
            return false;
        }
    }

    // ── Workflows ─────────────────────────────────────────────────────────────

    /** Create a workflow from a canonical IR map. */
    public CreateWorkflowResponse createWorkflow(Map<String, Object> ir) {
        var request = new CreateWorkflowRequest(ir);
        return post("/api/v1/workflows", request, CreateWorkflowResponse.class);
    }

    // ── Executions ────────────────────────────────────────────────────────────

    /** Start a new workflow execution. */
    public StartExecutionResponse startExecution(String workflowId, String version, Map<String, Object> input) {
        var request = new StartExecutionRequest(workflowId, version, input);
        return post("/api/v1/executions", request, StartExecutionResponse.class);
    }

    /** Get the current state of an execution. */
    public ExecutionState getExecution(String executionId) {
        return get("/api/v1/executions/" + executionId, ExecutionState.class);
    }

    /** Cancel a running execution. */
    public void cancelExecution(String executionId) {
        post("/api/v1/executions/" + executionId + "/cancel", Map.of(),
                new TypeReference<Map<String, Object>>() {});
    }

    /** Get the event stream for an execution. */
    public List<ExecutionEvent> listEvents(String executionId) {
        var result = get("/api/v1/executions/" + executionId + "/events",
                new TypeReference<Map<String, Object>>() {});
        @SuppressWarnings("unchecked")
        var rawEvents = (List<Map<String, Object>>) result.get("events");
        if (rawEvents == null) {
            return List.of();
        }
        return rawEvents.stream()
                .map(e -> mapper.convertValue(e, ExecutionEvent.class))
                .toList();
    }

    // ── Audit ──────────────────────────────────────────────────────────────────

    /** Query audit log entries with optional filters. */
    public AuditPage queryAudit(String executionId, String actorId, String eventType,
                                int limit, int offset) {
        var sb = new StringBuilder("/api/v1/audit?limit=").append(limit).append("&offset=").append(offset);
        if (executionId != null && !executionId.isBlank()) {
            sb.append("&execution_id=").append(executionId);
        }
        if (actorId != null && !actorId.isBlank()) {
            sb.append("&actor_id=").append(actorId);
        }
        if (eventType != null && !eventType.isBlank()) {
            sb.append("&event_type=").append(eventType);
        }
        return get(sb.toString(), AuditPage.class);
    }

    /** Send an external event (used for audit logging from advisors). */
    public void sendExternalEvent(String executionId, String correlationKey,
                                  Map<String, Object> payload) {
        post("/api/v1/executions/" + executionId + "/external-event",
                Map.of("correlation_key", correlationKey, "payload", payload),
                new TypeReference<Map<String, Object>>() {});
    }

    // ── Approval ──────────────────────────────────────────────────────────────

    /** Approve or reject a paused execution. */
    public ApproveResponse approveExecution(String executionId, ApprovalDecision decision) {
        return post("/api/v1/executions/" + executionId + "/approve", decision,
                ApproveResponse.class);
    }

    // ── Internal HTTP helpers ─────────────────────────────────────────────────

    private <T> T get(String path, Class<T> responseType) {
        return get(path, mapper.getTypeFactory().constructType(responseType));
    }

    private <T> T get(String path, TypeReference<T> typeRef) {
        return get(path, mapper.getTypeFactory().constructType(typeRef));
    }

    private <T> T get(String path, com.fasterxml.jackson.databind.JavaType type) {
        var request = buildRequest(path).GET().build();
        return send(request, path, type);
    }

    private <T> T post(String path, Object body, Class<T> responseType) {
        return post(path, body, mapper.getTypeFactory().constructType(responseType));
    }

    private <T> T post(String path, Object body, TypeReference<T> typeRef) {
        return post(path, body, mapper.getTypeFactory().constructType(typeRef));
    }

    private <T> T post(String path, Object body, com.fasterxml.jackson.databind.JavaType type) {
        try {
            var json = mapper.writeValueAsBytes(body);
            var request = buildRequest(path)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json))
                    .header("Content-Type", "application/json")
                    .build();
            return send(request, path, type);
        } catch (IOException e) {
            throw new JamjetClientException("Failed to serialize request body for " + path, e);
        }
    }

    private HttpRequest.Builder buildRequest(String path) {
        var uri = URI.create(properties.getRuntimeUrl() + path);
        var builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(properties.getReadTimeoutSeconds()))
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT);
        if (properties.getApiToken() != null && !properties.getApiToken().isBlank()) {
            builder.header("Authorization", "Bearer " + properties.getApiToken());
        }
        if (properties.getTenantId() != null && !properties.getTenantId().isBlank()) {
            builder.header("X-Tenant-Id", properties.getTenantId());
        }
        return builder;
    }

    private <T> T send(HttpRequest request, String path, com.fasterxml.jackson.databind.JavaType type) {
        try {
            log.debug("-> {} {}", request.method(), path);
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            String body = response.body();
            log.debug("<- {} {} ({} bytes)", status, path, body != null ? body.length() : 0);

            if (status < 200 || status >= 300) {
                throw new JamjetClientException(status, body, path);
            }

            if (body == null || body.isBlank()) {
                return null;
            }
            return mapper.readValue(body, type);
        } catch (JamjetClientException e) {
            throw e;
        } catch (java.net.http.HttpTimeoutException e) {
            throw new JamjetClientException("Request timed out: " + path, e);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new JamjetClientException("HTTP request failed: " + path, e);
        }
    }

    @Override
    public void close() {
        // HttpClient managed by JVM GC; virtual-thread executor has no pool to shut down
    }
}
