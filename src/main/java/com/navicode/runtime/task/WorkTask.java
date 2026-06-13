package com.navicode.runtime.task;

import java.time.Instant;

public record WorkTask(
        String id,
        String parentTaskId,
        WorkTaskStatus status,
        String summary,
        Instant createdAt,
        Instant updatedAt,
        Instant endedAt
) {
    public boolean actionable() {
        return status != null && status.actionable();
    }

    public boolean terminal() {
        return status != null && status.terminal();
    }
}
