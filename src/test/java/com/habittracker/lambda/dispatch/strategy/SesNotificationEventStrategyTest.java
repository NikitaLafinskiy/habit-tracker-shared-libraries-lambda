package com.habittracker.lambda.dispatch.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.amazonaws.services.lambda.runtime.Context;
import com.fasterxml.jackson.databind.JsonNode;
import com.habittracker.lambda.dispatch.EventFixtures;
import com.habittracker.lambda.dispatch.FailureContract;
import com.habittracker.lambda.dispatch.LambdaEvent;
import com.habittracker.lambda.dispatch.LambdaEventMapper;
import com.habittracker.lambda.ses.SesNotificationHandler;
import com.habittracker.lambda.ses.SnsLambdaEvent;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SesNotificationEventStrategyTest {

    private final LambdaEventMapper mapper = new LambdaEventMapper();
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();

    @Mock private SesNotificationHandler sesNotificationHandler;

    @Mock private Context context;

    private static LambdaEvent eventFrom(String fixture, Context context) {
        return new LambdaEvent(EventFixtures.bytes(fixture), EventFixtures.tree(fixture), context);
    }

    private SesNotificationEventStrategy strategy() {
        return new SesNotificationEventStrategy(mapper, sesNotificationHandler);
    }

    @Test
    @DisplayName("claims an SNS delivery")
    void claimsAnSnsDelivery() {
        JsonNode root = EventFixtures.tree(EventFixtures.SNS_NOTIFICATION);

        assertThat(strategy().matches(root)).isTrue();
    }

    @Test
    @DisplayName("leaves other record batches and HTTP alone")
    void leavesOtherRecordBatchesAndHttpAlone() {
        assertThat(strategy().matches(EventFixtures.tree(EventFixtures.SQS_BATCH))).isFalse();
        assertThat(strategy().matches(EventFixtures.tree(EventFixtures.API_GATEWAY_GET))).isFalse();
        assertThat(strategy().matches(EventFixtures.tree(EventFixtures.UNKNOWN_KINESIS))).isFalse();
    }

    @Test
    @DisplayName("parses the notification and hands it to the SES service")
    void parsesTheNotificationAndHandsItToTheSesService() throws IOException {
        strategy().handle(eventFrom(EventFixtures.SNS_NOTIFICATION, context), output);

        ArgumentCaptor<SnsLambdaEvent> handled = ArgumentCaptor.forClass(SnsLambdaEvent.class);
        verify(sesNotificationHandler).handle(handled.capture());
        assertThat(handled.getValue().getRecords()).hasSize(1);
        assertThat(handled.getValue().getRecords().get(0).getSns().getMessage())
                .contains("\"bounceType\":\"Permanent\"");
    }

    @Test
    @DisplayName("answers SNS with an empty object")
    void answersSnsWithAnEmptyObject() throws IOException {
        strategy().handle(eventFrom(EventFixtures.SNS_NOTIFICATION, context), output);

        assertThat(output.toString(StandardCharsets.UTF_8)).isEqualTo("{}");
    }

    @Test
    @DisplayName("swallows a handling failure and still answers, per its SWALLOW contract")
    void swallowsAHandlingFailureAndStillAnswers() {
        doThrow(new IllegalStateException("repository down"))
                .when(sesNotificationHandler)
                .handle(any());

        // SNS delivers asynchronously; failing here would only buy a redelivery of the same work.
        assertThatCode(
                        () ->
                                strategy()
                                        .handle(
                                                eventFrom(EventFixtures.SNS_NOTIFICATION, context),
                                                output))
                .doesNotThrowAnyException();
        assertThat(output.toString(StandardCharsets.UTF_8)).isEqualTo("{}");
    }

    @Test
    @DisplayName("declares that it swallows failures")
    void declaresThatItSwallowsFailures() {
        assertThat(strategy().failureContract()).isEqualTo(FailureContract.SWALLOW);
    }

    @Test
    @DisplayName("does not close the stream it was handed")
    void doesNotCloseTheStreamItWasHanded() throws IOException {
        // Jackson closes its write target by default; that stream belongs to the Lambda runtime.
        CloseTrackingStream tracked = new CloseTrackingStream();

        strategy().handle(eventFrom(EventFixtures.SNS_NOTIFICATION, context), tracked);

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
