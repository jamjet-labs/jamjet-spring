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
}
