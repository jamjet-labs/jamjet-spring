package dev.jamjet.spring.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.jamjet.spring.JamjetProperties;
import dev.jamjet.spring.client.model.ApprovalDecision;
import dev.jamjet.spring.client.model.ApprovalsView;
import dev.jamjet.spring.client.model.ApproveResponse;
import dev.jamjet.spring.client.model.AuditPage;
import dev.jamjet.spring.client.model.CreateWorkflowRequest;
import dev.jamjet.spring.client.model.CreateWorkflowResponse;
import dev.jamjet.spring.client.model.ExecutionEvent;
import dev.jamjet.spring.client.model.ExecutionState;
import dev.jamjet.spring.client.model.StartExecutionRequest;
import dev.jamjet.spring.client.model.StartExecutionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * HTTP client for the JamJet runtime REST API.
 *
 * <p>The runtime serves <strong>bare</strong> routes ({@code /workflows},
 * {@code /executions}, {@code /audit}); it has never served {@code /api/v1}.
 * Every path and body here matches {@code runtime/api/src/routes.rs}, and the
 * same contract as jamjet-runtime-java's {@code JamjetEngineClient} and the
 * Python {@code jamjet.client.JamjetClient}.
 *
 * <p>Any answer that is not the runtime's JSON is an error. The runtime answers
 * paths it does not route with its web UI ({@code 200 text/html}), so a wrong
 * {@code spring.jamjet.runtime-url} or a route mismatch surfaces here as a
 * {@link JamjetClientException} instead of a silently empty result.
 *
 * <p>Virtual-thread-friendly: uses {@link java.net.http.HttpClient} with a
 * virtual-thread-per-task executor.
 */
