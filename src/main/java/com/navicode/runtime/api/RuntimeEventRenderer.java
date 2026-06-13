package com.navicode.runtime.api;

import com.navicode.hitl.ApprovalRequest;
import com.navicode.hitl.ApprovalResult;
import com.navicode.llm.LlmClient;
import com.navicode.render.Renderer;
import com.navicode.render.StatusInfo;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

public final class RuntimeEventRenderer implements Renderer {
    private static final Pattern ANSI = Pattern.compile("\u001B\\[[;?0-9]*[ -/]*[@-~]");
    private final RuntimeTurnContext context;
    private final PrintStream stream;

    public RuntimeEventRenderer(RuntimeTurnContext context) {
        this.context = context;
        this.stream = new PrintStream(new EventOutputStream(), true, StandardCharsets.UTF_8);
    }

    @Override
    public void start() {
    }

    @Override
    public void close() {
        stream.flush();
    }

    @Override
    public PrintStream stream() {
        return stream;
    }

    @Override
    public void appendToolCalls(List<LlmClient.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return;
        }
        StringBuilder tools = new StringBuilder("[");
        for (int i = 0; i < toolCalls.size(); i++) {
            LlmClient.ToolCall call = toolCalls.get(i);
            if (i > 0) {
                tools.append(',');
            }
            tools.append("{\"name\":\"")
                    .append(RuntimeTurnContext.quote(call.function().name()))
                    .append("\"}");
        }
        tools.append(']');
        context.emit("tool.calls", "{\"turn_id\":\"" + RuntimeTurnContext.quote(context.turnId())
                + "\",\"tools\":" + tools + "}");
    }

    @Override
    public void appendDiff(String filePath, String before, String after) {
        int beforeLen = before == null ? 0 : before.length();
        int afterLen = after == null ? 0 : after.length();
        context.emit("diff.summary", "{\"turn_id\":\"" + RuntimeTurnContext.quote(context.turnId())
                + "\",\"path\":\"" + RuntimeTurnContext.quote(filePath)
                + "\",\"before_chars\":" + beforeLen
                + ",\"after_chars\":" + afterLen + "}");
    }

    @Override
    public void updateStatus(StatusInfo status) {
    }

    @Override
    public ApprovalResult promptApproval(ApprovalRequest request) {
        context.emit("approval.required", "{\"turn_id\":\"" + RuntimeTurnContext.quote(context.turnId())
                + "\",\"tool\":\"" + RuntimeTurnContext.quote(request.toolName())
                + "\",\"status\":\"rejected_headless\"}");
        return ApprovalResult.reject("Runtime API headless mode cannot approve HITL requests.");
    }

    @Override
    public int openPalette(String title, List<String> items) {
        return -1;
    }

    private final class EventOutputStream extends OutputStream {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        @Override
        public synchronized void write(int b) {
            buffer.write(b);
            if (b == '\n') {
                flush();
            }
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            buffer.write(b, off, len);
            if (len > 0 && b[off + len - 1] == '\n') {
                flush();
            }
        }

        @Override
        public synchronized void flush() {
            if (buffer.size() == 0) {
                return;
            }
            String text = buffer.toString(StandardCharsets.UTF_8);
            buffer.reset();
            text = ANSI.matcher(text).replaceAll("");
            if (!text.isBlank()) {
                context.emitMessageDelta(text);
            }
        }
    }
}
