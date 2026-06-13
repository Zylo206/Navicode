package com.navicode.rag;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CodeIndexTest {
    @TempDir
    Path tempDir;

    @AfterEach
    void clearRagDirProperty() {
        System.clearProperty("navicode.rag.dir");
    }

    @Test
    void testIndexNonExistentPath() {
        CodeIndex indexer = new CodeIndex();
        CodeIndex.IndexResult result = indexer.index("/non/existent/path");
        assertEquals(0, result.chunkCount());
        assertTrue(result.message().contains("路径不存在"));
    }

    @Test
    void testIndexCurrentProject() {
        System.setProperty("navicode.rag.dir", tempDir.resolve("rag-index").toString());
        CodeIndex indexer = new CodeIndex(new StubEmbeddingClient());
        // 索引测试资源目录
        CodeIndex.IndexResult result = indexer.index(Path.of("src/test/resources/rag").toAbsolutePath().toString());
        assertTrue(result.chunkCount() > 0, "应该至少索引一个代码块");
        assertTrue(result.message().contains("索引完成"));
    }

    @Test
    void reportsProgressThroughListener() {
        List<String> messages = new ArrayList<>();
        System.setProperty("navicode.rag.dir", tempDir.resolve("rag-progress").toString());
        CodeIndex indexer = new CodeIndex(new StubEmbeddingClient(), messages::add);

        CodeIndex.IndexResult result = indexer.index(Path.of("src/test/resources/rag").toAbsolutePath().toString());

        assertTrue(result.chunkCount() > 0, "应该至少索引一个代码块");
        assertTrue(messages.stream().anyMatch(message -> message.startsWith("🔍 开始索引")));
        assertTrue(messages.stream().anyMatch(message -> message.startsWith("📁 发现")));
        assertTrue(messages.stream().anyMatch(message -> message.startsWith("✅ 索引完成")));
    }

    private static final class StubEmbeddingClient extends EmbeddingClient {
        private StubEmbeddingClient() {
            super("stub", "stub", "http://localhost", "");
        }

        @Override
        public float[] embed(String text) throws IOException {
            return new float[]{1.0f, 0.0f};
        }
    }
}
