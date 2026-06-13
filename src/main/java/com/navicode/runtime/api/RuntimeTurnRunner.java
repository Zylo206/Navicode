package com.navicode.runtime.api;

@FunctionalInterface
public interface RuntimeTurnRunner {
    String run(RuntimeTurnContext context) throws Exception;
}
