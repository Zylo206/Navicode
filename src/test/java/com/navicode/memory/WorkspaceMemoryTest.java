package com.navicode.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceMemoryTest {

    @Test
    void initializesAuditableWorkspaceFiles(@TempDir Path tempDir) {
        WorkspaceMemory memory = new WorkspaceMemory(tempDir);

        assertTrue(Files.exists(memory.projectMemoryFile()));
        assertTrue(Files.exists(memory.checkpointFile()));
        assertTrue(Files.exists(memory.notesFile()));
    }

    @Test
    void appendsFactsAndBuildsPromptContext(@TempDir Path tempDir) throws Exception {
        WorkspaceMemory memory = new WorkspaceMemory(tempDir);

        memory.appendProjectFact("Project uses Java 17");
        String context = memory.buildPromptContext(1_000);

        assertTrue(Files.readString(memory.projectMemoryFile()).contains("Project uses Java 17"));
        assertTrue(context.contains("Workspace Memory"));
        assertTrue(context.contains("Project uses Java 17"));
    }

    @Test
    void writesStructuredCheckpointAndTaskProgress(@TempDir Path tempDir) throws Exception {
        WorkspaceMemory memory = new WorkspaceMemory(tempDir);

        memory.updateCheckpoint("fix tests", "tests passed");
        memory.appendTaskProgress("T1.1", "done: fix tests");

        String checkpoint = Files.readString(memory.checkpointFile());
        assertTrue(checkpoint.contains("## Current Goal"));
        assertTrue(checkpoint.contains("## Completed"));
        assertTrue(checkpoint.contains("## In Progress"));
        assertTrue(checkpoint.contains("## Blockers"));
        assertTrue(checkpoint.contains("## Key Files"));
        assertTrue(checkpoint.contains("## Verification Commands"));
        assertTrue(checkpoint.contains("## Next Steps"));
        assertTrue(checkpoint.contains("## Work Tasks"));
        assertTrue(checkpoint.contains("fix tests"));
        assertTrue(Files.readString(tempDir.resolve(".navicode/memory/tasks/T1.1/progress.md"))
                .contains("done: fix tests"));
    }

    @Test
    void updatesGoalAndWorkTasksWithoutOverwritingOtherCheckpointSections(@TempDir Path tempDir) throws Exception {
        WorkspaceMemory memory = new WorkspaceMemory(tempDir);

        memory.updateCurrentGoal("finish long task recovery");
        memory.updateWorkTasks(List.of("T1 (in_progress): implement goal"));

        String checkpoint = Files.readString(memory.checkpointFile());
        assertTrue(checkpoint.contains("finish long task recovery"));
        assertTrue(checkpoint.contains("T1 (in_progress): implement goal"));
    }

    @Test
    void dreamDraftDoesNotModifyProjectMemory(@TempDir Path tempDir) throws Exception {
        WorkspaceMemory memory = new WorkspaceMemory(tempDir);
        memory.updateCheckpoint("add structured checkpoint", "tests passed");
        String before = Files.readString(memory.projectMemoryFile());

        String draft = memory.buildDreamSuggestionDraft();

        assertTrue(draft.contains("Dream Memory Suggestions"));
        assertTrue(draft.contains("No changes were applied"));
        assertEquals(before, Files.readString(memory.projectMemoryFile()));
    }
}
