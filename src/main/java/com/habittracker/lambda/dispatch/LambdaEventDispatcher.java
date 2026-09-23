package com.habittracker.lambda.dispatch;

import com.amazonaws.services.lambda.runtime.Context;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

// Routes a payload to the first LambdaEventStrategy that claims it. Spring injects every strategy
// bean, ordered by @Order; matchers must be mutually exclusive.
@Slf4j
public class LambdaEventDispatcher {
    private final List<LambdaEventStrategy> strategies;
    private final LambdaEventMapper mapper;

    public LambdaEventDispatcher(List<LambdaEventStrategy> strategies, LambdaEventMapper mapper) {
        this.strategies = List.copyOf(strategies);
        this.mapper = mapper;
    }

    public void dispatch(byte[] body, OutputStream output, Context context) throws IOException {
        JsonNode root = parse(body);
        for (LambdaEventStrategy strategy : strategies) {
            if (strategy.matches(root)) {
                handle(strategy, new LambdaEvent(body, root, context), output);
                return;
            }
        }
        throw new UnsupportedLambdaEventException(
                "No strategy matched event: " + LambdaEventDiscriminators.describeShape(root));
    }

    private JsonNode parse(byte[] body) {
        try {
            return mapper.readTree(body);
        } catch (IOException e) {
            throw new UnsupportedLambdaEventException("Event payload is not JSON", e);
        }
    }

    private void handle(LambdaEventStrategy strategy, LambdaEvent event, OutputStream output)
            throws IOException {
        try {
            strategy.handle(event, output);
        } catch (IOException | RuntimeException e) {
            // Log the contract so an on-call reader knows whether AWS will retry.
            log.error(
                    "Strategy {} failed, failureContract={}",
                    strategy.getClass().getSimpleName(),
                    strategy.failureContract(),
                    e);
            throw e;
        }
    }
}
