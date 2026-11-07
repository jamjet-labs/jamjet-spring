package dev.jamjet.spring.approval;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalContentTest {

    @Test
    void theDigestCoversEveryMessageAndItsRole() {
        var base = new Prompt(List.of(new SystemMessage("be careful"), new UserMessage("send 100")));
        var sameText = new Prompt(List.of(new SystemMessage("be careful"), new UserMessage("send 100")));
        var otherText = new Prompt(List.of(new SystemMessage("be careful"), new UserMessage("send 1000")));
        var otherRole = new Prompt(List.of(new UserMessage("be careful"), new UserMessage("send 100")));

        assertThat(ApprovalContent.digest(base)).hasSize(64).isEqualTo(ApprovalContent.digest(sameText));
        assertThat(ApprovalContent.digest(base)).isNotEqualTo(ApprovalContent.digest(otherText))
                .isNotEqualTo(ApprovalContent.digest(otherRole));
    }

    @Test
    void thePreviewEscapesControlCharactersAndIsBounded() {
        String hostile = "pay\u001b[2J\r\nAPPROVED‮evil";
        String preview = ApprovalContent.preview(new Prompt(new UserMessage(hostile)));

        assertThat(preview).doesNotContain("\u001b", "\r", "\n", "‮")
                .contains("\\u001b", "\\r", "\\n", "\\u202e");

        String longPrompt = "x".repeat(10_000);
        assertThat(ApprovalContent.preview(new Prompt(new UserMessage(longPrompt))))
                .hasSizeLessThanOrEqualTo(ApprovalContent.PREVIEW_LIMIT)
                .contains("characters not shown");
    }

    private static org.springframework.ai.chat.messages.AssistantMessage toolCall(String id, String name, String args) {
        return org.springframework.ai.chat.messages.AssistantMessage.builder().content("")
                .toolCalls(java.util.List.of(new org.springframework.ai.chat.messages.AssistantMessage.ToolCall(
                        id, "function", name, args))).build();
    }

    private static org.springframework.ai.chat.messages.ToolResponseMessage toolResult(String id, String name, String data) {
        return org.springframework.ai.chat.messages.ToolResponseMessage.builder()
                .responses(java.util.List.of(new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse(
                        id, name, data))).build();
    }

    /** Review probe: a 2.6k system prompt pushed the request and the new tool call out of the preview. */
    @Test
    void aLongSystemPromptDoesNotHideTheRequestOrTheNewestToolCall() {
        var prompt = new Prompt(java.util.List.of(
                new org.springframework.ai.chat.messages.SystemMessage("S".repeat(2600)),
                new UserMessage("wire 100 to acct 42"),
                toolCall("t1", "transfer_funds", "{\"amount\":100}"),
                toolResult("t1", "transfer_funds", "ok")));

        String preview = ApprovalContent.preview(prompt);

        assertThat(preview).hasSizeLessThanOrEqualTo(ApprovalContent.PREVIEW_LIMIT)
                .contains("[user] wire 100 to acct 42", "transfer_funds {\"amount\":100}", "[tool response] transfer_funds ok");
    }

    @Test
    void toolCallsThatAlreadyRanAreLabelled() {
        var prompt = new Prompt(java.util.List.of(new UserMessage("wire 100"),
                toolCall("t1", "transfer_funds", "{}"), toolResult("t1", "transfer_funds", "ok")));

        assertThat(ApprovalContent.preview(prompt)).contains("[assistant tool call, already executed] transfer_funds");
    }

    @Test
    void manyRoundsKeepTheStartAndTheNewestContent() {
        var messages = new java.util.ArrayList<org.springframework.ai.chat.messages.Message>();
        messages.add(new UserMessage("wire 100 to acct 42"));
        for (int i = 0; i < 40; i++) {
            messages.add(toolCall("t" + i, "lookup_" + i, "{\"page\":" + i + "}"));
            messages.add(toolResult("t" + i, "lookup_" + i, "x".repeat(40)));
        }
        messages.add(toolCall("tz", "transfer_funds", "{\"amount\":1000000}"));
        messages.add(toolResult("tz", "transfer_funds", "ok"));

        String preview = ApprovalContent.preview(new Prompt(messages));

        assertThat(preview).hasSizeLessThanOrEqualTo(ApprovalContent.PREVIEW_LIMIT)
                .contains("[user] wire 100 to acct 42", "transfer_funds {\"amount\":1000000}", "lines not shown");
    }

    @Test
    void aSecondDecisionMarksWhatIsNewSinceTheLastApproval() {
        var prompt = new Prompt(java.util.List.of(new UserMessage("wire 100"),
                toolCall("t1", "transfer_funds", "{}"), toolResult("t1", "transfer_funds", "ok")));

        String preview = ApprovalContent.preview(prompt, 1);

        assertThat(preview).contains("[user] wire 100\n[--- new since the last approval ---]\n[assistant] \n"
                + "[assistant tool call, already executed] transfer_funds");
    }

    // ── Everything the model receives is in the digest (ported from probe P4) ──

    private static String digestOf(org.springframework.ai.chat.messages.Message... messages) {
        return ApprovalContent.digest(new Prompt(List.of(messages)));
    }

    private static org.springframework.ai.content.Media png(byte b) {
        return org.springframework.ai.content.Media.builder().mimeType(org.springframework.util.MimeTypeUtils.IMAGE_PNG)
                .data(new org.springframework.core.io.ByteArrayResource(new byte[]{b})).build();
    }

    @Test
    void differentMediaGiveDifferentDigests() {
        var a = UserMessage.builder().text("pay the invoice in the image").media(png((byte) 1)).build();
        var b = UserMessage.builder().text("pay the invoice in the image").media(png((byte) 2)).build();

        assertThat(digestOf(a)).isNotEqualTo(digestOf(b));
    }

    /** A tool as Spring AI 2.0 offers it: a callback with a definition and metadata. */
    static org.springframework.ai.tool.ToolCallback tool(String name, String description, String inputSchema,
                                                         boolean returnDirect) {
        return new org.springframework.ai.tool.ToolCallback() {
            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return org.springframework.ai.tool.definition.ToolDefinition.builder()
                        .name(name).description(description).inputSchema(inputSchema).build();
            }

            @Override
            public org.springframework.ai.tool.metadata.ToolMetadata getToolMetadata() {
                return org.springframework.ai.tool.metadata.ToolMetadata.builder().returnDirect(returnDirect).build();
            }

            @Override
            public String call(String toolInput) {
                return toolInput;
            }
        };
    }

    private static String digestOfTools(org.springframework.ai.tool.ToolCallback... tools) {
        return ApprovalContent.digest(new Prompt("send it",
                org.springframework.ai.model.tool.ToolCallingChatOptions.builder().toolCallbacks(tools).build()));
    }

    @Test
    void differentToolsOfferedGiveDifferentDigests() {
        assertThat(digestOfTools(tool("readBalance", "read", "{}", false)))
                .isNotEqualTo(digestOfTools(tool("sendWire", "read", "{}", false)));
        assertThat(digestOfTools(tool("readBalance", "read", "{}", false)))
                .isNotEqualTo(digestOfTools(tool("readBalance", "read", "{}", false),
                        tool("sendWire", "send money", "{}", false)));
    }

    @Test
    void aToolsDescriptionAndInputSchemaAreInTheDigest() {
        String base = digestOfTools(tool("sendWire", "send money", "{\"type\":\"object\"}", false));

        assertThat(base).isEqualTo(digestOfTools(tool("sendWire", "send money", "{\"type\":\"object\"}", false)));
        assertThat(base).as("description")
                .isNotEqualTo(digestOfTools(tool("sendWire", "send money anywhere", "{\"type\":\"object\"}", false)));
        assertThat(base).as("input schema")
                .isNotEqualTo(digestOfTools(tool("sendWire", "send money", "{\"type\":\"string\"}", false)));
    }

    @Test
    void whetherAToolReturnsDirectlyIsInTheDigest() {
        assertThat(digestOfTools(tool("sendWire", "send money", "{}", false)))
                .isNotEqualTo(digestOfTools(tool("sendWire", "send money", "{}", true)));
    }

    /**
     * The shape of what is digested. A field dropped from the canonical form (for
     * example after a Spring AI upgrade removes the accessor it was read from) fails
     * here even when no other test varies it.
     */
    @Test
    void theCanonicalFormNamesEveryToolField() {
        var options = org.springframework.ai.model.tool.ToolCallingChatOptions.builder()
                .toolCallbacks(tool("sendWire", "send money", "{}", true))
                .toolContext(java.util.Map.of("account", "42"))
                .build();
        @SuppressWarnings("unchecked")
        var canonicalOptions = (java.util.Map<String, Object>) ApprovalContent.canonical(new Prompt("pay", options))
                .get("options");

        assertThat(canonicalOptions).containsOnlyKeys("type", "properties", "tool_definitions", "tool_context");
        @SuppressWarnings("unchecked")
        var definitions = (List<java.util.Map<String, Object>>) canonicalOptions.get("tool_definitions");
        assertThat(definitions).singleElement().satisfies(d -> assertThat(d)
                .containsOnlyKeys("name", "description", "input_schema", "return_direct")
                .containsEntry("return_direct", true));
        assertThat(canonicalOptions.get("tool_context"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP).containsOnlyKeys("account");
    }

    @Test
    void aDifferentModelGivesADifferentDigest() {
        var small = new Prompt("send it", org.springframework.ai.chat.prompt.ChatOptions.builder().model("small").build());
        var large = new Prompt("send it", org.springframework.ai.chat.prompt.ChatOptions.builder().model("large").build());

        assertThat(ApprovalContent.digest(small)).isNotEqualTo(ApprovalContent.digest(large));
    }

    @Test
    void toolCallsAndToolResponsesAreInTheDigest() {
        var lookup = org.springframework.ai.chat.messages.AssistantMessage.builder().content("").toolCalls(List.of(
                new org.springframework.ai.chat.messages.AssistantMessage.ToolCall("t1", "function", "lookup", "{}")))
                .build();
        var wire = org.springframework.ai.chat.messages.AssistantMessage.builder().content("").toolCalls(List.of(
                new org.springframework.ai.chat.messages.AssistantMessage.ToolCall("t1", "function", "sendWire",
                        "{\"amount\":1000000}"))).build();
        var acct42 = org.springframework.ai.chat.messages.ToolResponseMessage.builder().responses(List.of(
                new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse("t1", "lookup", "{\"acct\":\"42\"}")))
                .build();
        var acct666 = org.springframework.ai.chat.messages.ToolResponseMessage.builder().responses(List.of(
                new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse("t1", "lookup", "{\"acct\":\"666\"}")))
                .build();

        assertThat(digestOf(new UserMessage("pay"), lookup)).isNotEqualTo(digestOf(new UserMessage("pay"), wire));
        assertThat(digestOf(new UserMessage("pay"), lookup, acct42))
                .isNotEqualTo(digestOf(new UserMessage("pay"), lookup, acct666));
    }

    // ── What the reviewer is shown ──────────────────────────────────────────

    @Test
    void thePreviewShowsRolesMediaToolCallsAndTools() {
        var prompt = new Prompt(List.of(new SystemMessage("be careful"),
                UserMessage.builder().text("pay this").media(png((byte) 7)).build(),
                org.springframework.ai.chat.messages.AssistantMessage.builder().content("").toolCalls(List.of(
                        new org.springframework.ai.chat.messages.AssistantMessage.ToolCall("t1", "function",
                                "sendWire", "{\"amount\":5}"))).build()),
                org.springframework.ai.model.tool.ToolCallingChatOptions.builder()
                        .toolCallbacks(tool("sendWire", "send money", "{}", false)).build());

        String preview = ApprovalContent.preview(prompt);

        assertThat(preview).contains("[system] be careful", "[user] pay this", "image/png",
                "sendWire", "{\"amount\":5}", "[tools] sendWire");
    }

    @Test
    void thePreviewEscapesInvisibleCharacters() {
        String hidden = "pay\u200Bme\u2060now\uFEFF" + new String(Character.toChars(0xE0041));
        String preview = ApprovalContent.preview(new Prompt(new UserMessage(hidden)));

        assertThat(preview).doesNotContain("\u200B", "\u2060", "\uFEFF", new String(Character.toChars(0xE0041)))
                .contains("\\u200b", "\\u2060", "\\ufeff", "\\U000e0041");
    }

    @Test
    void aPreviewThatFitsExactlyIsNotMarkedTruncated() {
        String exact = "x".repeat(ApprovalContent.PREVIEW_LIMIT - "[user] ".length());
        assertThat(ApprovalContent.preview(new Prompt(new UserMessage(exact)))).doesNotContain("[truncated]");
    }

    @Test
    void cuttingALongLineNeverSplitsACharacterOrAnEscape() {
        String emoji = new String(Character.toChars(0x1F4B8));
        for (int pad = 0; pad < 12; pad++) {
            String text = "y".repeat(pad) + (emoji + "\n").repeat(1500);
            String preview = ApprovalContent.preview(new Prompt(new UserMessage(text)));
            int gap = preview.indexOf(" [... ");
            assertThat(gap).as("a long line is cut with a marker").isPositive();
            String head = preview.substring(0, gap);
            String tail = preview.substring(preview.indexOf("not shown ...] ") + "not shown ...] ".length());

            assertThat(Character.isHighSurrogate(head.charAt(head.length() - 1))).isFalse();
            assertThat(head).doesNotEndWith("\\");
            assertThat(Character.isLowSurrogate(tail.charAt(0))).isFalse();
            assertThat(tail).doesNotStartWith("n").doesNotStartWith("\\n".substring(1));
            assertThat(preview.length()).isLessThanOrEqualTo(ApprovalContent.PREVIEW_LIMIT);
        }
    }
}
