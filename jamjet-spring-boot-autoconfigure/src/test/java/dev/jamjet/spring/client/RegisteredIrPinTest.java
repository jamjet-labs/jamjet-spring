package dev.jamjet.spring.client;

import dev.jamjet.spring.advisor.JamjetDurabilityAdvisor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Pins the content of the IR registered under each version. */
class RegisteredIrPinTest {

    @Test
    void springAiChatIrIsPinnedToItsVersion() throws Exception {
        assertThat(RecordedCallWorkflow.VERSION).isEqualTo("1.0.0");
        assertThat(IrHash.of(JamjetDurabilityAdvisor.workflowIr("spring-ai-chat")))
                .as(IrHash.bumpMessage("spring-ai-chat"))
                .isEqualTo("82b44fd38a0ff6d2f73fb00190b851bbd8f6521bd2e361567637ad2d27257f73");
    }
}
