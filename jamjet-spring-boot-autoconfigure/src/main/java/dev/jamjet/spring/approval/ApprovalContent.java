package dev.jamjet.spring.approval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.content.MediaContent;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * What a reviewer approves: a digest binding the decision to everything the
 * model receives in the request, and a bounded, escaped preview to show.
 *
 * <p>Covered: every message's role and text, its media (MIME type and a SHA-256
 * of the bytes, or the URL), an assistant message's tool calls, a tool message's
 * responses, and the options (model, sampling settings, tool names and tool
 * definitions). Not covered: the tool calls Spring AI makes inside the model call
 * after approval; those are not individually reviewed here.
 */
public final class ApprovalContent {

    /** Longest preview shown in {@code /pending}, in characters, before the truncation marker. */
    public static final int PREVIEW_LIMIT = 2000;

    private static final String TRUNCATED = " [truncated]";

    private static final ObjectMapper CANONICAL = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private ApprovalContent() {}

    /** SHA-256 (hex) of the canonical JSON of {@link #canonical(Prompt)}. */
    public static String digest(Prompt prompt) {
        try {
            byte[] json = CANONICAL.writeValueAsBytes(canonical(prompt));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("could not digest the prompt", e);
        }
    }

    /** Everything the model receives in this request, as plain data. */
    static Map<String, Object> canonical(Prompt prompt) {
        var out = new LinkedHashMap<String, Object>();
        var messages = new ArrayList<Map<String, Object>>();
        if (prompt != null) {
            for (Message m : prompt.getInstructions()) {
                messages.add(message(m));
            }
            out.put("options", options(prompt.getOptions()));
        }
        out.put("messages", messages);
        return out;
    }

    private static Map<String, Object> message(Message m) {
        var out = new LinkedHashMap<String, Object>();
        out.put("role", m.getMessageType().getValue());
        out.put("text", m.getText() == null ? "" : m.getText());
        if (m instanceof MediaContent mc && mc.getMedia() != null && !mc.getMedia().isEmpty()) {
            var media = new ArrayList<Map<String, Object>>();
            for (Media item : mc.getMedia()) {
                media.add(media(item));
            }
            out.put("media", media);
        }
        if (m instanceof AssistantMessage am && am.getToolCalls() != null && !am.getToolCalls().isEmpty()) {
            var calls = new ArrayList<Map<String, Object>>();
            for (var call : am.getToolCalls()) {
                var c = new LinkedHashMap<String, Object>();
                c.put("id", call.id());
                c.put("type", call.type());
                c.put("name", call.name());
                c.put("arguments", call.arguments());
                calls.add(c);
            }
            out.put("tool_calls", calls);
        }
        if (m instanceof ToolResponseMessage tm && tm.getResponses() != null) {
            var responses = new ArrayList<Map<String, Object>>();
            for (var r : tm.getResponses()) {
                var c = new LinkedHashMap<String, Object>();
                c.put("id", r.id());
                c.put("name", r.name());
                c.put("response", r.responseData());
                responses.add(c);
            }
            out.put("tool_responses", responses);
        }
        return out;
    }

    private static Map<String, Object> media(Media media) {
        var out = new LinkedHashMap<String, Object>();
        out.put("mime_type", media.getMimeType() == null ? null : media.getMimeType().toString());
        Object data = media.getData();
        if (data instanceof byte[] bytes) {
            out.put("sha256", sha256(bytes));
        } else if (data != null) {
            out.put("url", data.toString());
        }
        return out;
    }

    /** Names handled explicitly below, or that cannot be serialized (tool callbacks hold code). */
    private static final java.util.Set<String> SPECIAL_PROPERTIES = java.util.Set.of(
            "class", "toolCallbacks", "toolNames", "toolContext", "internalToolExecutionEnabled");

    /**
     * Every readable property of the concrete options object (so provider-specific
     * options such as a tool choice or a seed are covered without knowing the
     * provider), plus its type, the tool names and definitions, and the tool context.
     */
    private static Map<String, Object> options(ChatOptions options) {
        var out = new LinkedHashMap<String, Object>();
        if (options == null) {
            return out;
        }
        out.put("type", options.getClass().getName());
        var properties = new java.util.TreeMap<String, Object>();
        for (var pd : org.springframework.beans.BeanUtils.getPropertyDescriptors(options.getClass())) {
            if (pd.getReadMethod() == null || SPECIAL_PROPERTIES.contains(pd.getName())) {
                continue;
            }
            try {
                properties.put(pd.getName(), plain(pd.getReadMethod().invoke(options)));
            } catch (ReflectiveOperationException | RuntimeException e) {
                properties.put(pd.getName(), "<unreadable: " + e.getClass().getSimpleName() + ">");
            }
        }
        out.put("properties", properties);
        if (options instanceof ToolCallingChatOptions tools) {
            out.put("tool_names", tools.getToolNames() == null ? List.of() : new TreeSet<>(tools.getToolNames()));
            var definitions = new ArrayList<Map<String, Object>>();
            if (tools.getToolCallbacks() != null) {
                for (ToolCallback cb : tools.getToolCallbacks()) {
                    var d = new LinkedHashMap<String, Object>();
                    d.put("name", cb.getToolDefinition().name());
                    d.put("description", cb.getToolDefinition().description());
                    d.put("input_schema", cb.getToolDefinition().inputSchema());
                    definitions.add(d);
                }
            }
            definitions.sort(Comparator.comparing(d -> String.valueOf(d.get("name"))));
            out.put("tool_definitions", definitions);
            out.put("internal_tool_execution", tools.getInternalToolExecutionEnabled());
            var context = new java.util.TreeMap<String, Object>();
            if (tools.getToolContext() != null) {
                tools.getToolContext().forEach((k, v) -> context.put(k, plain(v)));
            }
            out.put("tool_context", context);
        }
        return out;
    }

