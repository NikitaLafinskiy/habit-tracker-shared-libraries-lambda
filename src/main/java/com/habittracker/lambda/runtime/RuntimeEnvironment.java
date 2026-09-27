package com.habittracker.lambda.runtime;

import java.util.Map;

public record RuntimeEnvironment(
        String runtimeApi,
        String functionName,
        String functionVersion,
        int memoryLimitInMb,
        String logGroupName,
        String logStreamName) {

    static final String RUNTIME_API = "AWS_LAMBDA_RUNTIME_API";
    static final String FUNCTION_NAME = "AWS_LAMBDA_FUNCTION_NAME";
    static final String FUNCTION_VERSION = "AWS_LAMBDA_FUNCTION_VERSION";
    static final String MEMORY_SIZE = "AWS_LAMBDA_FUNCTION_MEMORY_SIZE";
    static final String LOG_GROUP_NAME = "AWS_LAMBDA_LOG_GROUP_NAME";
    static final String LOG_STREAM_NAME = "AWS_LAMBDA_LOG_STREAM_NAME";

    public static RuntimeEnvironment from(Map<String, String> variables) {
        String runtimeApi = variables.get(RUNTIME_API);
        if (runtimeApi == null || runtimeApi.isBlank()) {
            throw new IllegalStateException(RUNTIME_API + " is not set");
        }
        return new RuntimeEnvironment(
                runtimeApi,
                variables.get(FUNCTION_NAME),
                variables.get(FUNCTION_VERSION),
                parseMemory(variables.get(MEMORY_SIZE)),
                variables.get(LOG_GROUP_NAME),
                variables.get(LOG_STREAM_NAME));
    }

    private static int parseMemory(String memorySize) {
        if (memorySize == null || memorySize.isBlank()) {
            return 0;
        }
        return Integer.parseInt(memorySize.trim());
    }
}
