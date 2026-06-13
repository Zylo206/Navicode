package com.navicode.agent;

import com.navicode.llm.LlmClient;
import com.navicode.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentGoalJudgeTest {

    @Test
    void appendsContinueJudgeMessageToNonStreamingAnswer(@TempDir Path tempDir) {
        RecordingClient client = new RecordingClient(List.of(
                new LlmClient.ChatResponse("assistant", "implemented", null, 20, 5),
                new LlmClient.ChatResponse("assistant", "status: continue\nreason: run quick tests", null, 5, 2)
        ));
        Agent agent = agentWithProject(client, tempDir);
        agent.getMemoryManager().getGoalManager().setGoal("finish recovery loop");

        String answer = agent.run("do work");

        assertTrue(answer.contains("implemented"));
        assertTrue(answer.contains("Goal judge: continue"));
        assertTrue(answer.contains("run quick tests"));
    }

    @Test
    void appendsCompleteJudgeMessage(@TempDir Path tempDir) {
        RecordingClient client = new RecordingClient(List.of(
                new LlmClient.ChatResponse("assistant", "done", null, 20, 5),
                new LlmClient.ChatResponse("assistant", "status: complete\nreason: objective satisfied", null, 5, 2)
        ));
        Agent agent = agentWithProject(client, tempDir);
        agent.getMemoryManager().getGoalManager().setGoal("finish recovery loop");

        String answer = agent.run("do work");

        assertTrue(answer.contains("Goal judge: complete"));
    }

    @Test
    void appendsBlockedJudgeMessage(@TempDir Path tempDir) {
        RecordingClient client = new RecordingClient(List.of(
                new LlmClient.ChatResponse("assistant", "cannot continue", null, 20, 5),
                new LlmClient.ChatResponse("assistant", "status: blocked\nreason: missing credentials", null, 5, 2)
        ));
        Agent agent = agentWithProject(client, tempDir);
        agent.getMemoryManager().getGoalManager().setGoal("finish recovery loop");

        String answer = agent.run("do work");

        assertTrue(answer.contains("Goal judge: blocked"));
        assertTrue(answer.contains("missing credentials"));
    }

    @Test
    void judgeFailureDoesNotDropOriginalAnswer(@TempDir Path tempDir) {
        RecordingClient client = new RecordingClient(List.of(
                new LlmClient.ChatResponse("assistant", "original answer", null, 20, 5)
        ));
        client.failAfterResponses = true;
        Agent agent = agentWithProject(client, tempDir);
        agent.getMemoryManager().getGoalManager().setGoal("finish recovery loop");

        String answer = agent.run("do work");

        assertTrue(answer.contains("original answer"));
        assertTrue(answer.contains("Goal judge: continue"));
        assertTrue(answer.contains("Goal judge unavailable"));
    }

    private static Agent agentWithProject(LlmClient client, Path projectPath) {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(projectPath.toString());
        return new Agent(client, registry);
    }

    private static final class RecordingClient implements LlmClient {
        private final Queue<ChatResponse> responses;
        private boolean failAfterResponses;

        private RecordingClient(List<ChatResponse> responses) {
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            ChatResponse response = responses.poll();
            if (response == null) {
                if (failAfterResponses) {
                    throw new IOException("judge failed");
                }
                throw new IOException("missing stub response");
            }
            return response;
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
