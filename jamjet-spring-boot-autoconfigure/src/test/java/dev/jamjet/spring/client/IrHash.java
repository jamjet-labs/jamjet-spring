package dev.jamjet.spring.client;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

/** SHA-256 of an IR's canonical JSON (keys sorted), the identity the runtime compares on re-registration. */
public final class IrHash {

    private static final ObjectMapper CANONICAL = JsonMapper.builder()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .build();

    private IrHash() {}

    public static String of(Map<String, Object> ir) throws Exception {
        byte[] json = CANONICAL.writeValueAsBytes(ir);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
    }

    public static String bumpMessage(String workflow) {
        return "The registered IR of " + workflow + " changed. The runtime refuses a different IR under a version "
                + "it already holds (and fail-closed then refuses every call), so bump RecordedCallWorkflow.VERSION "
                + "and update this pinned hash together.";
    }
}
