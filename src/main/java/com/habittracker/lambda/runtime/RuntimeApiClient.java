package com.habittracker.lambda.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class RuntimeApiClient {
    static final String REQUEST_ID_HEADER = "Lambda-Runtime-Aws-Request-Id";
    static final String DEADLINE_HEADER = "Lambda-Runtime-Deadline-Ms";
    static final String FUNCTION_ARN_HEADER = "Lambda-Runtime-Invoked-Function-Arn";
    static final String TRACE_ID_HEADER = "Lambda-Runtime-Trace-Id";
    static final String ERROR_TYPE_HEADER = "Lambda-Runtime-Function-Error-Type";
    static final String UNHANDLED_ERROR_TYPE = "Unhandled";

    private static final String BASE_PATH = "/2018-06-01/runtime";
    private static final int FIRST_ERROR_STATUS = 300;

    private final HttpClient httpClient =
            HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String baseUri;

    RuntimeApiClient(String runtimeApi) {
        this.baseUri = "http://" + runtimeApi + BASE_PATH;
    }

    Invocation next() {
        HttpResponse<byte[]> response =
                send(HttpRequest.newBuilder(uri("/invocation/next")).GET().build());
        HttpHeaders headers = response.headers();
        String requestId =
                headers.firstValue(REQUEST_ID_HEADER)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Runtime API sent an invocation without "
                                                        + REQUEST_ID_HEADER));
        return new Invocation(
                requestId,
                headers.firstValueAsLong(DEADLINE_HEADER).orElse(Long.MAX_VALUE),
                headers.firstValue(FUNCTION_ARN_HEADER).orElse(null),
                headers.firstValue(TRACE_ID_HEADER).orElse(null),
                response.body());
    }

    void postResponse(String requestId, byte[] body) {
        post("/invocation/" + requestId + "/response", body, null);
    }

    void reportInvocationError(String requestId, Throwable error) {
        post("/invocation/" + requestId + "/error", errorBody(error), UNHANDLED_ERROR_TYPE);
    }

    void reportInitError(Throwable error) {
        post("/init/error", errorBody(error), UNHANDLED_ERROR_TYPE);
    }

    private void post(String path, byte[] body, String errorType) {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(uri(path))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (errorType != null) {
            request.header(ERROR_TYPE_HEADER, errorType);
        }
        send(request.build());
    }

    private HttpResponse<byte[]> send(HttpRequest request) {
        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted calling the Lambda Runtime API", e);
        }
        if (response.statusCode() >= FIRST_ERROR_STATUS) {
            throw new RuntimeApiException(request.uri().getPath(), response.statusCode());
        }
        return response;
    }

    private URI uri(String path) {
        return URI.create(baseUri + path);
    }

    private byte[] errorBody(Throwable error) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("errorMessage", String.valueOf(error.getMessage()));
        body.put("errorType", error.getClass().getName());
        List<String> stackTrace =
                Arrays.stream(error.getStackTrace()).map(StackTraceElement::toString).toList();
        body.put("stackTrace", stackTrace);
        try {
            return objectMapper.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
