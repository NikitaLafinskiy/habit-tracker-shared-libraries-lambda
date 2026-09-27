package com.habittracker.lambda.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.amazonaws.services.lambda.runtime.Context;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.habittracker.lambda.dispatch.LambdaEvent;
import com.habittracker.lambda.dispatch.LambdaEventDispatcher;
import com.habittracker.lambda.dispatch.LambdaEventMapper;
import com.habittracker.lambda.dispatch.LambdaEventStrategy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LambdaRuntimeLoopTest {
    private static final long DEADLINE_MS = 10_000;
    private static final long NOW_MS = 4_000;
    private static final String EVENT = "{\"source\":\"test\"}";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private FakeRuntimeApi api;
    private RuntimeEnvironment environment;

    @BeforeEach
    void startApi() throws IOException {
        api = new FakeRuntimeApi();
        environment =
                RuntimeEnvironment.from(
                        Map.of(
                                RuntimeEnvironment.RUNTIME_API, api.address(),
                                RuntimeEnvironment.FUNCTION_NAME, "api",
                                RuntimeEnvironment.FUNCTION_VERSION, "7",
                                RuntimeEnvironment.MEMORY_SIZE, "512"));
    }

    @AfterEach
    void stopApi() {
        api.close();
        System.clearProperty(LambdaRuntimeLoop.TRACE_HEADER_PROPERTY);
    }

    private static Map<String, String> headers(String requestId) {
        Map<String, String> headers = new HashMap<>();
        headers.put(RuntimeApiClient.REQUEST_ID_HEADER, requestId);
        headers.put(RuntimeApiClient.DEADLINE_HEADER, String.valueOf(DEADLINE_MS));
        headers.put(
                RuntimeApiClient.FUNCTION_ARN_HEADER, "arn:aws:lambda:eu-central-1:1:function:api");
        return headers;
    }

    private LambdaRuntimeLoop loop(LambdaEventStrategy strategy) {
        return new LambdaRuntimeLoop(
                new RuntimeApiClient(environment.runtimeApi()),
                environment,
                new LambdaEventDispatcher(List.of(strategy), new LambdaEventMapper()),
                () -> NOW_MS,
                new StdoutLambdaLogger(
                        new PrintStream(
                                new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)));
    }

    private static LambdaEventStrategy writing(byte[] response, AtomicReference<Context> seen) {
        return new LambdaEventStrategy() {
            @Override
            public boolean matches(JsonNode root) {
                return true;
            }

            @Override
            public void handle(LambdaEvent event, OutputStream output) throws IOException {
                seen.set(event.context());
                output.write(response);
            }
        };
    }

    private static LambdaEventStrategy throwing(RuntimeException failure) {
        return new LambdaEventStrategy() {
            @Override
            public boolean matches(JsonNode root) {
                return true;
            }

            @Override
            public void handle(LambdaEvent event, OutputStream output) {
                throw failure;
            }
        };
    }

    @Test
    @DisplayName("posts the dispatcher's output as the invocation's response")
    void postsTheResponse() {
        AtomicReference<Context> seen = new AtomicReference<>();
        api.enqueue(headers("req-1"), EVENT);

        loop(writing("{\"ok\":true}".getBytes(StandardCharsets.UTF_8), seen)).handleNext();

        assertThat(api.posted()).hasSize(1);
        FakeRuntimeApi.Posted posted = api.posted().get(0);
        assertThat(posted.path()).isEqualTo("/invocation/req-1/response");
        assertThat(posted.body()).isEqualTo("{\"ok\":true}");
    }

    @Test
    @DisplayName("builds the Context from the Runtime API headers and the environment")
    void buildsTheContext() {
        AtomicReference<Context> seen = new AtomicReference<>();
        api.enqueue(headers("req-2"), EVENT);

        loop(writing(new byte[0], seen)).handleNext();

        Context context = seen.get();
        assertThat(context.getAwsRequestId()).isEqualTo("req-2");
        assertThat(context.getInvokedFunctionArn())
                .isEqualTo("arn:aws:lambda:eu-central-1:1:function:api");
        assertThat(context.getRemainingTimeInMillis()).isEqualTo((int) (DEADLINE_MS - NOW_MS));
        assertThat(context.getFunctionName()).isEqualTo("api");
        assertThat(context.getFunctionVersion()).isEqualTo("7");
        assertThat(context.getMemoryLimitInMB()).isEqualTo(512);
        assertThat(context.getLogger()).isNotNull();
    }

    @Test
    @DisplayName("exposes the invocation's trace id and clears it when the next one has none")
    void propagatesTheTraceHeader() {
        Map<String, String> traced = headers("req-3");
        traced.put(RuntimeApiClient.TRACE_ID_HEADER, "Root=1-abc;Parent=def;Sampled=1");
        api.enqueue(traced, EVENT);
        api.enqueue(headers("req-4"), EVENT);
        LambdaRuntimeLoop loop = loop(writing(new byte[0], new AtomicReference<>()));

        loop.handleNext();
        assertThat(System.getProperty(LambdaRuntimeLoop.TRACE_HEADER_PROPERTY))
                .isEqualTo("Root=1-abc;Parent=def;Sampled=1");

        loop.handleNext();
        assertThat(System.getProperty(LambdaRuntimeLoop.TRACE_HEADER_PROPERTY)).isNull();
    }

    @Test
    @DisplayName("reports a failing handler as an invocation error in Lambda's error shape")
    void reportsHandlerErrors() throws IOException {
        api.enqueue(headers("req-5"), EVENT);

        loop(throwing(new IllegalStateException("boom"))).handleNext();

        FakeRuntimeApi.Posted posted = api.posted().get(0);
        assertThat(posted.path()).isEqualTo("/invocation/req-5/error");
        assertThat(posted.errorType()).isEqualTo(RuntimeApiClient.UNHANDLED_ERROR_TYPE);
        JsonNode error = objectMapper.readTree(posted.body());
        assertThat(error.get("errorType").asText()).isEqualTo("java.lang.IllegalStateException");
        assertThat(error.get("errorMessage").asText()).isEqualTo("boom");
        assertThat(error.get("stackTrace").isArray()).isTrue();
    }

    @Test
    @DisplayName("keeps serving after a failed invocation")
    void keepsServingAfterAnError() {
        api.enqueue(headers("req-6"), EVENT);
        api.enqueue(headers("req-7"), "not json");
        LambdaRuntimeLoop loop = loop(throwing(new IllegalStateException("first")));

        loop.handleNext();
        loop.handleNext();

        assertThat(api.posted())
                .extracting(FakeRuntimeApi.Posted::path)
                .containsExactly("/invocation/req-6/error", "/invocation/req-7/error");
    }

    @Test
    @DisplayName("carries a response close to Lambda's 6 MB limit intact")
    void carriesALargeResponse() {
        byte[] large = new byte[6 * 1024 * 1024 - 1024];
        Arrays.fill(large, (byte) 'x');
        api.enqueue(headers("req-8"), EVENT);

        loop(writing(large, new AtomicReference<>())).handleNext();

        assertThat(api.posted().get(0).body()).hasSize(large.length);
    }

    @Test
    @DisplayName("reports an initialization failure to init/error and rethrows it")
    void reportsInitErrors() throws IOException {
        RuntimeApiClient client = new RuntimeApiClient(environment.runtimeApi());

        assertThatThrownBy(
                        () ->
                                LambdaRuntimeLoop.start(
                                        client,
                                        environment,
                                        () -> {
                                            throw new IllegalStateException("no context");
                                        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("no context");

        FakeRuntimeApi.Posted posted = api.posted().get(0);
        assertThat(posted.path()).isEqualTo("/init/error");
        assertThat(objectMapper.readTree(posted.body()).get("errorMessage").asText())
                .isEqualTo("no context");
    }

    @Test
    @DisplayName("fails loudly when the Runtime API itself answers with an error status")
    void failsOnRuntimeApiErrors() {
        api.enqueueFailure(500);

        assertThatThrownBy(() -> loop(writing(new byte[0], new AtomicReference<>())).handleNext())
                .isInstanceOf(RuntimeApiException.class)
                .hasMessageContaining("500");
    }

    @Test
    @DisplayName("a rejected response post loses the outcome but keeps the loop serving")
    void keepsServingAfterRejectedResponsePost() {
        api.failNextPost(400);
        api.enqueue(headers("req-9"), EVENT);
        api.enqueue(headers("req-10"), EVENT);
        LambdaRuntimeLoop loop = loop(writing(new byte[0], new AtomicReference<>()));

        loop.handleNext();
        loop.handleNext();

        assertThat(api.posted())
                .extracting(FakeRuntimeApi.Posted::path)
                .containsExactly("/invocation/req-9/response", "/invocation/req-10/response");
    }

    @Test
    @DisplayName("a rejected error post loses the outcome but keeps the loop serving")
    void keepsServingAfterRejectedErrorPost() {
        api.failNextPost(403);
        api.enqueue(headers("req-11"), EVENT);
        api.enqueue(headers("req-12"), EVENT);
        LambdaRuntimeLoop loop = loop(throwing(new IllegalStateException("boom")));

        loop.handleNext();
        loop.handleNext();

        assertThat(api.posted())
                .extracting(FakeRuntimeApi.Posted::path)
                .containsExactly("/invocation/req-11/error", "/invocation/req-12/error");
    }

    @Test
    @DisplayName("a container error on an outcome post exits the process")
    void exitsOnContainerErrorFromOutcomePost() {
        api.failNextPost(500);
        api.enqueue(headers("req-13"), EVENT);

        assertThatThrownBy(() -> loop(writing(new byte[0], new AtomicReference<>())).handleNext())
                .isInstanceOfSatisfying(
                        RuntimeApiException.class, e -> assertThat(e.isContainerError()).isTrue());
    }

    @Test
    @DisplayName("refuses to start without a Runtime API address")
    void requiresTheRuntimeApi() {
        assertThatThrownBy(() -> RuntimeEnvironment.from(Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(RuntimeEnvironment.RUNTIME_API);
    }
}
