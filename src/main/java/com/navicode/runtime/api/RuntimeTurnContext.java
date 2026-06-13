package com.navicode.runtime.api;

public record RuntimeTurnContext(
        String threadId,
        String turnId,
        String input,
        String cwd,
        RuntimeEventSink events
) {
    public RuntimeTurnContext(String threadId, String turnId, String input, RuntimeEventSink events) {
        this(threadId, turnId, input, null, events);
    }

    public void emitMessageDelta(String content) {
        if (content == null || content.isEmpty()) {
            return;
        }
        emit("message.delta", "{\"turn_id\":\"" + quote(turnId) + "\",\"content\":\"" + quote(content) + "\"}");
    }

    public void emit(String eventType, String dataJson) {
        events.append(eventType, dataJson == null || dataJson.isBlank() ? "{}" : dataJson);
    }

    public static String quote(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) ch));
                    } else {
                        escaped.append(ch);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
