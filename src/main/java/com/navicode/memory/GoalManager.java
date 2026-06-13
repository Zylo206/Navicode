package com.navicode.memory;

import com.navicode.llm.LlmClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

public class GoalManager {
    private final WorkspaceMemory workspaceMemory;

    public GoalManager(WorkspaceMemory workspaceMemory) {
        this.workspaceMemory = workspaceMemory;
    }

    public GoalState current() {
        String text = readGoalFile();
        if (text.isBlank()) {
            return GoalState.empty();
        }
        String status = readField(text, "Status");
        String objective = readSection(text, "Objective");
        String lastJudge = readSection(text, "Last Judge");
        if (objective.isBlank()) {
            return GoalState.empty();
        }
        return new GoalState(objective, status.isBlank() ? "active" : status, lastJudge);
    }

    public GoalState setGoal(String objective) {
        if (objective == null || objective.isBlank()) {
            return current();
        }
        GoalState state = new GoalState(objective.trim(), "active", "");
        writeGoalFile(state);
        workspaceMemory.updateCurrentGoal(state.objective());
        return state;
    }

    public void clearGoal() {
        try {
            Files.deleteIfExists(workspaceMemory.goalFile());
        } catch (IOException e) {
            throw new IllegalStateException("Unable to clear goal: " + e.getMessage(), e);
        }
        workspaceMemory.updateCurrentGoal("");
    }

    public String formatStatus() {
        GoalState state = current();
        if (!state.activeOrCompleted()) {
            return "No active goal. Use /goal set <objective> to define one.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Goal status: ").append(state.status()).append('\n');
        sb.append("Objective: ").append(state.objective());
        if (!state.lastJudge().isBlank()) {
            sb.append("\nLast judge: ").append(state.lastJudge());
        }
        return sb.toString();
    }

    public GoalJudgeResult judgeCompletion(LlmClient llmClient, String userInput, String assistantOutput) {
        GoalState state = current();
        if (!state.active()) {
            return GoalJudgeResult.none();
        }
        if (llmClient == null) {
            return recordJudge("continue", "Goal judge skipped because no LLM client is available.");
        }
        String prompt = """
                You are a strict completion judge for a coding-agent goal.
                Return exactly one line in this format:
                status: <complete|continue|blocked>
                reason: <short reason or next action>

                Goal:
                %s

                Latest user request:
                %s

                Assistant final answer:
                %s

                Current checkpoint:
                %s
                """.formatted(
                state.objective(),
                safeBlock(userInput, 1_000),
                safeBlock(assistantOutput, 1_500),
                safeBlock(workspaceMemory.readCheckpointSummary(2_000), 2_000));
        try {
            LlmClient.ChatResponse response = llmClient.chat(
                    List.of(LlmClient.Message.user(prompt)),
                    List.of());
            return recordJudge(parseStatus(response.content()), parseReason(response.content()));
        } catch (IOException | RuntimeException e) {
            return recordJudge("continue", "Goal judge unavailable: " + e.getMessage());
        }
    }

    private GoalJudgeResult recordJudge(String status, String reason) {
        String normalizedStatus = normalizeStatus(status);
        GoalState previous = current();
        String lastJudge = normalizedStatus + " - " + (reason == null ? "" : reason.trim());
        GoalState next = new GoalState(previous.objective(), normalizedStatus.equals("complete") ? "completed" : "active", lastJudge);
        writeGoalFile(next);
        workspaceMemory.recordGoalJudge(normalizedStatus, reason);
        return new GoalJudgeResult(normalizedStatus, reason == null ? "" : reason.trim(), true);
    }

    private void writeGoalFile(GoalState state) {
        String content = """
                # Goal

                Status: %s
                Updated: %s

                ## Objective

                %s

                ## Last Judge

                %s
                """.formatted(state.status(), Instant.now(), state.objective(), state.lastJudge());
        try {
            Files.createDirectories(workspaceMemory.goalFile().getParent());
            Files.writeString(workspaceMemory.goalFile(), content.trim() + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to write goal: " + e.getMessage(), e);
        }
    }

    private String readGoalFile() {
        try {
            return Files.exists(workspaceMemory.goalFile())
                    ? Files.readString(workspaceMemory.goalFile(), StandardCharsets.UTF_8)
                    : "";
        } catch (IOException e) {
            return "";
        }
    }

    private static String readField(String text, String field) {
        for (String line : text.split("\\R")) {
            String prefix = field + ":";
            if (line.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return line.substring(prefix.length()).trim();
            }
        }
        return "";
    }

    private static String readSection(String text, String title) {
        String marker = "## " + title;
        int start = text.indexOf(marker);
        if (start < 0) {
            return "";
        }
        start += marker.length();
        int next = text.indexOf("\n## ", start);
        String section = next < 0 ? text.substring(start) : text.substring(start, next);
        return section.trim();
    }

    private static String parseStatus(String text) {
        if (text == null) {
            return "continue";
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("status: complete") || lower.startsWith("complete")) {
            return "complete";
        }
        if (lower.contains("status: blocked") || lower.startsWith("blocked")) {
            return "blocked";
        }
        return "continue";
    }

    private static String parseReason(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        for (String line : text.split("\\R")) {
            if (line.regionMatches(true, 0, "reason:", 0, 7)) {
                return line.substring(7).trim();
            }
        }
        return text.trim();
    }

    private static String normalizeStatus(String status) {
        if ("complete".equalsIgnoreCase(status)) {
            return "complete";
        }
        if ("blocked".equalsIgnoreCase(status)) {
            return "blocked";
        }
        return "continue";
    }

    private static String safeBlock(String text, int maxChars) {
        if (text == null || text.isBlank()) {
            return "(empty)";
        }
        String trimmed = text.trim();
        return trimmed.length() <= maxChars ? trimmed : trimmed.substring(0, maxChars) + "\n[truncated]";
    }

    public record GoalState(String objective, String status, String lastJudge) {
        static GoalState empty() {
            return new GoalState("", "none", "");
        }

        boolean active() {
            return "active".equalsIgnoreCase(status) && !objective.isBlank();
        }

        boolean activeOrCompleted() {
            return !objective.isBlank();
        }
    }

    public record GoalJudgeResult(String status, String reason, boolean evaluated) {
        static GoalJudgeResult none() {
            return new GoalJudgeResult("none", "", false);
        }

        public String userMessage() {
            if (!evaluated) {
                return "";
            }
            return switch (status) {
                case "complete" -> "Goal judge: complete. " + reason;
                case "blocked" -> "Goal judge: blocked. " + reason;
                default -> "Goal judge: continue. " + reason;
            };
        }
    }
}
