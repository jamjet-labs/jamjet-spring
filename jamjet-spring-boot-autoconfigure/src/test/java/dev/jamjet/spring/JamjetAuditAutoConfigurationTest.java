package dev.jamjet.spring;

import dev.jamjet.spring.advisor.JamjetAuditAdvisor;
import dev.jamjet.spring.audit.JamjetAuditService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class JamjetAuditAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JamjetAutoConfiguration.class));

    @Test
    void auditEnabledByDefault() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(JamjetAuditAdvisor.class);
            assertThat(context).hasSingleBean(JamjetAuditService.class);
        });
    }

    @Test
    void auditDisabledWhenConfigured() {
        runner.withPropertyValues("spring.jamjet.audit.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(JamjetAuditAdvisor.class);
                    assertThat(context).doesNotHaveBean(JamjetAuditService.class);
                });
    }

    @Test
    void auditPropertiesApplied() {
        runner.withPropertyValues(
                "spring.jamjet.audit.include-prompts=false",
                "spring.jamjet.audit.include-responses=false"
        ).run(context -> {
            var props = context.getBean(JamjetProperties.class);
            assertThat(props.getAudit().isIncludePrompts()).isFalse();
            assertThat(props.getAudit().isIncludeResponses()).isFalse();
        });
    }
}
