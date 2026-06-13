package com.navicode.runtime.api;

@FunctionalInterface
public interface RuntimeEventSink {
    void append(String eventType, String dataJson);
}
