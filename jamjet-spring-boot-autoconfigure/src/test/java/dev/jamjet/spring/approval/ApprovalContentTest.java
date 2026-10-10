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
                .hasSizeLessThanOrEqualTo(ApprovalContent.PREVIEW_LIMIT + 32)
                .endsWith("[truncated]");
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

    @Test
    void differentToolsOfferedGiveDifferentDigests() {
        var read = new Prompt("send it", org.springframework.ai.model.tool.ToolCallingChatOptions.builder()
                .toolNames("readBalance").build());
        var wire = new Prompt("send it", org.springframework.ai.model.tool.ToolCallingChatOptions.builder()
                .toolNames("sendWire").build());

        assertThat(ApprovalContent.digest(read)).isNotEqualTo(ApprovalContent.digest(wire));
    }

    @Test
    void aDifferentModelGivesADifferentDigest() {
        var small = new Prompt("send it", org.springframework.ai.chat.prompt.ChatOptions.builder().model("small").build());
        var large = new Prompt("send it", org.springframework.ai.chat.prompt.ChatOptions.builder().model("large").build());

        assertThat(ApprovalContent.digest(small)).isNotEqualTo(ApprovalContent.digest(large));
    }

    @Test
    void toolCallsAndToolResponsesAreInTheDigest() {
        var lookup = new org.springframework.ai.chat.messages.AssistantMessage("", java.util.Map.of(), List.of(
                new org.springframework.ai.chat.messages.AssistantMessage.ToolCall("t1", "function", "lookup", "{}")));
        var wire = new org.springframework.ai.chat.messages.AssistantMessage("", java.util.Map.of(), List.of(
                new org.springframework.ai.chat.messages.AssistantMessage.ToolCall("t1", "function", "sendWire",
                        "{\"amount\":1000000}")));
        var acct42 = new org.springframework.ai.chat.messages.ToolResponseMessage(List.of(
                new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse("t1", "lookup", "{\"acct\":\"42\"}")));
        var acct666 = new org.springframework.ai.chat.messages.ToolResponseMessage(List.of(
                new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse("t1", "lookup", "{\"acct\":\"666\"}")));

        assertThat(digestOf(new UserMessage("pay"), lookup)).isNotEqualTo(digestOf(new UserMessage("pay"), wire));
        assertThat(digestOf(new UserMessage("pay"), lookup, acct42))
                .isNotEqualTo(digestOf(new UserMessage("pay"), lookup, acct666));
    }

    // ── What the reviewer is shown ──────────────────────────────────────────

    @Test
    void thePreviewShowsRolesMediaToolCallsAndTools() {
        var prompt = new Prompt(List.of(new SystemMessage("be careful"),
                UserMessage.builder().text("pay this").media(png((byte) 7)).build(),
                new org.springframework.ai.chat.messages.AssistantMessage("", java.util.Map.of(), List.of(
                        new org.springframework.ai.chat.messages.AssistantMessage.ToolCall("t1", "function",
                                "sendWire", "{\"amount\":5}")))),
                org.springframework.ai.model.tool.ToolCallingChatOptions.builder().toolNames("sendWire").build());

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
    void truncationNeverSplitsACharacterOrAnEscape() {
        String emoji = new String(Character.toChars(0x1F4B8));
        for (int pad = 0; pad < 8; pad++) {
            String text = "y".repeat(ApprovalContent.PREVIEW_LIMIT - "[user] ".length() - pad)
                    + (emoji + "\n").repeat(10);
            String preview = ApprovalContent.preview(new Prompt(new UserMessage(text)));
            String body = preview.substring(0, preview.length() - " [truncated]".length());

            assertThat(preview).endsWith(" [truncated]");
            assertThat(Character.isHighSurrogate(body.charAt(body.length() - 1))).isFalse();
            assertThat(body).doesNotEndWith("\\");
            assertThat(body.length()).isLessThanOrEqualTo(ApprovalContent.PREVIEW_LIMIT);
        }
    }
}
