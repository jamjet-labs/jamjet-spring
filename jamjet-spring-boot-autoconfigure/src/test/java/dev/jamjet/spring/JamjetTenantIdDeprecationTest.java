package dev.jamjet.spring;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class JamjetTenantIdDeprecationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JamjetAutoConfiguration.class));

    @Test
    void settingTenantIdWarnsThatTheTokenDecidesTheTenant(CapturedOutput output) {
        runner.withPropertyValues("spring.jamjet.tenant-id=acme").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(output).contains("spring.jamjet.tenant-id is ignored")
                    .contains("the runtime takes the tenant from the API token");
        });
    }

    @Test
    void leavingItUnsetSaysNothing(CapturedOutput output) {
        runner.run(context -> assertThat(output).doesNotContain("spring.jamjet.tenant-id is ignored"));
    }
}
