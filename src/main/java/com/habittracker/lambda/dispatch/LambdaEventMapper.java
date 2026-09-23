package com.habittracker.lambda.dispatch;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

// Jackson for AWS event payloads, deliberately a distinct type from Spring's own ObjectMapper.
public class LambdaEventMapper {
    private static final byte[] EMPTY_JSON_OBJECT = "{}".getBytes(StandardCharsets.UTF_8);

    // FAIL_ON_UNKNOWN_PROPERTIES off: application.yml turns it on and every AWS payload trips it.
    // AUTO_CLOSE_TARGET off: writeValue would otherwise close the Lambda runtime's output stream.
    private final ObjectMapper objectMapper =
            JsonMapper.builder()
                    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET)
                    .build();

    public JsonNode readTree(byte[] body) throws IOException {
        return objectMapper.readTree(body);
    }

    public <T> T read(JsonNode root, Class<T> type) throws IOException {
        return objectMapper.treeToValue(root, type);
    }

    public void write(OutputStream output, Object response) throws IOException {
        if (response == null) {
            output.write(EMPTY_JSON_OBJECT);
            return;
        }
        objectMapper.writeValue(output, response);
    }
}
