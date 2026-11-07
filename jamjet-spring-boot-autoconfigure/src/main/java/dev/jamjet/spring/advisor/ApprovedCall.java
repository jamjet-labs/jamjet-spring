package dev.jamjet.spring.advisor;

import dev.jamjet.spring.approval.ApprovalContent;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The approval of one ChatClient call, shared by the rounds of Spring AI's tool
 * loop so that a call that uses tools needs one decision, as in Spring AI 1.0.
 *
 * <p>The durability advisor puts a new one in the context of every call it
 * records, replacing anything the caller put there, and closes it when the call
 * ends. Advisors copy the context shallowly, so every round of the loop finds the
 * same instance. A later round goes through without a new decision only when it
 * is the approved request followed by the model's own reply to it (the tool calls
 * it asked for) and the results of those tool calls, with the same options. Any
 * other change needs a new decision.
 */
final class ApprovedCall {

    /** Context key. Only {@link JamjetDurabilityAdvisor} sets it. */
    static final String KEY = "jamjet.approval.call";

    private final String executionId;
    private boolean closed;
    private List<String> approvedMessages;
    private String approvedOptions;
    private String reply;
    private Set<String> replyToolCallIds = Set.of();

    ApprovedCall(String executionId) {
        this.executionId = executionId;
    }

    /** The open approval of the call recorded as {@code executionId}, or null. */
    static ApprovedCall of(Map<String, Object> context, String executionId) {
        Object value = context == null ? null : context.get(KEY);
        if (value instanceof ApprovedCall call && call.executionId.equals(executionId)) {
            synchronized (call) {
                return call.closed ? null : call;
            }
        }
        return null;
    }

    /** {@code effective} reached the model: approved, or continuing an approved request. */
    synchronized void approved(Prompt effective) {
        approvedMessages = ApprovalContent.messageDigests(effective);
        approvedOptions = ApprovalContent.optionsDigest(effective);
        reply = null;
        replyToolCallIds = Set.of();
    }

    /** The model answered the approved request. Only a reply asking for tools can be continued. */
    synchronized void replied(ChatResponse response) {
        reply = null;
        replyToolCallIds = Set.of();
        if (response == null || approvedMessages == null) {
            return;
        }
        for (Generation generation : response.getResults()) {
            AssistantMessage output = generation.getOutput();
            if (output != null && output.hasToolCalls()) {
                reply = ApprovalContent.messageDigest(output);
                var ids = new HashSet<String>();
                output.getToolCalls().forEach(call -> ids.add(call.id()));
                replyToolCallIds = ids;
                return;
            }
        }
    }

    /**
     * True when {@code effective} is the approved request, then the model's reply
     * to it, then only tool results for the tool calls in that reply.
     */
    synchronized boolean continues(Prompt effective) {
        if (closed || approvedMessages == null || reply == null) {
            return false;
        }
        if (!ApprovalContent.optionsDigest(effective).equals(approvedOptions)) {
            return false;
        }
        List<Message> messages = effective.getInstructions();
        int n = approvedMessages.size();
        if (messages.size() < n + 2) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            if (!ApprovalContent.messageDigest(messages.get(i)).equals(approvedMessages.get(i))) {
                return false;
            }
        }
        if (!ApprovalContent.messageDigest(messages.get(n)).equals(reply)) {
            return false;
        }
        for (Message m : messages.subList(n + 1, messages.size())) {
            if (!(m instanceof ToolResponseMessage results) || results.getResponses().isEmpty()) {
                return false;
            }
            for (var result : results.getResponses()) {
                if (!replyToolCallIds.contains(result.id())) {
                    return false;
                }
            }
        }
        return true;
    }

    /** The call ended; a copy of the context that outlives it continues nothing. */
    synchronized void close() {
        closed = true;
    }
}
