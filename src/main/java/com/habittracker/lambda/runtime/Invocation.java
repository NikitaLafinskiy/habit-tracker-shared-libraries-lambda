package com.habittracker.lambda.runtime;

final class Invocation {
    private final String requestId;
    private final long deadlineMs;
    private final String invokedFunctionArn;
    private final String traceId;
    private final byte[] body;

    Invocation(
            String requestId,
            long deadlineMs,
            String invokedFunctionArn,
            String traceId,
            byte[] body) {
        this.requestId = requestId;
        this.deadlineMs = deadlineMs;
        this.invokedFunctionArn = invokedFunctionArn;
        this.traceId = traceId;
        this.body = body;
    }

    String requestId() {
        return requestId;
    }

    long deadlineMs() {
        return deadlineMs;
    }

    String invokedFunctionArn() {
        return invokedFunctionArn;
    }

    String traceId() {
        return traceId;
    }

    byte[] body() {
        return body;
    }
}
