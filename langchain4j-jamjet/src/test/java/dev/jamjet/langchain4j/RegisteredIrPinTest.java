package dev.jamjet.langchain4j;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.jamjet.spring.client.RecordedCallWorkflow;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Pins the content of the IRs this module registers under each version. */
class RegisteredIrPinTest {

    private static String hash(Map<String, Object> ir) throws Exception {
        var canonical = JsonMapper.builder()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .build();
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.writeValueAsBytes(ir)));
    }

    private static String bump(String workflow) {
        return "The registered IR of " + workflow + " changed. The runtime refuses a different IR under a version "
                + "it already holds, so bump RecordedCallWorkflow.VERSION and update this pinned hash together.";
    }

    @Test
    void durableAgentIrIsPinnedToItsVersion() throws Exception {
        assertThat(RecordedCallWorkflow.VERSION).isEqualTo("2.0.0");
        assertThat(hash(JamjetDurableAgent.workflowIr())).as(bump(JamjetDurableAgent.WORKFLOW_ID))
                .isEqualTo("627e85f30e47122771cf31f2c5e3fb172fe2ccbc5e9249aacafbf67bad0f271d");
    }

    @Test
    void chatMemoryIrIsPinnedToItsVersion() throws Exception {
        assertThat(RecordedCallWorkflow.VERSION).isEqualTo("2.0.0");
        assertThat(hash(JamjetChatMemoryStore.workflowIr())).as(bump(JamjetChatMemoryStore.WORKFLOW_ID))
                .isEqualTo("14ed8dd7c6ecbf5cfda00511b7d5cced5624a7c1383e748e356012579f326bdc");
    }

    /**
     * The 1.0.0 IRs are what 0.2.x registered. A 0.5.x runtime that holds them
     * re-registers them only if they are byte for byte the same, so these hashes
     * never change.
     */
    @Test
    void legacyIrsAreUnchanged() throws Exception {
        assertThat(RecordedCallWorkflow.LEGACY_VERSION).isEqualTo("1.0.0");
        assertThat(hash(JamjetDurableAgent.legacyWorkflowIr()))
                .isEqualTo("fbaa55e8016f01e6072f6ae4a97c4607403bd82911c4ffa54f2dd768a3cee552");
        assertThat(hash(JamjetChatMemoryStore.legacyWorkflowIr()))
                .isEqualTo("d6f75c51f7efe4891ef1ee74023cb9d25430f396b9d77f1321c09aff3b71b833");
    }
}
