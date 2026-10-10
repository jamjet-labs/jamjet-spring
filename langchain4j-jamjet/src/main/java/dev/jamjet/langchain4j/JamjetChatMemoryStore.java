package dev.jamjet.langchain4j;

import dev.jamjet.spring.RuntimeErrorMode;
import dev.jamjet.spring.client.JamjetClientException;
import dev.jamjet.spring.client.JamjetRuntimeClient;
import dev.jamjet.spring.client.RecordedCallWorkflow;
import dev.jamjet.spring.client.model.ExecutionEvent;
import dev.jamjet.spring.client.model.ExecutionState;
import dev.jamjet.spring.client.model.StartExecutionResponse;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A LangChain4j {@link ChatMemoryStore} backed by the JamJet runtime's event log.
 *
 * <p>Each (namespace, memory id) is one execution of the {@code langchain4j-chat-memory}
 * workflow, started on the first write with {@code {"namespace": ns, "memory_id": id}}
 * as input. The namespace (use the application's name) keeps two applications on
 * one runtime tenant from reading each other's memory under the same id, such as
 * LangChain4j's default {@code "default"}. It is not access control: anything
 * holding a token for the tenant can read and append events on any execution.
 * Every update appends a {@code chat_memory} external event holding the full
 * message list; a delete appends {@code chat_memory_cleared}. Reads take the
 * latest of the two from {@code GET /executions/{id}/events}.
 *
 * <p>The runtime has no lookup by input, so the first access to a memory id in a
 * process scans {@code GET /executions} for its execution and caches the result.
 * The scan is linear in the tenant's execution count. A miss is cached too (for
 * {@link #DEFAULT_MISS_TTL}), so a new conversation scans once. Each id has its
 * own lock; different conversations never wait for each other's scan.
 *
 * <p><b>One writer per memory id.</b> Two processes (replicas) that both write a
 * new id before either has seen the other's execution each start one, and the
 * history splits: after a restart the newest wins. Route a conversation to one
 * replica, or expect that, until the runtime offers a lookup by input.
 *
 * <p>By default a runtime error is thrown
 * ({@link dev.jamjet.spring.JamjetGovernanceException}) rather than read as an
 * empty memory or dropped; {@link RuntimeErrorMode#FAIL_OPEN} restores
 * read-empty, drop-write behaviour.
 */
public class JamjetChatMemoryStore implements ChatMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(JamjetChatMemoryStore.class);

    /** Workflow id of chat-memory executions. */
    public static final String WORKFLOW_ID = "langchain4j-chat-memory";

    /** Correlation key of a memory update. */
    public static final String UPDATE_EVENT = "chat_memory";

    /** Correlation key of a memory delete. */
    public static final String CLEARED_EVENT = "chat_memory_cleared";

    /** How long a scan that found nothing is trusted before scanning again. */
    public static final Duration DEFAULT_MISS_TTL = Duration.ofSeconds(30);

    private static final int PAGE = 100;

    private final JamjetRuntimeClient client;
    private final String namespace;
    private final RuntimeErrorMode mode;
    private final Duration missTtl;
    private final ConcurrentHashMap<String, String> executions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> misses = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
    private volatile boolean registered;

    /** Fail-closed store for this namespace (use the application's name). */
    public JamjetChatMemoryStore(JamjetRuntimeClient client, String namespace) {
        this(client, namespace, RuntimeErrorMode.FAIL_CLOSED);
    }

    public JamjetChatMemoryStore(JamjetRuntimeClient client, String namespace, RuntimeErrorMode mode) {
        this(client, namespace, mode, DEFAULT_MISS_TTL);
    }

    public JamjetChatMemoryStore(JamjetRuntimeClient client, String namespace, RuntimeErrorMode mode,
                                 Duration missTtl) {
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("a namespace is required (use the application's name), so two "
                    + "applications on one runtime tenant do not share chat memory");
        }
        this.client = client;
        this.namespace = namespace;
        this.mode = mode == null ? RuntimeErrorMode.FAIL_CLOSED : mode;
        this.missTtl = missTtl == null ? DEFAULT_MISS_TTL : missTtl;
    }

    public String namespace() {
        return namespace;
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String id = String.valueOf(memoryId);
        try {
            String executionId = find(id);
            if (executionId == null) {
                return List.of();
            }
            ExecutionEvent latest = null;
            for (ExecutionEvent e : client.listEvents(executionId)) {
                if (e.isExternalEvent(UPDATE_EVENT) || e.isExternalEvent(CLEARED_EVENT)) {
                    latest = e;
                }
            }
            if (latest == null || latest.isExternalEvent(CLEARED_EVENT) || latest.payload() == null) {
                return List.of();
            }
            Object json = latest.payload().get("messages_json");
            return json == null ? List.of() : ChatMessageDeserializer.messagesFromJson(json.toString());
        } catch (JamjetClientException e) {
            Failures.handle(mode, log, "read chat memory " + id, e);
            return List.of();
        }
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String id = String.valueOf(memoryId);
        try {
            String executionId = findOrStart(id);
            client.sendExternalEvent(executionId, UPDATE_EVENT,
                    Map.of("messages_json", ChatMessageSerializer.messagesToJson(messages),
                            "count", messages.size()));
        } catch (JamjetClientException e) {
            Failures.handle(mode, log, "persist chat memory " + id, e);
        }
    }

    @Override
    public void deleteMessages(Object memoryId) {
        String id = String.valueOf(memoryId);
        try {
            String executionId = find(id);
            if (executionId != null) {
                client.sendExternalEvent(executionId, CLEARED_EVENT, Map.of("memory_id", id));
            }
        } catch (JamjetClientException e) {
            Failures.handle(mode, log, "clear chat memory " + id, e);
        }
    }

    // ── Memory id -> execution ────────────────────────────────────────────────

    private String find(String memoryId) {
        String cached = executions.get(memoryId);
        if (cached != null) {
            return cached;
        }
        Instant missedAt = misses.get(memoryId);
        if (missedAt != null && missedAt.plus(missTtl).isAfter(Instant.now())) {
            return null;
        }
        synchronized (lockFor(memoryId)) {
            cached = executions.get(memoryId);
            if (cached != null) {
                return cached;
            }
            missedAt = misses.get(memoryId);
            if (missedAt != null && missedAt.plus(missTtl).isAfter(Instant.now())) {
                return null;
            }
            String found = scan(memoryId);
            if (found != null) {
                executions.put(memoryId, found);
                misses.remove(memoryId);
            } else {
                misses.put(memoryId, Instant.now());
            }
            return found;
        }
    }

    private String findOrStart(String memoryId) {
        synchronized (lockFor(memoryId)) {
            String existing = find(memoryId);
            if (existing != null) {
                return existing;
            }
            ensureRegistered();
            var input = new LinkedHashMap<String, Object>();
            input.put("namespace", namespace);
            input.put("memory_id", memoryId);
            StartExecutionResponse started;
            try {
                started = client.startExecution(WORKFLOW_ID, RecordedCallWorkflow.VERSION, input);
            } catch (JamjetClientException e) {
                if (e.getStatusCode() != 404) {
                    throw e;
                }
                // The runtime forgot the workflow (store reset): register again, retry once.
                synchronized (this) {
                    registered = false;
                }
                ensureRegistered();
                started = client.startExecution(WORKFLOW_ID, RecordedCallWorkflow.VERSION, input);
            }
            if (started == null || started.executionId() == null || started.executionId().isBlank()) {
                throw new JamjetClientException("the runtime returned no execution_id", "/executions");
            }
            executions.put(memoryId, started.executionId());
            misses.remove(memoryId);
            return started.executionId();
        }
    }

    private Object lockFor(String memoryId) {
        return locks.computeIfAbsent(memoryId, k -> new Object());
    }

    /** The most recently started chat-memory execution for this id, or null. */
    private String scan(String memoryId) {
        String best = null;
        Instant bestStarted = null;
        for (int offset = 0; ; offset += PAGE) {
            List<ExecutionState> page = client.listExecutions(null, PAGE, offset);
            for (ExecutionState e : page) {
                if (WORKFLOW_ID.equals(e.workflowId()) && e.initialInput() != null
                        && namespace.equals(e.initialInput().get("namespace"))
                        && memoryId.equals(e.initialInput().get("memory_id"))) {
                    if (best == null || (e.startedAt() != null
                            && (bestStarted == null || e.startedAt().isAfter(bestStarted)))) {
                        best = e.executionId();
                        bestStarted = e.startedAt();
                    }
                }
            }
            if (page.size() < PAGE) {
                return best;
            }
        }
    }

    static Map<String, Object> workflowIr() {
        return RecordedCallWorkflow.ir(WORKFLOW_ID, "memory",
                "langchain4j.chat_memory.closed", "LangChain4j chat memory", "langchain4j-jamjet");
    }

    private synchronized void ensureRegistered() {
        if (!registered) {
            client.createWorkflow(workflowIr());
            registered = true;
        }
    }
}
