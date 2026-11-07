package dev.jamjet.spring.advisor;

import dev.jamjet.spring.approval.ApprovalContent;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * The approval of one ChatClient call, shared by the rounds of Spring AI's tool
 * loop so that a call that uses tools needs one decision, as in Spring AI 1.0.
 *
 * <p>The durability advisor puts a new one in the context of every call it
 * records, replacing anything the caller put there, and closes it when the call
 * ends. Advisors copy the context shallowly, so every round of the loop finds the
 * same instance. A later round goes through without a new decision only when it
 * is the approved request followed by the model's own reply to it (the tool calls
 * it asked for) and one message with exactly one result per tool call, with the
 * same options, and at most {@code maxContinuedRounds} rounds in a row go through
 * this way after a decision. Any other change needs a new decision.
 */
final class ApprovedCall {

    /** Context key. Only {@link JamjetDurabilityAdvisor} sets it. */
    static final String KEY = "jamjet.approval.call";

    private final String executionId;
    private boolean closed;
    private List<String> approvedMessages;
    private String approvedOptions;
    private String reply;
    /** Tool call id to tool name, for the reply's tool calls. */
    private Map<String, String> replyToolCalls = Map.of();
    /** Rounds continued since the last real decision. */
    private int continuedRounds;

    private final int maxContinuedRounds;

    ApprovedCall(String executionId) {
        this(executionId, Integer.MAX_VALUE);
    }

    ApprovedCall(String executionId, int maxContinuedRounds) {
        if (maxContinuedRounds < 0) {
            throw new IllegalArgumentException("max continued rounds must be 0 or more: " + maxContinuedRounds);
        }
        this.executionId = executionId;
        this.maxContinuedRounds = maxContinuedRounds;
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

    /** A reviewer approved {@code effective}, and it reached the model. */
    synchronized void approved(Prompt effective) {
        remember(effective);
        continuedRounds = 0;
    }

    /** {@code effective} reached the model on an earlier decision ({@link #continues} was true). */
    synchronized int continued(Prompt effective) {
        remember(effective);
        return ++continuedRounds;
    }

    /** How many messages the request that last reached the model had, or 0. */
    synchronized int approvedMessageCount() {
        return approvedMessages == null ? 0 : approvedMessages.size();
    }

    private void remember(Prompt effective) {
        approvedMessages = ApprovalContent.messageDigests(effective);
        approvedOptions = ApprovalContent.optionsDigest(effective);
        reply = null;
        replyToolCalls = Map.of();
    }

    /** The model answered the approved request. Only a reply asking for tools can be continued. */
    synchronized void replied(ChatResponse response) {
        reply = null;
        replyToolCalls = Map.of();
        if (response == null || approvedMessages == null) {
            return;
        }
        for (Generation generation : response.getResults()) {
            AssistantMessage output = generation.getOutput();
            if (output != null && output.hasToolCalls()) {
                var calls = new HashMap<String, String>();
                for (var call : output.getToolCalls()) {
                    if (call.id() == null || calls.put(call.id(), call.name()) != null) {
                        return; // no id, or the same id twice: results cannot be matched to calls
                    }
                }
                reply = ApprovalContent.messageDigest(output);
                replyToolCalls = Map.copyOf(calls);
                return;
            }
        }
    }

    /**
     * True when {@code effective} is the approved request, then the model's reply
     * to it, then one tool-result message holding exactly one result per tool call
     * in that reply (same id, same tool name), and fewer than the maximum rounds
     * have continued since the last decision.
     */
    synchronized boolean continues(Prompt effective) {
        if (closed || approvedMessages == null || reply == null || continuedRounds >= maxContinuedRounds) {
            return false;
        }
        if (!ApprovalContent.optionsDigest(effective).equals(approvedOptions)) {
            return false;
        }
        List<Message> messages = effective.getInstructions();
        int n = approvedMessages.size();
        if (messages.size() != n + 2) {
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
        if (!(messages.get(n + 1) instanceof ToolResponseMessage results)
                || results.getResponses().size() != replyToolCalls.size()) {
            return false;
        }
        var answered = new HashSet<String>();
        for (var result : results.getResponses()) {
            String name = replyToolCalls.get(result.id());
            if (name == null || !name.equals(result.name()) || !answered.add(result.id())) {
                return false;
            }
        }
        return true;
    }

    /** The call ended; a copy of the context that outlives it continues nothing. */
    synchronized void close() {
        closed = true;
    }
}
