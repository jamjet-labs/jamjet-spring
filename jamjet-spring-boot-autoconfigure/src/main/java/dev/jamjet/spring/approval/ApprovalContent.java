package dev.jamjet.spring.approval;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.Prompt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * What a reviewer approves: a digest binding the decision to the exact prompt,
 * and a bounded, escaped preview to show.
 */
public final class ApprovalContent {

    /** Longest preview shown in {@code /pending}, in characters. */
    public static final int PREVIEW_LIMIT = 2000;

    private ApprovalContent() {}

    /**
     * SHA-256 (hex) over every message of the prompt, in order, each as its role
     * and its text, length-prefixed so no two different prompts encode the same.
     */
    public static String digest(Prompt prompt) {
        try {
            var sha = MessageDigest.getInstance("SHA-256");
            if (prompt != null) {
                for (Message m : prompt.getInstructions()) {
                    update(sha, m.getMessageType().getValue());
                    update(sha, m.getText() == null ? "" : m.getText());
                }
            }
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void update(MessageDigest sha, String part) {
        byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
        sha.update((bytes.length + ":").getBytes(StandardCharsets.UTF_8));
        sha.update(bytes);
    }

    /**
     * The prompt text for a reviewer: control characters, line breaks and
     * bidirectional overrides escaped (so the text cannot restyle or reorder what
     * the reviewer sees), cut at {@link #PREVIEW_LIMIT} characters.
     */
    public static String preview(Prompt prompt) {
        String text = prompt == null || prompt.getContents() == null ? "" : prompt.getContents();
        var out = new StringBuilder();
        for (int i = 0; i < text.length() && out.length() < PREVIEW_LIMIT; i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\\' -> out.append("\\\\");
                default -> {
                    if (c < 0x20 || (c >= 0x7f && c <= 0x9f) || (c >= 0x202a && c <= 0x202e)
                            || (c >= 0x2066 && c <= 0x2069) || c == 0x2028 || c == 0x2029 || c == 0x200e
                            || c == 0x200f || c == 0x061c) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        if (out.length() >= PREVIEW_LIMIT && text.length() > 0) {
            out.setLength(Math.min(out.length(), PREVIEW_LIMIT));
            out.append(" [truncated]");
        }
        return out.toString();
    }
}
