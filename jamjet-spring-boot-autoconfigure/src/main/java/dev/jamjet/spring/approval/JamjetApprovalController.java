package dev.jamjet.spring.approval;

import dev.jamjet.spring.client.JamjetClientException;
import dev.jamjet.spring.client.JamjetRuntimeClient;
import dev.jamjet.spring.client.model.ApprovalDecision;
import dev.jamjet.spring.client.model.PendingApproval;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST endpoints for human decisions on ChatClient calls paused by
 * {@code JamjetApprovalAdvisor} in this application.
 *
 * <ul>
 *   <li>{@code GET /jamjet/approvals/pending}: calls waiting here, plus, read-only,
 *       any approval requests the runtime itself holds for their executions.</li>
 *   <li>{@code POST /jamjet/approvals/{executionId}}: body {@code {decision, comment?}}.</li>
 * </ul>
 *
 * <p><b>Who decides.</b> The approver is the authenticated principal of the
 * request ({@link Principal}, populated by Spring Security). A request with no
 * principal gets 401 and releases nothing. The body cannot name the approver. The
 * principal that triggered the call, when known, cannot approve it (403).
 * Without Spring Security the controller is only registered when
 * {@code spring.jamjet.approval.controller.allow-unauthenticated=true}, which is for
 * local development: decisions are then recorded as unauthenticated.
 *
 * <p><b>What it never does.</b> It never decides an approval the runtime holds.
 * The runtime binds a decision to the token that sends it and enforces separation
 * of duties on that token, so a decision forwarded with this application's own
 * token would be attributed to the application, not the reviewer. Reviewers
 * decide runtime-held requests with their own runtime token; {@code /pending}
 * shows the command.
 *
 * <p>A decision is recorded on the call's execution as a
 * {@code spring_ai.approval_decision} event before the call is released. If it
 * cannot be recorded, the response carries the runtime's status (4xx) or 502 with
 * its message, and the call is released as rejected.
 */
@RestController
@RequestMapping("/jamjet/approvals")
public class JamjetApprovalController {

    private static final Logger log = LoggerFactory.getLogger(JamjetApprovalController.class);

    /** Correlation key of the event recording a decision. */
    public static final String DECISION_EVENT = "spring_ai.approval_decision";

    /** Approver recorded for a decision made without authentication (local development only). */
    public static final String UNAUTHENTICATED = "unauthenticated";

    private final JamjetRuntimeClient client;
    private final ApprovalWaitRegistry waitRegistry;
    private final boolean allowUnauthenticated;

    public JamjetApprovalController(JamjetRuntimeClient client, ApprovalWaitRegistry waitRegistry) {
        this(client, waitRegistry, false);
    }

    public JamjetApprovalController(JamjetRuntimeClient client, ApprovalWaitRegistry waitRegistry,
                                    boolean allowUnauthenticated) {
        this.client = client;
        this.waitRegistry = waitRegistry;
        this.allowUnauthenticated = allowUnauthenticated;
    }

