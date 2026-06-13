package com.navicode.memory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Project-visible memory files under .navicode/memory.
 *
 * <p>The existing LongTermMemory remains the durable searchable store. This
 * layer gives humans and future sessions an auditable checkpoint surface that
 * can be inspected, edited, or copied with the workspace.</p>
 */
public class WorkspaceMemory {
    private static final int DEFAULT_CONTEXT_BUDGET = 2_000;
    private static final String SECTION_CURRENT_GOAL = "Current Goal";
    private static final String SECTION_COMPLETED = "Completed";
    private static final String SECTION_IN_PROGRESS = "In Progress";
    private static final String SECTION_BLOCKERS = "Blockers";
    private static final String SECTION_KEY_FILES = "Key Files";
    private static final String SECTION_VERIFICATION_COMMANDS = "Verification Commands";
    private static final String SECTION_NEXT_STEPS = "Next Steps";
    private static final String SECTION_WORK_TASKS = "Work Tasks";
    private static final List<String> CHECKPOINT_SECTIONS = List.of(
            SECTION_CURRENT_GOAL,
            SECTION_COMPLETED,
            SECTION_IN_PROGRESS,
            SECTION_BLOCKERS,
            SECTION_KEY_FILES,
            SECTION_VERIFICATION_COMMANDS,
            SECTION_NEXT_STEPS,
            SECTION_WORK_TASKS
    );

    private final Path root;

    public WorkspaceMemory(Path projectRoot) {
        Path normalized = projectRoot == null ? Path.of(".") : projectRoot;
        this.root = normalized.toAbsolutePath().normalize().resolve(".navicode").resolve("memory");
        ensureInitialized();
    }

    public static WorkspaceMemory forProject(String projectPath) {
        return new WorkspaceMemory(projectPath == null || projectPath.isBlank()
                ? Path.of(".")
                : Path.of(projectPath));
    }

    public Path root() {
        return root;
    }

    public Path projectMemoryFile() {
        return root.resolve("MEMORY.md");
    }

    public Path checkpointFile() {
        return root.resolve("checkpoint.md");
    }

    public Path notesFile() {
        return root.resolve("notes.md");
    }

    public Path goalFile() {
        return root.resolve("goal.md");
    }

    public void appendProjectFact(String fact) {
        if (fact == null || fact.isBlank()) {
            return;
        }
        ensureInitialized();
        appendLine(projectMemoryFile(), "- " + fact.trim());
    }

    /**
     * Backward-compatible checkpoint writer. The checkpoint is now structured,
     * but old callers still provide only the latest user request and result.
     */
    public void updateCheckpoint(String userInput, String assistantOutput) {
        ensureInitialized();
        updateStructuredCheckpoint(CheckpointUpdate.builder()
                .completed(List.of("Last assistant result: " + oneLine(assistantOutput)))
                .inProgress(List.of("Last user request: " + oneLine(userInput)))
                .nextSteps(List.of("Continue from the latest user request and checkpoint state."))
                .build());
    }

    public void updateCurrentGoal(String goal) {
        updateStructuredCheckpoint(CheckpointUpdate.builder()
                .currentGoal(blankList(goal))
                .build());
    }

    public void recordGoalJudge(String status, String reason) {
        String normalizedStatus = status == null || status.isBlank() ? "unknown" : status.trim();
        String normalizedReason = oneLine(reason);
        CheckpointUpdate.Builder builder = CheckpointUpdate.builder();
        if ("complete".equalsIgnoreCase(normalizedStatus)) {
            builder.completed(List.of("Goal judge: complete - " + normalizedReason));
            builder.nextSteps(List.of("No goal follow-up required unless the user sets a new goal."));
        } else if ("blocked".equalsIgnoreCase(normalizedStatus)) {
            builder.blockers(List.of("Goal judge: blocked - " + normalizedReason));
            builder.nextSteps(List.of("Resolve the blocker or adjust the active goal."));
        } else {
            builder.inProgress(List.of("Goal judge: continue - " + normalizedReason));
            builder.nextSteps(List.of(normalizedReason.isBlank()
                    ? "Continue working toward the active goal."
                    : normalizedReason));
        }
        updateStructuredCheckpoint(builder.build());
    }

    public void updateWorkTasks(List<String> taskLines) {
        updateStructuredCheckpoint(CheckpointUpdate.builder()
                .workTasks(taskLines == null ? List.of() : List.copyOf(taskLines))
                .build());
    }

    public void updateStructuredCheckpoint(CheckpointUpdate update) {
        ensureInitialized();
        Map<String, List<String>> current = readStructuredCheckpoint();
        merge(current, SECTION_CURRENT_GOAL, update.currentGoal());
        merge(current, SECTION_COMPLETED, update.completed());
        merge(current, SECTION_IN_PROGRESS, update.inProgress());
        merge(current, SECTION_BLOCKERS, update.blockers());
        merge(current, SECTION_KEY_FILES, update.keyFiles());
        merge(current, SECTION_VERIFICATION_COMMANDS, update.verificationCommands());
        merge(current, SECTION_NEXT_STEPS, update.nextSteps());
        merge(current, SECTION_WORK_TASKS, update.workTasks());
        write(checkpointFile(), renderCheckpoint(current));
    }

