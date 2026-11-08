package dev.jamjet.spring.client;

import dev.jamjet.spring.advisor.JamjetDurabilityAdvisor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Pins the content of the IR registered under each version. */
class RegisteredIrPinTest {

    @Test
    void springAiChatIrIsPinnedToItsVersion() throws Exception {
        assertThat(RecordedCallWorkflow.VERSION).isEqualTo("2.0.0");
        assertThat(IrHash.of(JamjetDurabilityAdvisor.workflowIr("spring-ai-chat")))
                .as(IrHash.bumpMessage("spring-ai-chat"))
                .isEqualTo("74d0f1337d8bc1d4ef3da194e8914a9143526b79c00573e3acd246c9d0afd290");
    }

    /**
     * The 1.0.0 IR is what 0.2.x registered. A 0.5.x runtime that holds it
     * re-registers it only if it is byte for byte the same, so this never changes.
     */
    @Test
    void legacySpringAiChatIrIsUnchanged() throws Exception {
        assertThat(RecordedCallWorkflow.LEGACY_VERSION).isEqualTo("1.0.0");
        assertThat(IrHash.of(JamjetDurabilityAdvisor.legacyWorkflowIr("spring-ai-chat")))
                .isEqualTo("82b44fd38a0ff6d2f73fb00190b851bbd8f6521bd2e361567637ad2d27257f73");
    }
}
