package dev.jamjet.spring;

import dev.jamjet.spring.approval.JamjetApprovalController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class JamjetApprovalControllerRegistrationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JamjetAutoConfiguration.class))
            .withPropertyValues("spring.jamjet.approval.enabled=true");

    @Test
    void withSpringSecurityTheEndpointsAreRegistered() {
        runner.run(context -> assertThat(context).hasSingleBean(JamjetApprovalController.class));
    }

    @Test
    void withoutSpringSecurityTheyAreNotRegistered() {
        runner.withClassLoader(new FilteredClassLoader("org.springframework.security"))
                .run(context -> assertThat(context).doesNotHaveBean(JamjetApprovalController.class));
    }

    @Test
    void withoutSpringSecurityAnExplicitOptInRegistersThemAndWarns(CapturedOutput output) {
        runner.withClassLoader(new FilteredClassLoader("org.springframework.security"))
                .withPropertyValues("spring.jamjet.approval.controller.allow-unauthenticated=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(JamjetApprovalController.class);
                    assertThat(output).contains("accept UNAUTHENTICATED decisions");
                });
    }
}
