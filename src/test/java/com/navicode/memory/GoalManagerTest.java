package com.navicode.memory;

import com.navicode.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoalManagerTest {

    @Test
    void persistsSetShowAndClear(@TempDir Path tempDir) throws Exception {
        WorkspaceMemory workspaceMemory = new WorkspaceMemory(tempDir);
        GoalManager manager = new GoalManager(workspaceMemory);

        manager.setGoal("ship recovery loop");

        assertTrue(manager.formatStatus().contains("ship recovery loop"));
        assertTrue(Files.readString(workspaceMemory.checkpointFile()).contains("ship recovery loop"));

        manager.clearGoal();

        assertEquals("No active goal. Use /goal set <objective> to define one.", manager.formatStatus());
        assertTrue(Files.readString(workspaceMemory.checkpointFile()).contains("## Current Goal"));
    }

    @Test
    void judgeParsesCompleteContinueAndBlocked(@TempDir Path tempDir) {
        WorkspaceMemory workspaceMemory = new WorkspaceMemory(tempDir);
        GoalManager manager = new GoalManager(workspaceMemory);
        manager.setGoal("finish");

        GoalManager.GoalJudgeResult complete = manager.judgeCompletion(
                new StubClient("status: complete\nreason: done"), "request", "answer");
        assertEquals("complete", complete.status());
        assertTrue(manager.formatStatus().contains("completed"));

        manager.setGoal("continue work");
        GoalManager.GoalJudgeResult cont = manager.judgeCompletion(
                new StubClient("status: continue\nreason: run tests"), "request", "answer");
        assertEquals("continue", cont.status());

        manager.setGoal("blocked work");
        GoalManager.GoalJudgeResult blocked = manager.judgeCompletion(
                new StubClient("status: blocked\nreason: missing access"), "request", "answer");
        assertEquals("blocked", blocked.status());
    }

    @Test
    void judgeFailureFallsBackToContinue(@TempDir Path tempDir) {
        GoalManager manager = new GoalManager(new WorkspaceMemory(tempDir));
        manager.setGoal("finish");

        GoalManager.GoalJudgeResult result = manager.judgeCompletion(
                new FailingClient(), "request", "answer");

        assertEquals("continue", result.status());
        assertTrue(result.reason().contains("Goal judge unavailable"));
    }

    private static final class StubClient implements LlmClient {
        private final String response;

        private StubClient(String response) {
            this.response = response;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) {
            return new ChatResponse("assistant", response, null, 10, 5);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            return chat(messages, tools);
        }

        @Override
        public String getModelName() {
            return "stub";
        }

        @Override
        public String getProviderName() {
            return "stub";
        }
    }

    private static final class FailingClient implements LlmClient {
        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            throw new IOException("network down");
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            throw new IOException("network down");
        }

        @Override
        public String getModelName() {
            return "stub";
        }

        @Override
        public String getProviderName() {
            return "stub";
        }
    }
}
