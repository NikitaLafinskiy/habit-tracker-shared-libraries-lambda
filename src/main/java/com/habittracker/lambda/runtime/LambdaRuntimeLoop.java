package com.habittracker.lambda.runtime;

import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.habittracker.lambda.dispatch.LambdaEventDispatcher;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public final class LambdaRuntimeLoop {
    static final String TRACE_HEADER_PROPERTY = "com.amazonaws.xray.traceHeader";

    private final RuntimeApiClient client;
    private final RuntimeEnvironment environment;
    private final LambdaEventDispatcher dispatcher;
    private final LongSupplier clock;
    private final LambdaLogger logger;

    LambdaRuntimeLoop(
            RuntimeApiClient client,
            RuntimeEnvironment environment,
            LambdaEventDispatcher dispatcher,
            LongSupplier clock,
            LambdaLogger logger) {
        this.client = client;
        this.environment = environment;
        this.dispatcher = dispatcher;
        this.clock = clock;
        this.logger = logger;
    }

    public static void run(Supplier<LambdaEventDispatcher> initializer) {
        RuntimeEnvironment environment = RuntimeEnvironment.from(System.getenv());
        start(new RuntimeApiClient(environment.runtimeApi()), environment, initializer).serve();
    }

    static LambdaRuntimeLoop start(
            RuntimeApiClient client,
            RuntimeEnvironment environment,
            Supplier<LambdaEventDispatcher> initializer) {
        LambdaEventDispatcher dispatcher;
        try {
            dispatcher = initializer.get();
        } catch (RuntimeException e) {
            client.reportInitError(e);
            throw e;
        }
        return new LambdaRuntimeLoop(
                client,
                environment,
                dispatcher,
                System::currentTimeMillis,
                new StdoutLambdaLogger(System.out));
    }

    void serve() {
        while (true) {
            handleNext();
        }
    }

    void handleNext() {
        Invocation invocation = client.next();
        propagateTraceHeader(invocation.traceId());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            dispatcher.dispatch(
                    invocation.body(),
                    output,
                    new RuntimeContext(invocation, environment, clock, logger));
        } catch (IOException | RuntimeException e) {
            postOutcome(() -> client.reportInvocationError(invocation.requestId(), e));
            return;
        }
        postOutcome(() -> client.postResponse(invocation.requestId(), output.toByteArray()));
    }

    private void postOutcome(Runnable post) {
        try {
            post.run();
        } catch (RuntimeApiException e) {
            if (e.isContainerError()) {
                throw e;
            }
            logger.log("Dropped Runtime API post after HTTP " + e.statusCode());
        }
    }

    private static void propagateTraceHeader(String traceId) {
        if (traceId == null) {
            System.clearProperty(TRACE_HEADER_PROPERTY);
        } else {
            System.setProperty(TRACE_HEADER_PROPERTY, traceId);
        }
    }
}
