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
}
