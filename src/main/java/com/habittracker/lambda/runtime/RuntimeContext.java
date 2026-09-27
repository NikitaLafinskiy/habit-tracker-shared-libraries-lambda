package com.habittracker.lambda.runtime;

import com.amazonaws.services.lambda.runtime.ClientContext;
import com.amazonaws.services.lambda.runtime.CognitoIdentity;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import java.util.function.LongSupplier;

final class RuntimeContext implements Context {
    private final Invocation invocation;
    private final RuntimeEnvironment environment;
    private final LongSupplier clock;
    private final LambdaLogger logger;

    RuntimeContext(
            Invocation invocation,
            RuntimeEnvironment environment,
            LongSupplier clock,
            LambdaLogger logger) {
        this.invocation = invocation;
        this.environment = environment;
        this.clock = clock;
        this.logger = logger;
    }

    @Override
    public String getAwsRequestId() {
        return invocation.requestId();
    }

    @Override
    public String getLogGroupName() {
        return environment.logGroupName();
    }

    @Override
    public String getLogStreamName() {
        return environment.logStreamName();
    }

    @Override
    public String getFunctionName() {
        return environment.functionName();
    }

    @Override
    public String getFunctionVersion() {
        return environment.functionVersion();
    }

    @Override
    public String getInvokedFunctionArn() {
        return invocation.invokedFunctionArn();
    }

    @Override
    public CognitoIdentity getIdentity() {
        return null;
    }

    @Override
    public ClientContext getClientContext() {
        return null;
    }

    @Override
    public int getRemainingTimeInMillis() {
        long remaining = invocation.deadlineMs() - clock.getAsLong();
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, remaining));
    }

    @Override
    public int getMemoryLimitInMB() {
        return environment.memoryLimitInMb();
    }

    @Override
    public LambdaLogger getLogger() {
        return logger;
    }
}
