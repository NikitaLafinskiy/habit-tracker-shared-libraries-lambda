package com.habittracker.lambda.ses;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.Data;
import lombok.experimental.Accessors;

// Shape of an event delivered by a direct SNS "lambda" subscription - distinct from API Gateway's
// AwsProxyRequest, which is how the dispatcher tells the two apart.
@Data
@Accessors(chain = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class SnsLambdaEvent {
    @JsonProperty("Records")
    private List<SnsRecord> records;
}
