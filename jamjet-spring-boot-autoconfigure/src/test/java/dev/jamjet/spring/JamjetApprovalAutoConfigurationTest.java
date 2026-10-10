package dev.jamjet.spring;

import dev.jamjet.spring.advisor.JamjetApprovalAdvisor;
import dev.jamjet.spring.approval.ApprovalWaitRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class JamjetApprovalAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JamjetAutoConfiguration.class));

    @Test
    void approvalDisabledByDefault() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(JamjetApprovalAdvisor.class);
            assertThat(context).doesNotHaveBean(ApprovalWaitRegistry.class);
        });
    }

    @Test
    void approvalEnabledWhenConfigured() {
        runner.withPropertyValues("spring.jamjet.approval.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(JamjetApprovalAdvisor.class);
                    assertThat(context).hasSingleBean(ApprovalWaitRegistry.class);
                });
    }

    @Test
    void approvalPropertiesApplied() {
        runner.withPropertyValues(
                "spring.jamjet.approval.enabled=true",
                "spring.jamjet.approval.timeout=1h",
                "spring.jamjet.approval.default-decision=rejected",
                "spring.jamjet.approval.webhook-url=https://hooks.slack.com/test"
        ).run(context -> {
            var props = context.getBean(JamjetProperties.class);
            assertThat(props.getApproval().getTimeout()).isEqualTo("1h");
            assertThat(props.getApproval().getDefaultDecision()).isEqualTo("rejected");
            assertThat(props.getApproval().getWebhookUrl()).isEqualTo("https://hooks.slack.com/test");
        });
    }

    @Test
    void anApprovedTimeoutDefaultStopsTheApplication() {
        runner.withPropertyValues("spring.jamjet.approval.enabled=true",
                "spring.jamjet.approval.default-decision=approved")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasStackTraceContaining("never approves"));
    }
}
