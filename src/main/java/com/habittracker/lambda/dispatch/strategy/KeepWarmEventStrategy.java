package com.habittracker.lambda.dispatch.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.habittracker.lambda.dispatch.LambdaEvent;
import com.habittracker.lambda.dispatch.LambdaEventDiscriminators;
import com.habittracker.lambda.dispatch.LambdaEventMapper;
import com.habittracker.lambda.dispatch.LambdaEventStrategy;
import java.io.IOException;
import java.io.OutputStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;

// The keep-warm ping: an EventBridge rule (iac/modules/lambda keep_warm) invokes the alias on an
// interval so an environment stays live. No work to do; it just writes an empty body. Matches the
// standard scheduled-event shape because that rule is the only scheduled one on the function.
// Ordered last - the rarest event.
@Slf4j
@Order(200)
@RequiredArgsConstructor
public class KeepWarmEventStrategy implements LambdaEventStrategy {
    private static final String EVENTBRIDGE_SOURCE = "aws.events";
    private static final String SCHEDULED_DETAIL_TYPE = "Scheduled Event";

    private final LambdaEventMapper mapper;

    @Override
    public boolean matches(JsonNode root) {
        return LambdaEventDiscriminators.isEventBridgeEvent(
                root, EVENTBRIDGE_SOURCE, SCHEDULED_DETAIL_TYPE);
    }

    @Override
    public void handle(LambdaEvent event, OutputStream output) throws IOException {
        log.debug("Keep-warm ping received; returning without work");
        mapper.write(output, null);
    }
}