    @PostMapping("/{executionId}")
    public ResponseEntity<Map<String, Object>> handleApproval(
            @PathVariable String executionId,
            @RequestBody ReviewDecision decision,
            Principal principal) {

        String approver = principal == null || principal.getName() == null || principal.getName().isBlank()
                ? null : principal.getName();
        if (approver == null && !allowUnauthenticated) {
            return error(HttpStatus.UNAUTHORIZED, executionId,
                    "an authenticated reviewer is required to decide an approval", null);
        }
        if (decision == null || !("approved".equals(decision.decision())
                || "rejected".equals(decision.decision()))) {
            return error(HttpStatus.BAD_REQUEST, executionId,
                    "decision must be \"approved\" or \"rejected\"", null);
        }

        var waiting = waitRegistry.context(executionId);
        if (waiting == null) {
            return error(HttpStatus.NOT_FOUND, executionId,
                    "no call is waiting for a decision on execution " + executionId + " in this application. "
                            + "An approval the runtime holds is decided with your own runtime token, never "
                            + "through this endpoint: see GET /jamjet/approvals/pending for the command", null);
        }
        if (approver != null && approver.equals(waiting.initiator()) && "approved".equals(decision.decision())) {
            return error(HttpStatus.FORBIDDEN, executionId,
                    "separation of duties: " + approver + " triggered this call and cannot approve it", null);
        }

        String recordedApprover = approver != null ? approver : UNAUTHENTICATED;
        log.info("Received {} for execution {} from {}", decision.decision(), executionId, recordedApprover);
        try {
            var payload = new LinkedHashMap<String, Object>();
            payload.put("decision", decision.decision());
            payload.put("approver", recordedApprover);
            payload.put("authenticated", approver != null);
            if (waiting.initiator() != null) {
                payload.put("initiator", waiting.initiator());
            }
            if (decision.comment() != null) {
                payload.put("comment", decision.comment());
            }
            client.sendExternalEvent(executionId, DECISION_EVENT, payload);
        } catch (JamjetClientException e) {
            return refused(executionId, e);
        }

        var forCall = new ApprovalDecision(decision.decision(), null, recordedApprover, decision.comment(), null);
        boolean released = waitRegistry.complete(executionId, forCall);
        var body = new LinkedHashMap<String, Object>();
        body.put("execution_id", executionId);
        body.put("decision", decision.decision());
        body.put("approver", recordedApprover);
        body.put("recorded_as", "external_event");
        body.put("released_waiting_call", released);
        return ResponseEntity.ok(body);
    }

    /**
     * Calls waiting in this application. For each, any approval request the
     * runtime holds for its execution is listed read-only, with the command a
     * reviewer runs with their own runtime token to decide it.
     */
    @GetMapping("/pending")
    public ResponseEntity<List<Map<String, Object>>> listPending() {
        var out = new ArrayList<Map<String, Object>>();
        for (var waiting : waitRegistry.allPending()) {
            var item = new LinkedHashMap<String, Object>();
            item.put("execution_id", waiting.executionId());
            item.put("waiting_since", waiting.waitingSince().toString());
            if (waiting.context().initiator() != null) {
                item.put("initiator", waiting.context().initiator());
            }
            try {
                var requests = new ArrayList<Map<String, Object>>();
                for (PendingApproval p : client.listApprovals(waiting.executionId()).pending()) {
                    var request = new LinkedHashMap<String, Object>();
                    request.put("node_id", p.nodeId());
                    request.put("tool_name", p.toolName());
                    request.put("approver", p.approver());
                    request.put("action_digest", p.actionDigest());
                    request.put("context", p.context());
                    request.put("decide_with", "jamjet approve " + waiting.executionId()
                            + " --decision approved --node-id " + p.nodeId()
                            + (p.actionDigest() != null ? " --action-digest " + p.actionDigest() : ""));
                    requests.add(request);
                }
                if (!requests.isEmpty()) {
                    item.put("runtime_requests", requests);
                    item.put("runtime_requests_note", "Held by the runtime, not by this application. Decide them "
                            + "with your own runtime token (the jamjet CLI or POST /executions/{id}/approve): the "
                            + "runtime records the token's identity as the approver and enforces separation of "
                            + "duties on it, so this application never decides them with its own token.");
                }
            } catch (JamjetClientException e) {
                item.put("runtime_error", e.getRuntimeMessage());
            }
            out.add(item);
        }
        return ResponseEntity.ok(out);
    }

    /** Release the waiting call as rejected and answer with the runtime's error. */
    private ResponseEntity<Map<String, Object>> refused(String executionId, JamjetClientException e) {
        String message = e.getRuntimeMessage();
        log.warn("Could not record the decision for execution {}: {}", executionId, message);
        waitRegistry.complete(executionId,
                ApprovalDecision.rejected("jamjet", "the decision could not be recorded: " + message));
        HttpStatus status = e.isClientError() ? HttpStatus.valueOf(e.getStatusCode()) : HttpStatus.BAD_GATEWAY;
        return error(status, executionId, message, e.getStatusCode() > 0 ? e.getStatusCode() : null);
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String executionId,
                                                             String message, Integer runtimeStatus) {
        var body = new LinkedHashMap<String, Object>();
        body.put("execution_id", executionId);
        body.put("error", message);
        if (runtimeStatus != null) {
            body.put("runtime_status", runtimeStatus);
        }
        return ResponseEntity.status(status).body(body);
    }
}
