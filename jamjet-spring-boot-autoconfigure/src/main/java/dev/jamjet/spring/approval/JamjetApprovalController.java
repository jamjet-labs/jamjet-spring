package dev.jamjet.spring.approval;

import dev.jamjet.spring.client.JamjetClientException;
import dev.jamjet.spring.client.JamjetRuntimeClient;
import dev.jamjet.spring.client.model.ApprovalDecision;
import dev.jamjet.spring.client.model.ApprovalsView;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST endpoints for human approval decisions on paused ChatClient calls.
 *
 * <ul>
 *   <li>{@code GET /jamjet/approvals/pending}: calls waiting in this application,
 *       each with the approval requests the runtime holds for its execution and
 *       the {@code action_digest} a decision must echo.</li>
 *   <li>{@code POST /jamjet/approvals/{executionId}}: body
 *       {@code {decision, user_id?, comment?, node_id?, action_digest?}}.</li>
 * </ul>
 *
 * <p>A decision is recorded on the runtime before the paused call is released:
 * <ul>
 *   <li>if the runtime holds a pending approval for the execution, the decision
 *       goes to {@code POST /executions/{id}/approve}, with the
 *       {@code node_id} and {@code action_digest} the reviewer was shown;</li>
 *   <li>otherwise (the call was paused by {@code JamjetApprovalAdvisor} in this
 *       application) it is appended as a {@code spring_ai.approval_decision}
 *       external event.</li>
 * </ul>
 *
 * <p>If the runtime refuses or cannot be reached, the response carries the
 * runtime's status (4xx) or 502, with the runtime's message, and the paused call
 * is released at once as rejected. It is never left to time out.
 */
@RestController
@RequestMapping("/jamjet/approvals")
public class JamjetApprovalController {

    private static final Logger log = LoggerFactory.getLogger(JamjetApprovalController.class);

    /** Correlation key of the event recording an application-side decision. */
    public static final String DECISION_EVENT = "spring_ai.approval_decision";

    private final JamjetRuntimeClient client;
    private final ApprovalWaitRegistry waitRegistry;

    public JamjetApprovalController(JamjetRuntimeClient client,
                                    ApprovalWaitRegistry waitRegistry) {
        this.client = client;
        this.waitRegistry = waitRegistry;
    }

    @PostMapping("/{executionId}")
    public ResponseEntity<Map<String, Object>> handleApproval(
            @PathVariable String executionId,
            @RequestBody ApprovalDecision decision) {

        if (decision == null || !("approved".equals(decision.decision())
                || "rejected".equals(decision.decision()))) {
            return error(HttpStatus.BAD_REQUEST, executionId,
                    "decision must be \"approved\" or \"rejected\"", null);
        }
        log.info("Received {} for execution {} from {}", decision.decision(), executionId,
                decision.userId() != null ? decision.userId() : "unknown");

        boolean waitingHere = waitRegistry.isPending(executionId);
        ApprovalsView onRuntime;
        try {
            onRuntime = client.listApprovals(executionId);
        } catch (JamjetClientException e) {
            return refused(executionId, waitingHere, e);
        }

        if (!onRuntime.pending().isEmpty()) {
            try {
                var resp = client.approveExecution(executionId, decision);
                boolean released = waitRegistry.complete(executionId, decision);
                var body = new LinkedHashMap<String, Object>();
                body.put("execution_id", executionId);
                body.put("decision", decision.decision());
                body.put("node_id", resp != null ? resp.nodeId() : decision.nodeId());
                body.put("accepted", resp != null && resp.accepted());
                body.put("recorded_as", "runtime_approval");
                body.put("released_waiting_call", released);
                return ResponseEntity.ok(body);
            } catch (JamjetClientException e) {
                return refused(executionId, waitingHere, e);
            }
        }

        if (!waitingHere) {
            return error(HttpStatus.NOT_FOUND, executionId,
                    "no pending approval for execution " + executionId
                            + ": no call is waiting in this application and the runtime holds no request",
                    null);
        }

        try {
            var payload = new LinkedHashMap<String, Object>();
            payload.put("decision", decision.decision());
            if (decision.userId() != null) {
                payload.put("user_id", decision.userId());
            }
            if (decision.comment() != null) {
                payload.put("comment", decision.comment());
            }
            client.sendExternalEvent(executionId, DECISION_EVENT, payload);
        } catch (JamjetClientException e) {
            return refused(executionId, true, e);
        }
        boolean released = waitRegistry.complete(executionId, decision);
        var body = new LinkedHashMap<String, Object>();
        body.put("execution_id", executionId);
        body.put("decision", decision.decision());
        body.put("accepted", true);
        body.put("recorded_as", "external_event");
        body.put("released_waiting_call", released);
        return ResponseEntity.ok(body);
    }

    /**
     * Calls waiting in this application, each with the runtime's pending
     * requests for its execution and the digest a decision must echo.
     */
    @GetMapping("/pending")
    public ResponseEntity<List<Map<String, Object>>> listPending() {
        var out = new ArrayList<Map<String, Object>>();
        for (var waiting : waitRegistry.allPending()) {
            var item = new LinkedHashMap<String, Object>();
            item.put("execution_id", waiting.executionId());
            item.put("waiting_since", waiting.waitingSince().toString());
            try {
                var requests = new ArrayList<Map<String, Object>>();
                for (PendingApproval p : client.listApprovals(waiting.executionId()).pending()) {
                    var request = new LinkedHashMap<String, Object>();
                    request.put("node_id", p.nodeId());
                    request.put("tool_name", p.toolName());
                    request.put("approver", p.approver());
                    request.put("action_digest", p.actionDigest());
                    request.put("context", p.context());
                    requests.add(request);
                }
                item.put("runtime_requests", requests);
            } catch (JamjetClientException e) {
                item.put("runtime_error", e.getRuntimeMessage());
            }
            out.add(item);
        }
        return ResponseEntity.ok(out);
    }

    /** Release the waiting call as rejected and answer with the runtime's error. */
    private ResponseEntity<Map<String, Object>> refused(String executionId, boolean waitingHere,
                                                        JamjetClientException e) {
        String message = e.getRuntimeMessage();
        log.warn("Runtime refused the decision for execution {}: {}", executionId, message);
        if (waitingHere) {
            waitRegistry.complete(executionId,
                    ApprovalDecision.rejected("jamjet", "the runtime refused the decision: " + message));
        }
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
