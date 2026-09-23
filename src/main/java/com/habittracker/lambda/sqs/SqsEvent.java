package com.habittracker.lambda.sqs;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.Data;
import lombok.experimental.Accessors;

// A batch from an SQS event source mapping. Hand-rolled, not from aws-lambda-java-events.
// "Records" is capitalised but its inner fields are not - AWS inconsistency.
@Data
@Accessors(chain = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class SqsEvent {
    @JsonProperty("Records")
    private List<SqsMessage> records;
}
