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
 *   <li>{@code POST /jamjet/approvals/{executionId}}: body {@code {decision, content_digest, comment?}}.
 *       The digest binds the decision to the prompt the reviewer was shown.</li>
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

    /** Correlation key of the event recording that a recorded decision did not take effect. */
    public static final String VOID_EVENT = "spring_ai.approval_decision_void";

    /** Approver recorded for a decision made without authentication (local development only). */
    public static final String UNAUTHENTICATED = "unauthenticated";

    private final JamjetRuntimeClient client;
    private final ApprovalWaitRegistry waitRegistry;
    private final ReviewPolicy policy;

    public JamjetApprovalController(JamjetRuntimeClient client, ApprovalWaitRegistry waitRegistry) {
        this(client, waitRegistry, ReviewPolicy.defaults());
    }

    public JamjetApprovalController(JamjetRuntimeClient client, ApprovalWaitRegistry waitRegistry,
                                    ReviewPolicy policy) {
        this.client = client;
        this.waitRegistry = waitRegistry;
        this.policy = policy == null ? ReviewPolicy.defaults() : policy;
    }

    @PostMapping("/{executionId}")
    public ResponseEntity<Map<String, Object>> handleApproval(
            @PathVariable String executionId,
            @RequestBody ReviewDecision decision,
            Principal principal) {

        var reviewer = Reviewers.check(principal, policy);
        if (!reviewer.accepted()) {
            return error(HttpStatus.valueOf(reviewer.status()), executionId, reviewer.reason(), null);
        }
        boolean authenticated = reviewer.authenticated();
        String approver = reviewer.name();
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
        if (decision.contentDigest() == null || decision.contentDigest().isBlank()) {
            return error(HttpStatus.BAD_REQUEST, executionId, "content_digest is required: echo the "
                    + "content_digest GET /jamjet/approvals/pending showed for the call you reviewed", null);
        }
        if (!decision.contentDigest().equals(waiting.contentDigest())) {
            return error(HttpStatus.CONFLICT, executionId, "content_digest does not match the waiting call; "
                    + "re-read GET /jamjet/approvals/pending and decide again", null);
        }
        if (waiting.initiator() == null && "approved".equals(decision.decision())
                && !policy.allowUnknownInitiator()) {
            return error(HttpStatus.FORBIDDEN, executionId, "separation of duties: who triggered this call is "
                    + "unknown, so it cannot be approved (it can be rejected). Set "
                    + "spring.jamjet.approval.allow-unknown-initiator=true to approve such calls.", null);
        }
        if (approver.equals(waiting.initiator()) && "approved".equals(decision.decision())) {
            return error(HttpStatus.FORBIDDEN, executionId,
                    "separation of duties: " + approver + " triggered this call and cannot approve it", null);
        }

        if (!waitRegistry.claim(executionId)) {
            return error(HttpStatus.CONFLICT, executionId,
                    "the call on execution " + executionId + " is already being decided or no longer waiting; "
                            + "nothing was recorded", null);
        }

        // From here the call is claimed: every exit must release it.
        try {
            return decideClaimed(executionId, decision, approver, authenticated, waiting);
        } catch (RuntimeException unexpected) {
            log.error("Unexpected failure deciding execution {}; rejecting the call", executionId, unexpected);
            waitRegistry.complete(executionId, ApprovalDecision.rejected("jamjet",
                    "the decision could not be processed: " + unexpected.getMessage()));
            try {
                voidDecision(executionId, approver, decision.decision(),
                        "the decision could not be processed and the call was rejected: " + unexpected.getMessage());
            } catch (RuntimeException ignored) {
                // already logged above; the call is released either way
            }
            return error(HttpStatus.INTERNAL_SERVER_ERROR, executionId,
                    "the decision could not be processed; the call was rejected", null);
        }
    }

    private ResponseEntity<Map<String, Object>> decideClaimed(String executionId, ReviewDecision decision,
                                                              String approver, boolean authenticated,
                                                              ApprovalWaitRegistry.WaitContext waiting) {
        String recordedApprover = approver;
        log.info("Received {} for execution {} from {}", decision.decision(), executionId, recordedApprover);
        try {
            var payload = new LinkedHashMap<String, Object>();
            payload.put("decision", decision.decision());
            payload.put("approver", recordedApprover);
            payload.put("authenticated", authenticated);
            if (waiting.initiator() != null) {
                payload.put("initiator", waiting.initiator());
            }
            payload.put("initiator_known", waiting.initiator() != null);
            payload.put("content_digest", decision.contentDigest());
            if (decision.comment() != null) {
                payload.put("comment", decision.comment());
            }
            client.sendExternalEvent(executionId, DECISION_EVENT, payload);
        } catch (JamjetClientException e) {
            return refused(executionId, e);
        }

        var forCall = new ApprovalDecision(decision.decision(), null, recordedApprover, decision.comment(), null);
        boolean released = waitRegistry.complete(executionId, forCall);
        if (!released) {
            // The call stopped waiting (timed out past the grace, or was interrupted)
            // while the decision was being recorded. The record must not stand as if
            // it applied: append a correcting event and tell the reviewer.
            voidDecision(executionId, recordedApprover, decision.decision(),
                    "the call stopped waiting before the decision could be delivered; it was rejected");
            return error(HttpStatus.CONFLICT, executionId, "the decision was recorded but did not take effect: "
                    + "the call stopped waiting before it could be delivered and was rejected. A "
                    + VOID_EVENT + " event records that.", null);
        }
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
    public ResponseEntity<Object> listPending(Principal principal) {
        // The list shows prompts, initiators and runtime request context: the same
        // reviewer rules as a decision apply.
        var reviewer = Reviewers.check(principal, policy);
        if (!reviewer.accepted()) {
            return ResponseEntity.status(reviewer.status()).body(Map.of("error", reviewer.reason()));
        }
        var out = new ArrayList<Map<String, Object>>();
        for (var waiting : waitRegistry.allPending()) {
            var item = new LinkedHashMap<String, Object>();
            item.put("execution_id", waiting.executionId());
            item.put("waiting_since", waiting.waitingSince().toString());
            if (waiting.context().initiator() != null) {
                item.put("initiator", waiting.context().initiator());
            }
            item.put("prompt", waiting.context().promptPreview());
            item.put("content_digest", waiting.context().contentDigest());
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

    private void voidDecision(String executionId, String approver, String decision, String reason) {
        try {
            var payload = new LinkedHashMap<String, Object>();
            payload.put("voids", DECISION_EVENT);
            payload.put("decision", decision);
            payload.put("approver", approver);
            payload.put("reason", reason);
            client.sendExternalEvent(executionId, VOID_EVENT, payload);
        } catch (JamjetClientException e) {
            log.error("Execution {}: a recorded {} decision did not take effect and the correcting {} event "
                    + "could not be written: {}", executionId, decision, VOID_EVENT, e.getMessage());
        }
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
