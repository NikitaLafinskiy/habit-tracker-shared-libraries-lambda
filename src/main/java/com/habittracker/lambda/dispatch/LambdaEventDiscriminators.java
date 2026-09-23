package com.habittracker.lambda.dispatch;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

// Shape probes identifying which AWS service produced a payload.
public final class LambdaEventDiscriminators {
    public static final String SNS = "aws:sns";
    public static final String SQS = "aws:sqs";
    public static final String DYNAMODB = "aws:dynamodb";

    private static final String RECORDS = "Records";
    private static final String SOURCE = "source";
    private static final String DETAIL_TYPE = "detail-type";

    private LambdaEventDiscriminators() {}

    // Only the first record is inspected: a batch always comes from one event source mapping.
    public static boolean firstRecordSourceIs(JsonNode root, String expected) {
        JsonNode records = root.path(RECORDS);
        if (!records.isArray() || records.isEmpty()) {
            return false;
        }
        return expected.equals(recordSource(records.get(0)));
    }

    // Matches API Gateway proxy payload format 1.0 (what iac pins); a 2.0 payload deliberately does
    // not match - the container handler is typed for 1.0.
    public static boolean isHttpProxyRequest(JsonNode root) {
        return root.hasNonNull("requestContext") && root.hasNonNull("httpMethod");
    }

    public static boolean isEventBridgeEvent(JsonNode root, String source, String detailType) {
        return source.equals(root.path(SOURCE).asText())
                && detailType.equals(root.path(DETAIL_TYPE).asText());
    }

    // Field names, record count and routing discriminators only - never values: these payloads hold
    // addresses and tokens and this string lands in CloudWatch.
    public static String describeShape(JsonNode root) {
        List<String> fieldNames = new ArrayList<>();
        root.fieldNames().forEachRemaining(fieldNames::add);

        StringBuilder shape = new StringBuilder("topLevelFields=").append(fieldNames);
        JsonNode records = root.path(RECORDS);
        if (records.isArray()) {
            shape.append(", recordCount=").append(records.size());
            if (!records.isEmpty()) {
                shape.append(", firstRecordSource=").append(recordSource(records.get(0)));
            }
        }
        if (root.hasNonNull(SOURCE)) {
            shape.append(", source=").append(root.path(SOURCE).asText());
        }
        if (root.hasNonNull(DETAIL_TYPE)) {
            shape.append(", detailType=").append(root.path(DETAIL_TYPE).asText());
        }
        return shape.toString();
    }

    // Checks both spellings: SNS emits EventSource, SQS/DynamoDB emit eventSource. Reading only one
    // makes a strategy silently never match.
    private static String recordSource(JsonNode record) {
        JsonNode lowerCase = record.path("eventSource");
        return lowerCase.isTextual() ? lowerCase.asText() : record.path("EventSource").asText();
    }
}
