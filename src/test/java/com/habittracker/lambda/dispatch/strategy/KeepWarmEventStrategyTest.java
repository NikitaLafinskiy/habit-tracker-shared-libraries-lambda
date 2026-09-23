package com.habittracker.lambda.dispatch.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.Context;
import com.fasterxml.jackson.databind.JsonNode;
import com.habittracker.lambda.dispatch.EventFixtures;
import com.habittracker.lambda.dispatch.LambdaEvent;
import com.habittracker.lambda.dispatch.LambdaEventMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KeepWarmEventStrategyTest {

    private final LambdaEventMapper mapper = new LambdaEventMapper();
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();

    @Mock private Context context;

    private static LambdaEvent eventFrom(String fixture, Context context) {
        return new LambdaEvent(EventFixtures.bytes(fixture), EventFixtures.tree(fixture), context);
    }

    private KeepWarmEventStrategy strategy() {
        return new KeepWarmEventStrategy(mapper);
    }

    @Test
    @DisplayName("claims an EventBridge scheduled event")
    void claimsAnEventBridgeScheduledEvent() {
        JsonNode root = EventFixtures.tree(EventFixtures.EVENTBRIDGE_SCHEDULED);

        assertThat(strategy().matches(root)).isTrue();
    }

    @Test
    @DisplayName("leaves HTTP, record batches and unknown events alone")
    void leavesHttpRecordBatchesAndUnknownEventsAlone() {
        assertThat(strategy().matches(EventFixtures.tree(EventFixtures.API_GATEWAY_GET))).isFalse();
        assertThat(strategy().matches(EventFixtures.tree(EventFixtures.SQS_BATCH))).isFalse();
        assertThat(strategy().matches(EventFixtures.tree(EventFixtures.SNS_NOTIFICATION)))
                .isFalse();
        assertThat(strategy().matches(EventFixtures.tree(EventFixtures.UNKNOWN_KINESIS))).isFalse();
    }

    @Test
    @DisplayName("does no work and answers with an empty object")
    void doesNoWorkAndAnswersWithAnEmptyObject() throws IOException {
        strategy().handle(eventFrom(EventFixtures.EVENTBRIDGE_SCHEDULED, context), output);

        assertThat(output.toString(StandardCharsets.UTF_8)).isEqualTo("{}");
    }

    @Test
    @DisplayName("does not close the stream it was handed")
    void doesNotCloseTheStreamItWasHanded() throws IOException {
        // The output stream belongs to the Lambda runtime, not this strategy.
        CloseTrackingStream tracked = new CloseTrackingStream();

        strategy().handle(eventFrom(EventFixtures.EVENTBRIDGE_SCHEDULED, context), tracked);

        assertThat(tracked.closed).isFalse();
    }

    private static final class CloseTrackingStream extends ByteArrayOutputStream {
        private boolean closed;

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}