    public void appendTaskProgress(String taskId, String line) {
        if (taskId == null || taskId.isBlank() || line == null || line.isBlank()) {
            return;
        }
        ensureInitialized();
        Path file = root.resolve("tasks").resolve(safeTaskId(taskId)).resolve("progress.md");
        appendLine(file, "- " + Instant.now() + " " + line.trim());
    }

    public String buildPromptContext(int maxTokens) {
        ensureInitialized();
        int budget = maxTokens <= 0 ? DEFAULT_CONTEXT_BUDGET : maxTokens;
        List<String> sections = new ArrayList<>();
        addIfPresent(sections, "Project Memory", projectMemoryFile());
        addIfPresent(sections, "Session Checkpoint", checkpointFile());
        addIfPresent(sections, "Scratch Notes", notesFile());
        if (sections.isEmpty()) {
            return "";
        }
        String joined = String.join("\n\n", sections);
        int tokens = MemoryEntry.estimateTokens(joined);
        if (tokens <= budget) {
            return "## Workspace Memory\n\n" + joined;
        }
        int keepChars = Math.max(0, (int) (joined.length() * (budget / (double) tokens) * 0.95));
        String truncated = joined.substring(0, Math.min(joined.length(), keepChars));
        int lastNewline = truncated.lastIndexOf('\n');
        if (lastNewline > 0) {
            truncated = truncated.substring(0, lastNewline);
        }
        return "## Workspace Memory\n\n" + truncated
                + "\n\n[workspace memory truncated at about " + budget + " tokens]";
    }

    public String statusLine() {
        return "Workspace memory: " + root;
    }

    public String readCheckpointSummary(int maxChars) {
        ensureInitialized();
        String text = readFile(checkpointFile()).trim();
        int bounded = Math.max(100, maxChars);
        return text.length() <= bounded ? text : text.substring(0, bounded) + "\n[truncated]";
    }

    public String buildDreamSuggestionDraft() {
        ensureInitialized();
        String currentMemory = readFile(projectMemoryFile());
        List<String> candidates = new ArrayList<>();
        collectCandidates(candidates, checkpointFile(), currentMemory);
        collectCandidates(candidates, notesFile(), currentMemory);
        Path tasksRoot = root.resolve("tasks");
        if (Files.exists(tasksRoot)) {
            try (Stream<Path> paths = Files.walk(tasksRoot)) {
                paths.filter(path -> path.getFileName().toString().equals("progress.md"))
                        .sorted()
                        .forEach(path -> collectCandidates(candidates, path, currentMemory));
            } catch (IOException ignored) {
                // Dream suggestions are best-effort and must not block the CLI.
            }
        }
        List<String> unique = candidates.stream()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .distinct()
                .limit(20)
                .toList();
        StringBuilder sb = new StringBuilder();
        sb.append("# Dream Memory Suggestions\n\n");
        sb.append("No changes were applied. Review these candidates before saving durable memory.\n\n");
        sb.append("## Candidate MEMORY.md Additions\n\n");
        if (unique.isEmpty()) {
            sb.append("- No high-confidence candidates found.\n");
        } else {
            for (String candidate : unique) {
                sb.append("- ").append(candidate).append('\n');
            }
        }
        sb.append("\n## Suggested Diff\n\n```diff\n");
        for (String candidate : unique) {
            sb.append("+ - ").append(candidate).append('\n');
        }
        sb.append("```\n");
        return sb.toString();
    }

    private void ensureInitialized() {
        try {
            Files.createDirectories(root.resolve("tasks"));
            createIfMissing(projectMemoryFile(), """
                    # Project Memory

                    Stable project knowledge, rules, and architecture decisions.
                    """);
            createIfMissing(checkpointFile(), """
                    # Session Checkpoint

                    Updated: never

                    ## Current Goal

                    - None

                    ## Completed

                    - None

                    ## In Progress

                    - None

                    ## Blockers

                    - None

                    ## Key Files

                    - None

                    ## Verification Commands

                    - None

                    ## Next Steps

                    - None

                    ## Work Tasks

                    - None
                    """);
            createIfMissing(notesFile(), """
                    # Scratch Notes

                    Temporary notes for agents. Keep durable facts in MEMORY.md.
                    """);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to initialize workspace memory: " + e.getMessage(), e);
        }
    }

