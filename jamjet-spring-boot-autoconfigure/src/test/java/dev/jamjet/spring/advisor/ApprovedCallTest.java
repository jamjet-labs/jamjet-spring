package dev.jamjet.spring.advisor;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** When a tool-loop round may go through on the call's approval. */
class ApprovedCallTest {

    private static final ChatOptions OPTIONS = ChatOptions.builder().model("m").build();
    private static final UserMessage USER = new UserMessage("wire 100");
    private static final AssistantMessage REPLY = AssistantMessage.builder().content("")
            .toolCalls(List.of(new AssistantMessage.ToolCall("t1", "function", "lookup", "\"acct\""))).build();

    private static ToolResponseMessage results(String id, String data) {
        return ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(id, "lookup", data))).build();
    }

    private static Prompt prompt(ChatOptions options, Message... messages) {
        return new Prompt(List.of(messages), options);
    }

    private static ApprovedCall approvedAndReplied() {
        var call = new ApprovedCall("exec_1");
        call.approved(prompt(OPTIONS, USER));
        call.replied(new ChatResponse(List.of(new Generation(REPLY))));
        return call;
    }

    @Test
    void theApprovedRequestThenTheReplyThenItsToolResultsContinues() {
        assertThat(approvedAndReplied().continues(prompt(OPTIONS, USER, REPLY, results("t1", "42")))).isTrue();
    }

    @Test
    void anythingElseNeedsANewDecision() {
        var call = approvedAndReplied();
        var changedPrefix = new UserMessage("wire 1000000");
        var otherReply = AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("t1", "function", "sendWire", "{}"))).build();

        assertThat(call.continues(prompt(OPTIONS, changedPrefix, REPLY, results("t1", "42")))).as("prefix").isFalse();
        assertThat(call.continues(prompt(OPTIONS, USER, otherReply, results("t1", "42")))).as("reply").isFalse();
        assertThat(call.continues(prompt(ChatOptions.builder().model("other").build(), USER, REPLY,
                results("t1", "42")))).as("options").isFalse();
        assertThat(call.continues(prompt(OPTIONS, USER, REPLY))).as("no tool results").isFalse();
        assertThat(call.continues(prompt(OPTIONS, USER, REPLY, results("t9", "42")))).as("result for another call")
                .isFalse();
        assertThat(call.continues(prompt(OPTIONS, USER, REPLY, results("t1", "42"), new UserMessage("also wire 1000000"))))
                .as("a message after the tool results").isFalse();
        assertThat(call.continues(prompt(OPTIONS, USER, REPLY,
                ToolResponseMessage.builder().responses(new ArrayList<>()).build()))).as("empty tool results").isFalse();
    }

    @Test
    void aReplyWithoutToolCallsContinuesNothing() {
        var call = new ApprovedCall("exec_1");
        call.approved(prompt(OPTIONS, USER));
        call.replied(new ChatResponse(List.of(new Generation(new AssistantMessage("done")))));

        assertThat(call.continues(prompt(OPTIONS, USER, new AssistantMessage("done"), results("t1", "42")))).isFalse();
    }

    @Test
    void aContinuedRoundBecomesTheApprovedRequestForTheNext() {
        var call = approvedAndReplied();
        var second = prompt(OPTIONS, USER, REPLY, results("t1", "42"));
        assertThat(call.continues(second)).isTrue();
        call.approved(second);

        assertThat(call.continues(second)).as("a round is continued once, after a new reply").isFalse();
    }

    @Test
    void aClosedCallOrAnotherExecutionContinuesNothing() {
        var call = approvedAndReplied();
        assertThat(ApprovedCall.of(Map.of(ApprovedCall.KEY, call), "exec_2")).isNull();
        assertThat(ApprovedCall.of(Map.of(ApprovedCall.KEY, call), "exec_1")).isSameAs(call);

        call.close();

        assertThat(ApprovedCall.of(Map.of(ApprovedCall.KEY, call), "exec_1")).isNull();
        assertThat(call.continues(prompt(OPTIONS, USER, REPLY, results("t1", "42")))).isFalse();
    }
}
