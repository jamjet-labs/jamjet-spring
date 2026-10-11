package dev.jamjet.spring.advisor;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class JamjetApprovalAdvisorTest {

    @Test
    void parseTimeoutMinutes() {
        assertThat(JamjetApprovalAdvisor.parseTimeout("30m")).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void parseTimeoutSeconds() {
        assertThat(JamjetApprovalAdvisor.parseTimeout("60s")).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void parseTimeoutHours() {
        assertThat(JamjetApprovalAdvisor.parseTimeout("2h")).isEqualTo(Duration.ofHours(2));
    }

    @Test
    void parseTimeoutPlainNumber() {
        assertThat(JamjetApprovalAdvisor.parseTimeout("15")).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void parseTimeoutNullDefaults() {
        assertThat(JamjetApprovalAdvisor.parseTimeout(null)).isEqualTo(Duration.ofMinutes(30));
        assertThat(JamjetApprovalAdvisor.parseTimeout("")).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void parseTimeoutAcceptsSpringDurationFormats() {
        assertThat(JamjetApprovalAdvisor.parseTimeout("500ms")).isEqualTo(Duration.ofMillis(500));
        assertThat(JamjetApprovalAdvisor.parseTimeout("30s")).isEqualTo(Duration.ofSeconds(30));
        assertThat(JamjetApprovalAdvisor.parseTimeout("PT2M")).isEqualTo(Duration.ofMinutes(2));
        assertThat(JamjetApprovalAdvisor.parseTimeout("1d")).isEqualTo(Duration.ofDays(1));
    }

    @Test
    void aBadTimeoutFailsAtStartupNotOnTheFirstCall() {
        var props = new dev.jamjet.spring.JamjetProperties();
        props.getApproval().setTimeout("soon");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new JamjetApprovalAdvisor(
                        new dev.jamjet.spring.client.JamjetRuntimeClient(props), props,
                        new dev.jamjet.spring.approval.ApprovalWaitRegistry()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("spring.jamjet.approval.timeout");
    }
}