public class JamjetRuntimeClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(JamjetRuntimeClient.class);
    private static final String USER_AGENT = "jamjet-spring-boot-starter/0.2.0";

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

    /** {@code GET /health}: true when the runtime answers {@code {"status":"ok"}}. */
    public boolean isHealthy() {
        try {
            var result = get("/health", new TypeReference<Map<String, Object>>() {});
            return result != null && "ok".equals(result.get("status"));
        } catch (Exception e) {
            log.debug("Health check failed", e);
            return false;
        }
    }

    // ── Workflows ─────────────────────────────────────────────────────────────

    /**
     * {@code POST /workflows} with body {@code {"ir": ir}}. The runtime requires
     * {@code ir.workflow_id} and {@code ir.version} and refuses IR it cannot load.
     * Re-registering a byte-identical IR is a no-op.
     */
    public CreateWorkflowResponse createWorkflow(Map<String, Object> ir) {
        return post("/workflows", new CreateWorkflowRequest(ir), CreateWorkflowResponse.class);
    }

    // ── Executions ────────────────────────────────────────────────────────────

    /**
     * {@code POST /executions} with body {@code {workflow_id, workflow_version?, input}}.
     * A null {@code version} is omitted and the runtime uses {@code "1.0.0"}.
     */
    public StartExecutionResponse startExecution(String workflowId, String version, Map<String, Object> input) {
        var request = new StartExecutionRequest(workflowId, version, input == null ? Map.of() : input);
        return post("/executions", request, StartExecutionResponse.class);
    }

    /** {@code GET /executions/{id}}. */
    public ExecutionState getExecution(String executionId) {
        return get("/executions/" + segment(executionId), ExecutionState.class);
    }

    /**
     * {@code GET /executions?limit&offset[&status]}. {@code status} is one of
     * {@code running}, {@code paused}, {@code completed}, {@code failed}, or null for all.
     */
    public List<ExecutionState> listExecutions(String status, int limit, int offset) {
        var sb = new StringBuilder("/executions?limit=").append(limit).append("&offset=").append(offset);
        if (status != null && !status.isBlank()) {
            sb.append("&status=").append(query(status));
        }
        var result = get(sb.toString(), new TypeReference<Map<String, List<ExecutionState>>>() {});
        var executions = result == null ? null : result.get("executions");
        return executions == null ? List.of() : executions;
    }

    /** {@code POST /executions/{id}/cancel}. */
    public void cancelExecution(String executionId) {
        post("/executions/" + segment(executionId) + "/cancel", Map.of(),
                new TypeReference<Map<String, Object>>() {});
    }

    /** {@code GET /executions/{id}/events}: the execution's event log, in sequence order. */
    public List<ExecutionEvent> listEvents(String executionId) {
        var result = get("/executions/" + segment(executionId) + "/events",
                new TypeReference<Map<String, List<ExecutionEvent>>>() {});
        var events = result == null ? null : result.get("events");
        return events == null ? List.of() : events;
    }

    /**
     * {@code POST /executions/{id}/external-event} with body
     * {@code {correlation_key, payload}}. Appends an {@code external_event_received}
     * event to the execution's log.
     */
    public void sendExternalEvent(String executionId, String correlationKey,
                                  Map<String, Object> payload) {
        post("/executions/" + segment(executionId) + "/external-event",
                Map.of("correlation_key", correlationKey, "payload", payload == null ? Map.of() : payload),
                new TypeReference<Map<String, Object>>() {});
    }

    // ── Audit ──────────────────────────────────────────────────────────────────

    /** {@code GET /audit?limit&offset[&execution_id][&actor_id][&event_type]}. */
    public AuditPage queryAudit(String executionId, String actorId, String eventType,
                                int limit, int offset) {
        var sb = new StringBuilder("/audit?limit=").append(limit).append("&offset=").append(offset);
        if (executionId != null && !executionId.isBlank()) {
            sb.append("&execution_id=").append(query(executionId));
        }
        if (actorId != null && !actorId.isBlank()) {
            sb.append("&actor_id=").append(query(actorId));
        }
        if (eventType != null && !eventType.isBlank()) {
            sb.append("&event_type=").append(query(eventType));
        }
        return get(sb.toString(), AuditPage.class);
    }

    // ── Approval ──────────────────────────────────────────────────────────────

    /**
     * {@code GET /executions/{id}/approvals}: pending and decided approval
     * requests. Each pending request carries the digest a decision must echo
     * ({@link dev.jamjet.spring.client.model.PendingApproval#actionDigest()}).
     */
    public ApprovalsView listApprovals(String executionId) {
        return get("/executions/" + segment(executionId) + "/approvals", ApprovalsView.class);
    }

    /**
     * {@code POST /executions/{id}/approve} with body
     * {@code {decision, node_id?, user_id?, comment?, action_digest?}}.
     */
    public ApproveResponse approveExecution(String executionId, ApprovalDecision decision) {
        return post("/executions/" + segment(executionId) + "/approve", decision,
                ApproveResponse.class);
    }

    // ── Internal HTTP helpers ─────────────────────────────────────────────────

    private <T> T get(String path, Class<T> responseType) {
        return get(path, mapper.getTypeFactory().constructType(responseType));
    }

    private <T> T get(String path, TypeReference<T> typeRef) {
        return get(path, mapper.getTypeFactory().constructType(typeRef));
    }

    private <T> T get(String path, JavaType type) {
        var request = buildRequest(path).GET().build();
        return send(request, path, type);
    }

    private <T> T post(String path, Object body, Class<T> responseType) {
        return post(path, body, mapper.getTypeFactory().constructType(responseType));
    }

    private <T> T post(String path, Object body, TypeReference<T> typeRef) {
        return post(path, body, mapper.getTypeFactory().constructType(typeRef));
    }

    private <T> T post(String path, Object body, JavaType type) {
        byte[] json;
        try {
            json = mapper.writeValueAsBytes(body);
        } catch (IOException e) {
            throw new JamjetClientException("Failed to serialize request body for " + path, e);
        }
        var request = buildRequest(path)
                .POST(HttpRequest.BodyPublishers.ofByteArray(json))
                .header("Content-Type", "application/json")
                .build();
        return send(request, path, type);
    }

    private HttpRequest.Builder buildRequest(String path) {
        var uri = URI.create(stripTrailingSlash(properties.getRuntimeUrl()) + path);
        var builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(properties.getReadTimeoutSeconds()))
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT);
        if (properties.getApiToken() != null && !properties.getApiToken().isBlank()) {
            builder.header("Authorization", "Bearer " + properties.getApiToken());
        }
        return builder;
    }

    private <T> T send(HttpRequest request, String path, JavaType type) {
        HttpResponse<String> response;
        try {
            log.debug("-> {} {}", request.method(), path);
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (java.net.http.HttpTimeoutException e) {
            throw new JamjetClientException("Request to the JamJet runtime timed out: " + path, e);
        } catch (IOException e) {
            throw new JamjetClientException("Could not reach the JamJet runtime at "
                    + properties.getRuntimeUrl() + " (" + path + "): " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JamjetClientException("Interrupted calling the JamJet runtime: " + path, e);
        }

        int status = response.statusCode();
        String body = response.body();
        log.debug("<- {} {} ({} bytes)", status, path, body != null ? body.length() : 0);

        if (status < 200 || status >= 300) {
            throw new JamjetClientException(status, body, path);
        }

        // The runtime's SPA fallback answers any path it does not route with
        // 200 text/html. Treat every non-JSON answer as a failure, never as data.
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        if (!contentType.toLowerCase(Locale.ROOT).contains("json")) {
            throw new JamjetClientException(
                    "Not a JamJet runtime API response for " + request.method() + " " + path
                            + ": got " + status + " " + (contentType.isEmpty() ? "(no content type)" : contentType)
                            + ". Check spring.jamjet.runtime-url (" + properties.getRuntimeUrl()
                            + ") points at the JamJet runtime, which serves bare routes such as /executions.",
                    path);
        }

        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(body, type);
        } catch (IOException e) {
            throw new JamjetClientException("Could not parse the JamJet runtime response for "
                    + path + ": " + e.getMessage(), e);
        }
    }

    private static String segment(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String query(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String stripTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    @Override
    public void close() {
        // HttpClient is managed by the JVM; the virtual-thread executor has no pool to drain.
    }
}
