package com.habittracker.lambda.dispatch;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.OutputStream;

// One event source's handling. Add a source by adding one @Component implementing this; the
// dispatcher discovers every implementation.
public interface LambdaEventStrategy {

    // Called on strategies in turn until one matches, so it must be cheap and side-effect-free.
    boolean matches(JsonNode root);

    // Must not close output - the Lambda runtime owns that stream.
    void handle(LambdaEvent event, OutputStream output) throws IOException;

    default FailureContract failureContract() {
        return FailureContract.PROPAGATE;
    }
}
