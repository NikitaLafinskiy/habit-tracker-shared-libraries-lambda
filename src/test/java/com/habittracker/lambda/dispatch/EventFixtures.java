package com.habittracker.lambda.dispatch;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

// The captured AWS payloads under src/test/resources/events. Each carries a sentinel address or
// token string so tests can assert diagnostics never echo payload contents into the logs.
public final class EventFixtures {
    public static final String API_GATEWAY_GET = "api-gateway-v1-get.json";
    public static final String API_GATEWAY_POST = "api-gateway-v1-post.json";
    public static final String SQS_BATCH = "sqs-batch.json";
    public static final String DYNAMODB_STREAM = "dynamodb-stream-insert.json";
    public static final String EVENTBRIDGE_SCHEDULED = "eventbridge-scheduled.json";
    public static final String SNS_NOTIFICATION = "sns-notification.json";
    public static final String UNKNOWN_KINESIS = "unknown-kinesis.json";
    public static final String NOT_JSON = "not-json.txt";

    public static final String SENTINEL_EMAIL = "sentinel-user@example.com";

    private static final LambdaEventMapper MAPPER = new LambdaEventMapper();

    private EventFixtures() {}

    public static byte[] bytes(String fixture) {
        try (InputStream stream = EventFixtures.class.getResourceAsStream("/events/" + fixture)) {
            if (stream == null) {
                throw new IllegalArgumentException("No such event fixture: " + fixture);
            }
            return stream.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static JsonNode tree(String fixture) {
        try {
            return MAPPER.readTree(bytes(fixture));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