    private static void createIfMissing(Path file, String content) throws IOException {
        if (!Files.exists(file)) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content.trim() + "\n", StandardCharsets.UTF_8);
        }
    }

    private static void appendLine(Path file, String line) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, line + "\n", StandardCharsets.UTF_8,
                    Files.exists(file)
                            ? new java.nio.file.OpenOption[]{java.nio.file.StandardOpenOption.APPEND}
                            : new java.nio.file.OpenOption[]{java.nio.file.StandardOpenOption.CREATE});
        } catch (IOException e) {
            throw new IllegalStateException("Unable to write workspace memory: " + e.getMessage(), e);
        }
    }

    private static void write(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content.trim() + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to write workspace checkpoint: " + e.getMessage(), e);
        }
    }

    private static void addIfPresent(List<String> sections, String title, Path file) {
        try {
            if (!Files.exists(file)) {
                return;
            }
            String text = Files.readString(file, StandardCharsets.UTF_8).trim();
            if (text.isBlank()) {
                return;
            }
            sections.add("### " + title + "\n\n" + text);
        } catch (IOException ignored) {
            // Prompt construction must not fail the agent run.
        }
    }

    private Map<String, List<String>> readStructuredCheckpoint() {
        Map<String, List<String>> sections = emptySections();
        String text = readFile(checkpointFile());
        if (text.isBlank()) {
            return sections;
        }
        String currentSection = null;
        for (String rawLine : text.split("\\R")) {
            String line = rawLine.trim();
            if (line.startsWith("## ")) {
                String title = line.substring(3).trim();
                currentSection = CHECKPOINT_SECTIONS.contains(title) ? title : null;
                continue;
            }
            if (currentSection == null || line.isBlank()) {
                continue;
            }
            if (line.startsWith("- ")) {
                line = line.substring(2).trim();
            }
            if (!line.isBlank() && !"None".equalsIgnoreCase(line)) {
                sections.get(currentSection).add(line);
            }
        }
        return sections;
    }

    private static Map<String, List<String>> emptySections() {
        Map<String, List<String>> sections = new LinkedHashMap<>();
        for (String section : CHECKPOINT_SECTIONS) {
            sections.put(section, new ArrayList<>());
        }
        return sections;
    }

    private static void merge(Map<String, List<String>> sections, String section, List<String> values) {
        if (values == null) {
            return;
        }
        sections.put(section, values.stream()
                .map(WorkspaceMemory::oneLine)
                .filter(value -> !value.isBlank())
                .toList());
    }

    private static String renderCheckpoint(Map<String, List<String>> sections) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Session Checkpoint\n\n");
        sb.append("Updated: ").append(Instant.now()).append("\n");
        for (String section : CHECKPOINT_SECTIONS) {
            sb.append("\n## ").append(section).append("\n\n");
            List<String> values = sections.getOrDefault(section, List.of());
            if (values.isEmpty()) {
                sb.append("- None\n");
            } else {
                for (String value : values) {
                    sb.append("- ").append(value).append('\n');
                }
            }
        }
        return sb.toString();
    }

    private static List<String> blankList(String value) {
        String normalized = oneLine(value);
        return normalized.isBlank() ? List.of() : List.of(normalized);
    }

    private static String oneLine(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replaceAll("\\R+", " ").trim();
        return normalized.length() <= 240 ? normalized : normalized.substring(0, 237) + "...";
    }

    private static String readFile(Path file) {
        try {
            return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            return "";
        }
    }

    private static void collectCandidates(List<String> candidates, Path file, String existingMemory) {
        String text = readFile(file);
        for (String rawLine : text.split("\\R")) {
            String line = rawLine.trim();
            if (line.startsWith("- ")) {
                line = line.substring(2).trim();
            }
            if (line.length() < 12 || line.equalsIgnoreCase("None")) {
                continue;
            }
            if (existingMemory != null && existingMemory.contains(line)) {
                continue;
            }
            candidates.add(oneLine(line));
        }
    }

    private static String safeTaskId(String taskId) {
        return taskId.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    public record CheckpointUpdate(
            List<String> currentGoal,
            List<String> completed,
            List<String> inProgress,
            List<String> blockers,
            List<String> keyFiles,
            List<String> verificationCommands,
            List<String> nextSteps,
            List<String> workTasks
    ) {
        public static Builder builder() {
            return new Builder();
        }

        public static final class Builder {
            private List<String> currentGoal;
            private List<String> completed;
            private List<String> inProgress;
            private List<String> blockers;
            private List<String> keyFiles;
            private List<String> verificationCommands;
            private List<String> nextSteps;
            private List<String> workTasks;

            public Builder currentGoal(List<String> currentGoal) {
                this.currentGoal = currentGoal;
                return this;
            }

            public Builder completed(List<String> completed) {
                this.completed = completed;
                return this;
            }

            public Builder inProgress(List<String> inProgress) {
                this.inProgress = inProgress;
                return this;
            }

            public Builder blockers(List<String> blockers) {
                this.blockers = blockers;
                return this;
            }

            public Builder keyFiles(List<String> keyFiles) {
                this.keyFiles = keyFiles;
                return this;
            }

            public Builder verificationCommands(List<String> verificationCommands) {
                this.verificationCommands = verificationCommands;
                return this;
            }

            public Builder nextSteps(List<String> nextSteps) {
                this.nextSteps = nextSteps;
                return this;
            }

            public Builder workTasks(List<String> workTasks) {
                this.workTasks = workTasks;
                return this;
            }

            public CheckpointUpdate build() {
                return new CheckpointUpdate(currentGoal, completed, inProgress, blockers,
                        keyFiles, verificationCommands, nextSteps, workTasks);
            }
        }
    }
}
