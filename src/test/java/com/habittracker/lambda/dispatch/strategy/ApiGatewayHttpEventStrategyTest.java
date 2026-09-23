package com.habittracker.lambda.dispatch.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.verify;

import com.amazonaws.services.lambda.runtime.Context;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.habittracker.lambda.dispatch.HttpRequestProxy;
import com.habittracker.lambda.dispatch.LambdaEvent;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

// Regression guard for the HTTP path: this strategy replaced a direct proxyStream call, so what
// matters is that the container still receives byte-for-byte what API Gateway sent.
@ExtendWith(MockitoExtension.class)
class ApiGatewayHttpEventStrategyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock private HttpRequestProxy httpRequestProxy;

    @Mock private Context context;

    private static LambdaEvent eventFrom(String json, Context context) {
        try {
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            return new LambdaEvent(body, MAPPER.readTree(body), context);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static JsonNode tree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("claims an API Gateway proxy payload")
    void claimsAnApiGatewayProxyPayload() {
        JsonNode root =
                tree("{\"httpMethod\":\"GET\",\"requestContext\":{\"stage\":\"$default\"}}");

        assertThat(new ApiGatewayHttpEventStrategy(httpRequestProxy).matches(root)).isTrue();
    }

    @Test
    @DisplayName("leaves a record batch to another strategy")
    void leavesARecordBatchToAnotherStrategy() {
        JsonNode root = tree("{\"Records\":[{\"eventSource\":\"aws:sqs\"}]}");

        assertThat(new ApiGatewayHttpEventStrategy(httpRequestProxy).matches(root)).isFalse();
    }

    @Test
    @DisplayName("forwards the payload to the container byte for byte")
    void forwardsThePayloadToTheContainerByteForByte() throws IOException {
        String json = "{\"httpMethod\":\"POST\",\"requestContext\":{},\"body\":\"{\\\"a\\\":1}\"}";
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        new ApiGatewayHttpEventStrategy(httpRequestProxy).handle(eventFrom(json, context), output);

        ArgumentCaptor<InputStream> forwarded = ArgumentCaptor.forClass(InputStream.class);
        verify(httpRequestProxy).proxy(forwarded.capture(), same(output), same(context));
        assertThat(forwarded.getValue().readAllBytes()).isEqualTo(body);
    }

    @Test
    @DisplayName("writes nothing itself - the container owns the response")
    void writesNothingItselfTheContainerOwnsTheResponse() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        new ApiGatewayHttpEventStrategy(httpRequestProxy)
                .handle(
                        eventFrom("{\"httpMethod\":\"GET\",\"requestContext\":{}}", context),
                        output);

        verify(httpRequestProxy).proxy(any(InputStream.class), any(OutputStream.class), any());
        assertThat(output.size()).isZero();
    }
}
