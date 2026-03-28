package dev.jamjet.spring.audit;

import dev.jamjet.spring.client.JamjetRuntimeClient;
import dev.jamjet.spring.client.model.AuditEntry;
import dev.jamjet.spring.client.model.AuditPage;

import java.util.ArrayList;
import java.util.List;

/**
 * Service bean for querying the JamJet audit trail. Exposes convenient
 * methods for common audit queries — by execution, actor, or event type.
 *
 * <pre>{@code
 * @Autowired JamjetAuditService auditService;
 *
 * var trail = auditService.fullTrail("exec-abc123");
 * trail.forEach(e -> log.info("{} {} {}", e.timestamp(), e.eventType(), e.nodeId()));
 * }</pre>
 */
public class JamjetAuditService {

    private final JamjetRuntimeClient client;

    public JamjetAuditService(JamjetRuntimeClient client) {
        this.client = client;
    }

    /** Query audit entries for a specific execution. */
    public AuditPage queryByExecution(String executionId, int limit, int offset) {
        return client.queryAudit(executionId, null, null, limit, offset);
    }

    /** Query audit entries by actor ID. */
    public AuditPage queryByActor(String actorId, int limit, int offset) {
        return client.queryAudit(null, actorId, null, limit, offset);
    }

    /** Query audit entries by event type. */
    public AuditPage queryByEventType(String eventType, int limit, int offset) {
        return client.queryAudit(null, null, eventType, limit, offset);
    }

    /**
     * Fetch the complete audit trail for an execution (all events, unpaginated).
     * Iterates through pages until all entries are collected.
     */
    public List<AuditEntry> fullTrail(String executionId) {
        var all = new ArrayList<AuditEntry>();
        int offset = 0;
        int limit = 200;
        while (true) {
            var page = client.queryAudit(executionId, null, null, limit, offset);
            if (page.items() == null || page.items().isEmpty()) {
                break;
            }
            all.addAll(page.items());
            if (all.size() >= page.total()) {
                break;
            }
            offset += limit;
        }
        return List.copyOf(all);
    }
}
