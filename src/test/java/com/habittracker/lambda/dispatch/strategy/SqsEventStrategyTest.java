package com.habittracker.lambda.dispatch.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.amazonaws.services.lambda.runtime.Context;
import com.habittracker.lambda.dispatch.EventFixtures;
import com.habittracker.lambda.dispatch.FailureContract;
import com.habittracker.lambda.dispatch.LambdaEvent;
import com.habittracker.lambda.dispatch.LambdaEventMapper;
import com.habittracker.lambda.sqs.SqsMessage;
import com.habittracker.lambda.sqs.SqsMessageHandler;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

// The two messages in sqs-batch.json are what make the partial-batch assertions meaningful - one
// can fail without touching the other.
@ExtendWith(MockitoExtension.class)
class SqsEventStrategyTest {

    private static final String FIRST_MESSAGE_ID = "059f36b4-87a3-44ab-83d2-661975830a7d";
    private static final String SECOND_MESSAGE_ID = "2e1424d4-f796-459a-8184-9c92662be6da";

    private final LambdaEventMapper mapper = new LambdaEventMapper();
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();

    @Mock private Context context;

    private static LambdaEvent eventFrom(String fixture, Context context) {
        return new LambdaEvent(EventFixtures.bytes(fixture), EventFixtures.tree(fixture), context);
    }

    private SqsEventStrategy strategy(SqsMessageHandler... handlers) {
        return new SqsEventStrategy(mapper, List.of(handlers));
    }

    private String handleBatch(SqsMessageHandler... handlers) throws IOException {
        strategy(handlers).handle(eventFrom(EventFixtures.SQS_BATCH, context), output);
        return output.toString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("claims an SQS batch")
    void claimsAnSqsBatch() {
        assertThat(strategy().matches(EventFixtures.tree(EventFixtures.SQS_BATCH))).isTrue();
    }

    @Test
    @DisplayName("leaves other record batches and HTTP alone")
    void leavesOtherRecordBatchesAndHttpAlone() {
        SqsEventStrategy strategy = strategy();

        assertThat(strategy.matches(EventFixtures.tree(EventFixtures.SNS_NOTIFICATION))).isFalse();
        assertThat(strategy.matches(EventFixtures.tree(EventFixtures.DYNAMODB_STREAM))).isFalse();
        assertThat(strategy.matches(EventFixtures.tree(EventFixtures.API_GATEWAY_GET))).isFalse();
        assertThat(strategy.matches(EventFixtures.tree(EventFixtures.UNKNOWN_KINESIS))).isFalse();
    }

    @Test
    @DisplayName("hands every message in the batch to the handler that claims it")
    void handsEveryMessageInTheBatchToTheHandlerThatClaimsIt() throws IOException {
        RecordingHandler handler = new RecordingHandler(message -> true);

        handleBatch(handler);

        assertThat(handler.handled)
                .extracting(SqsMessage::getMessageId)
                .containsExactly(FIRST_MESSAGE_ID, SECOND_MESSAGE_ID);
        assertThat(handler.handled.get(0).getBody()).contains("\"metricBlueprintId\":\"bp-1\"");
        assertThat(handler.handled.get(0).queueName()).isEqualTo("metric-rollups");
    }

    @Test
    @DisplayName("takes the first handler that claims a message, not every one that could")
    void takesTheFirstHandlerThatClaimsAMessageNotEveryOneThatCould() throws IOException {
        RecordingHandler skipped = new RecordingHandler(message -> false);
        RecordingHandler first = new RecordingHandler(message -> true);
        RecordingHandler second = new RecordingHandler(message -> true);

        handleBatch(skipped, first, second);

        assertThat(skipped.handled).isEmpty();
        assertThat(first.handled).hasSize(2);
        assertThat(second.handled).isEmpty();
    }

    @Test
    @DisplayName("reports no failures when the whole batch succeeds")
    void reportsNoFailuresWhenTheWholeBatchSucceeds() throws IOException {
        // An empty list is how the mapping is told to delete all of them; omitting the response
        // entirely would mean the same thing, but only by accident.
        assertThat(handleBatch(new RecordingHandler(message -> true)))
                .isEqualTo("{\"batchItemFailures\":[]}");
    }

    @Test
    @DisplayName("reports only the message that failed, leaving its batch-mates deleted")
    void reportsOnlyTheMessageThatFailedLeavingItsBatchMatesDeleted() throws IOException {
        RecordingHandler handler = new RecordingHandler(message -> true, SECOND_MESSAGE_ID);

        String response = handleBatch(handler);

        // The whole reason for PARTIAL_BATCH: propagating would redeliver the first message too,
        // and redoing it is only safe if the handler happens to be idempotent.
        assertThat(response)
                .isEqualTo(
                        "{\"batchItemFailures\":[{\"itemIdentifier\":\""
                                + SECOND_MESSAGE_ID
                                + "\"}]}");
        assertThat(handler.handled).hasSize(2);
    }

    @Test
    @DisplayName("never throws when a handler does, per its PARTIAL_BATCH contract")
    void neverThrowsWhenAHandlerDoes() {
        RecordingHandler handler =
                new RecordingHandler(message -> true, FIRST_MESSAGE_ID, SECOND_MESSAGE_ID);

        assertThatCode(() -> handleBatch(handler)).doesNotThrowAnyException();
        assertThat(output.toString(StandardCharsets.UTF_8))
                .contains(FIRST_MESSAGE_ID)
                .contains(SECOND_MESSAGE_ID);
    }

    @Test
    @DisplayName("reports a message no handler claimed rather than silently deleting it")
    void reportsAMessageNoHandlerClaimedRatherThanSilentlyDeletingIt() throws IOException {
        // With no handlers registered, every message has to come back - answering success here
        // would drop the batch on the floor.
        String response = handleBatch();

        assertThat(response).contains(FIRST_MESSAGE_ID).contains(SECOND_MESSAGE_ID);
    }

    @Test
    @DisplayName("keeps message bodies out of the response it writes")
    void keepsMessageBodiesOutOfTheResponseItWrites() throws IOException {
        assertThat(handleBatch()).doesNotContain(EventFixtures.SENTINEL_EMAIL);
    }

    @Test
    @DisplayName("declares that it reports partial batch failures")
    void declaresThatItReportsPartialBatchFailures() {
        assertThat(strategy().failureContract()).isEqualTo(FailureContract.PARTIAL_BATCH);
    }

    @Test
    @DisplayName("does not close the stream it was handed")
    void doesNotCloseTheStreamItWasHanded() throws IOException {
        // Jackson closes its write target by default; that stream belongs to the Lambda runtime.
        CloseTrackingStream tracked = new CloseTrackingStream();

        strategy().handle(eventFrom(EventFixtures.SQS_BATCH, context), tracked);

        assertThat(tracked.closed).isFalse();
    }

    private static final class RecordingHandler implements SqsMessageHandler {
        private final List<SqsMessage> handled = new ArrayList<>();
        private final Predicate<SqsMessage> claims;
        private final List<String> failingMessageIds;

        private RecordingHandler(Predicate<SqsMessage> claims, String... failingMessageIds) {
            this.claims = claims;
            this.failingMessageIds = List.of(failingMessageIds);
        }

        @Override
        public boolean supports(SqsMessage message) {
            return claims.test(message);
        }

        @Override
        public void handle(SqsMessage message) {
            handled.add(message);
            if (failingMessageIds.contains(message.getMessageId())) {
                throw new IllegalStateException("handler blew up");
            }
        }
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
