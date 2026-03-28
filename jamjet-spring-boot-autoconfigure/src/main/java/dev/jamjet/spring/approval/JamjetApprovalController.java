package dev.jamjet.spring.approval;

import dev.jamjet.spring.client.JamjetRuntimeClient;
import dev.jamjet.spring.client.model.ApprovalDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for receiving human approval decisions. Provides
 * webhook targets that can be called by external UIs, Slack bots,
 * or other approval systems.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code POST /jamjet/approvals/{executionId}} — submit an approval/rejection</li>
 *   <li>{@code GET /jamjet/approvals/pending} — list all pending approvals</li>
 * </ul>
 */
@RestController
@RequestMapping("/jamjet/approvals")
public class JamjetApprovalController {

    private static final Logger log = LoggerFactory.getLogger(JamjetApprovalController.class);

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

        log.info("Received approval for execution {}: {} by {}",
                executionId, decision.decision(), decision.userId());

        // Forward to JamJet runtime
        var response = client.approveExecution(executionId, decision);

        // Unblock the waiting advisor thread
        waitRegistry.complete(executionId, decision);

        return ResponseEntity.ok(Map.of(
                "execution_id", executionId,
                "accepted", response.accepted()
        ));
    }

    @GetMapping("/pending")
    public ResponseEntity<List<ApprovalWaitRegistry.PendingApproval>> listPending() {
        return ResponseEntity.ok(waitRegistry.allPending());
    }
}
