package dev.jamjet.spring.approval;

import dev.jamjet.spring.JamjetProperties;
import dev.jamjet.spring.advisor.JamjetApprovalAdvisor;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** The default grace and the derived grace are one value, not two that must agree. */
class DecidingGraceTest {

    @Test
    void theRegistryDefaultIsTheGraceDerivedFromDefaultProperties() {
        assertThat(ApprovalWaitRegistry.DEFAULT_DECIDING_GRACE)
                .isEqualTo(JamjetApprovalAdvisor.decidingGrace(new JamjetProperties()));
    }

    @Test
    void theGraceFollowsTheClientTimeouts() {
        var props = new JamjetProperties();
        props.setConnectTimeoutSeconds(1);
        props.setReadTimeoutSeconds(2);

        assertThat(ApprovalWaitRegistry.decidingGrace(props)).isEqualTo(Duration.ofSeconds(13));
        assertThat(JamjetApprovalAdvisor.decidingGrace(props)).isEqualTo(ApprovalWaitRegistry.decidingGrace(props));
    }
}
