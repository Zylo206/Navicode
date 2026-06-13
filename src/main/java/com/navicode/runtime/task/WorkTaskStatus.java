package com.navicode.runtime.task;

public enum WorkTaskStatus {
    OPEN("open"),
    IN_PROGRESS("in_progress"),
    BLOCKED("blocked"),
    DONE("done"),
    ABANDONED("abandoned");

    private final String value;

    WorkTaskStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public boolean actionable() {
        return this == OPEN || this == IN_PROGRESS;
    }

    public boolean terminal() {
        return this == DONE || this == ABANDONED;
    }

    public static WorkTaskStatus from(String value) {
        if (value == null || value.isBlank()) {
            return OPEN;
        }
        for (WorkTaskStatus status : values()) {
            if (status.value.equalsIgnoreCase(value) || status.name().equalsIgnoreCase(value)) {
                return status;
            }
        }
        return OPEN;
    }
}
