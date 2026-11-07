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
import java.util.Map;
import java.util.TreeSet;

/**
 * What a reviewer approves: a digest binding the decision to everything the
 * model receives in the request, and a bounded, escaped preview to show.
 *
 * <p>Covered: every message's role and text, its media (MIME type and a SHA-256
 * of the bytes, or the URL), an assistant message's tool calls, a tool message's
 * responses, and the options (model, sampling settings, provider options, the tool
 * definitions with their return-direct flag, and the tool context). Not covered:
 * the tool calls Spring AI's tool-calling advisor runs after the model answered;
 * those are not individually reviewed here.
 *
 * <p>Spring AI 2.0 removed the per-request tool names and the internal tool
 * execution flag from {@link ToolCallingChatOptions}: the ChatClient resolves every
 * tool to a callback before the advisors run, and a {@code ToolCallingAdvisor} in
 * the advisor chain, not the model, runs the tool calls. The offered tools are
 * therefore exactly the callbacks digested here.
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

    /** SHA-256 (hex) of each message's canonical form, in order. */
    public static java.util.List<String> messageDigests(Prompt prompt) {
        var out = new ArrayList<String>();
        if (prompt != null) {
            for (Message m : prompt.getInstructions()) {
                out.add(messageDigest(m));
            }
        }
        return out;
    }

    /** SHA-256 (hex) of one message's canonical form: role, text, media, tool calls, tool responses. */
    public static String messageDigest(Message message) {
        return sha256Of(message(message));
    }

    /** SHA-256 (hex) of the canonical options: every property, the tool definitions and the tool context. */
    public static String optionsDigest(Prompt prompt) {
        return sha256Of(prompt == null ? Map.of() : options(prompt.getOptions()));
    }

    private static String sha256Of(Object canonical) {
        try {
            return sha256(CANONICAL.writeValueAsBytes(canonical));
        } catch (JsonProcessingException e) {
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
            "class", "toolCallbacks", "toolContext");

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
            var definitions = new ArrayList<Map<String, Object>>();
            if (tools.getToolCallbacks() != null) {
                for (ToolCallback cb : tools.getToolCallbacks()) {
                    var d = new LinkedHashMap<String, Object>();
                    d.put("name", cb.getToolDefinition().name());
                    d.put("description", cb.getToolDefinition().description());
                    d.put("input_schema", cb.getToolDefinition().inputSchema());
                    // A return-direct tool's result goes to the caller instead of the model.
                    d.put("return_direct", cb.getToolMetadata() != null && cb.getToolMetadata().returnDirect());
                    definitions.add(d);
                }
            }
            definitions.sort(Comparator.comparing(d -> String.valueOf(d.get("name"))));
            out.put("tool_definitions", definitions);
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

    /** Lines longer than this are cut to their start and end when the preview is too long. */
    private static final int LINE_LIMIT = 480;
    private static final int LINE_HEAD = 300;
    private static final int LINE_TAIL = 120;
    /** Share of the limit kept from the start when whole lines must be left out. */
    private static final int HEAD_BUDGET = PREVIEW_LIMIT * 2 / 5;

    /**
     * The request for a reviewer, one line per message ({@code [role] text}),
     * with media, tool calls, tool responses and offered tools. Control, line,
     * zero-width, bidirectional and tag characters are escaped, so the text
     * cannot hide or reorder what the reviewer sees. A tool call whose result is
     * already in the request is labelled {@code already executed}: the tool loop
     * ran it before this round reached the model.
     *
     * <p>At most {@link #PREVIEW_LIMIT} characters. When the request is longer,
     * long lines keep their start and end with {@code [... N characters not shown ...]}
     * between, and if that is not enough the first and the newest lines are kept
     * with {@code [... N lines not shown ...]} between, so neither the original
     * request nor the newest tool calls drop out.
     */
    public static String preview(Prompt prompt) {
        return preview(prompt, 0);
    }

    /**
     * {@link #preview(Prompt)} for a request whose first {@code approvedMessageCount}
     * messages were already approved: a {@code [--- new since the last approval ---]}
     * line marks where the new messages start.
     */
    public static String preview(Prompt prompt, int approvedMessageCount) {
        var lines = new ArrayList<String>();
        if (prompt != null) {
            var executed = new java.util.HashSet<String>();
            for (Message m : prompt.getInstructions()) {
                if (m instanceof ToolResponseMessage tm && tm.getResponses() != null) {
                    tm.getResponses().forEach(r -> executed.add(r.id()));
                }
            }
            var messages = prompt.getInstructions();
            for (int i = 0; i < messages.size(); i++) {
                Message m = messages.get(i);
                if (approvedMessageCount > 0 && i == approvedMessageCount) {
                    lines.add("[--- new since the last approval ---]");
                }
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
                        lines.add((executed.contains(call.id()) ? "[assistant tool call, already executed] "
                                : "[assistant tool call] ") + escape(call.name()) + " " + escape(call.arguments()));
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
                if (tools.getToolCallbacks() != null) {
                    tools.getToolCallbacks().forEach(cb -> names.add(cb.getToolDefinition().name()));
                }
                if (!names.isEmpty()) {
                    lines.add("[tools] " + escape(String.join(", ", names)));
                }
            }
        }
        return fit(lines);
    }

    private static String fit(java.util.List<String> lines) {
        String all = String.join("\n", lines);
        if (all.length() <= PREVIEW_LIMIT) {
            return all;
        }
        var shortened = new ArrayList<String>(lines.size());
        for (String line : lines) {
            shortened.add(line.length() > LINE_LIMIT ? shortenLine(line) : line);
        }
        all = String.join("\n", shortened);
        if (all.length() <= PREVIEW_LIMIT) {
            return all;
        }
        // Keep the first lines and the newest ones, leave out the middle.
        int head = 0;
        int used = 0;
        while (head < shortened.size() && used + shortened.get(head).length() + 1 <= HEAD_BUDGET) {
            used += shortened.get(head).length() + 1;
            head++;
        }
        int reserve = 48; // the gap line
        int tail = shortened.size();
        while (tail > head && used + shortened.get(tail - 1).length() + 1 + reserve <= PREVIEW_LIMIT) {
            used += shortened.get(tail - 1).length() + 1;
            tail--;
        }
        var kept = new ArrayList<String>(shortened.subList(0, head));
        kept.add("[... " + (tail - head) + " lines not shown ...]");
        kept.addAll(shortened.subList(tail, shortened.size()));
        return bounded(String.join("\n", kept));
    }

    /** First and last characters of an escaped line, never splitting a character or an escape. */
    private static String shortenLine(String line) {
        int headEnd = safeEnd(line, LINE_HEAD);
        int tailStart = safeStart(line, line.length() - LINE_TAIL);
        if (tailStart <= headEnd) {
            return line;
        }
        return line.substring(0, headEnd) + " [... " + (tailStart - headEnd) + " characters not shown ...] "
                + line.substring(tailStart);
    }

    /** An index at or before {@code end} that does not split a surrogate pair or an escape. */
    private static int safeEnd(String text, int end) {
        if (end >= text.length()) {
            return text.length();
        }
        if (Character.isLowSurrogate(text.charAt(end)) && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        int backslash = lastEscapeStart(text, end);
        if (backslash >= 0 && escapeEnd(text, backslash) > end) {
            end = backslash;
        }
        return end;
    }

    /** An index at or after {@code start} that does not split a surrogate pair or an escape. */
    private static int safeStart(String text, int start) {
        if (start <= 0) {
            return 0;
        }
        int backslash = lastEscapeStart(text, start);
        if (backslash >= 0 && escapeEnd(text, backslash) > start) {
            start = escapeEnd(text, backslash);
        }
        if (start < text.length() && Character.isLowSurrogate(text.charAt(start))
                && Character.isHighSurrogate(text.charAt(start - 1))) {
            start++;
        }
        return Math.min(start, text.length());
    }

    /**
     * The start of the last escape that begins before {@code at}, or -1. Escapes
     * are found by scanning from the start of the text, so an escaped backslash
     * ({@code \\\\}) is never mistaken for the start of another escape.
     */
    private static int lastEscapeStart(String text, int at) {
        int last = -1;
        int scan = 0;
        while (scan < at) {
            if (text.charAt(scan) == '\\') {
                last = scan;
                scan = escapeEnd(text, scan);
            } else {
                scan++;
            }
        }
        return last;
    }

    /** Cut at a code point (and so escape-sequence) boundary of the already escaped text. */
    private static String bounded(String text) {
        if (text.length() <= PREVIEW_LIMIT) {
            return text;
        }
        int end = safeEnd(text, PREVIEW_LIMIT - TRUNCATED.length());
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
