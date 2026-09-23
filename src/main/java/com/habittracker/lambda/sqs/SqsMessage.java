package com.habittracker.lambda.sqs;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import lombok.Data;
import lombok.experimental.Accessors;

// One message from an SqsEvent batch. body is opaque (parsed by whichever handler claims it), and
// the only field that can hold caller data, which is why nothing in the dispatch path logs it.
@Data
@Accessors(chain = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class SqsMessage {
    private String messageId;
    private String receiptHandle;
    private String body;
    private String eventSource;

    @JsonProperty("eventSourceARN")
    private String eventSourceArn;

    private String awsRegion;
    // Strings because SQS sends every system attribute (ApproximateReceiveCount, ...) as a string.
    private Map<String, String> attributes;

    // Not getQueueName: derived, and a getter would make Jackson emit it if this were serialized.
    public String queueName() {
        if (eventSourceArn == null) {
            return "unknown";
        }
        return eventSourceArn.substring(eventSourceArn.lastIndexOf(':') + 1);
    }
}