    /** A value as JSON-like data, or its string form when Jackson cannot represent it. */
    private static Object plain(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return CANONICAL.valueToTree(value);
        } catch (IllegalArgumentException e) {
            return value.getClass().getName() + ":" + value;
        }
    }

    /**
     * The request for a reviewer, one line per message ({@code [role] text}),
     * with media, tool calls, tool responses and offered tools. Control, line,
     * zero-width, bidirectional and tag characters are escaped, so the text
     * cannot hide or reorder what the reviewer sees. Cut on a character boundary
     * at {@link #PREVIEW_LIMIT}, with {@code " [truncated]"} only when something
     * was cut.
     */
    public static String preview(Prompt prompt) {
        var lines = new ArrayList<String>();
        if (prompt != null) {
            for (Message m : prompt.getInstructions()) {
                String role = m.getMessageType().getValue();
                lines.add("[" + role + "] " + escape(m.getText() == null ? "" : m.getText()));
                if (m instanceof MediaContent mc && mc.getMedia() != null) {
                    for (Media item : mc.getMedia()) {
                        var md = media(item);
                        lines.add("[" + role + " media] " + md.get("mime_type") + " "
                                + (md.containsKey("sha256") ? "sha256:" + md.get("sha256") : escape(String.valueOf(md.get("url")))));
                    }
                }
                if (m instanceof AssistantMessage am && am.getToolCalls() != null) {
                    for (var call : am.getToolCalls()) {
                        lines.add("[assistant tool call] " + escape(call.name()) + " " + escape(call.arguments()));
                    }
                }
                if (m instanceof ToolResponseMessage tm && tm.getResponses() != null) {
                    for (var r : tm.getResponses()) {
                        lines.add("[tool response] " + escape(r.name()) + " " + escape(r.responseData()));
                    }
                }
            }
            if (prompt.getOptions() instanceof ToolCallingChatOptions tools) {
                var names = new TreeSet<String>();
                if (tools.getToolNames() != null) {
                    names.addAll(tools.getToolNames());
                }
                if (tools.getToolCallbacks() != null) {
                    tools.getToolCallbacks().forEach(cb -> names.add(cb.getToolDefinition().name()));
                }
                if (!names.isEmpty()) {
                    lines.add("[tools] " + escape(String.join(", ", names)));
                }
            }
        }
        return bounded(String.join("\n", lines));
    }

    /** Cut at a code point (and so escape-sequence) boundary of the already escaped text. */
    private static String bounded(String text) {
        if (text.length() <= PREVIEW_LIMIT) {
            return text;
        }
        int end = PREVIEW_LIMIT;
        if (Character.isLowSurrogate(text.charAt(end)) && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        // Never end inside an escape such as ​ or \U000e0041.
        int backslash = text.lastIndexOf('\\', end - 1);
        if (backslash >= 0 && backslash >= end - 10) {
            int escapeEnd = escapeEnd(text, backslash);
            if (escapeEnd > end) {
                end = backslash;
            }
        }
        return text.substring(0, end) + TRUNCATED;
    }

    private static int escapeEnd(String text, int backslash) {
        if (backslash + 1 >= text.length()) {
            return backslash + 1;
        }
        return switch (text.charAt(backslash + 1)) {
            case 'u' -> backslash + 6;
            case 'U' -> backslash + 10;
            default -> backslash + 2;
        };
    }

    static String escape(String text) {
        if (text == null) {
            return "";
        }
        var out = new StringBuilder(text.length());
        text.codePoints().forEach(cp -> {
            switch (cp) {
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\\' -> out.append("\\\\");
                default -> {
                    if (hidden(cp)) {
                        if (cp > 0xFFFF) {
                            out.append(String.format("\\U%08x", cp));
                        } else {
                            out.append(String.format("\\u%04x", cp));
                        }
                    } else {
                        out.appendCodePoint(cp);
                    }
                }
            }
        });
        return out.toString();
    }

    /** Characters that are invisible, reorder text, or move the terminal cursor. */
    private static boolean hidden(int cp) {
        return cp < 0x20 || (cp >= 0x7f && cp <= 0x9f)
                || (cp >= 0x200b && cp <= 0x200f)       // zero-width space/joiners, LRM/RLM
                || (cp >= 0x202a && cp <= 0x202e)       // bidi embeddings and overrides
                || (cp >= 0x2060 && cp <= 0x2069)       // word joiner, invisible operators, bidi isolates
                || cp == 0x2028 || cp == 0x2029 || cp == 0x061c || cp == 0xfeff
                || (cp >= 0xe0000 && cp <= 0xe007f);    // tag characters
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
