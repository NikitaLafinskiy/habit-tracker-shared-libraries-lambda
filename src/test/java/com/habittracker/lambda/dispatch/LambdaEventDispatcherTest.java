package com.habittracker.lambda.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.amazonaws.services.lambda.runtime.Context;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class LambdaEventDispatcherTest {

    private final LambdaEventMapper mapper = new LambdaEventMapper();
    private final Context context = Mockito.mock(Context.class);
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();

    private LambdaEventDispatcher dispatcherOf(LambdaEventStrategy... strategies) {
        return new LambdaEventDispatcher(List.of(strategies), mapper);
    }

    @Test
    @DisplayName("hands the event to the first strategy that claims it")
    void handsTheEventToTheFirstStrategyThatClaimsIt() throws IOException {
        RecordingStrategy first = new RecordingStrategy(root -> true, "first");
        RecordingStrategy second = new RecordingStrategy(root -> true, "second");

        dispatcherOf(first, second)
                .dispatch(EventFixtures.bytes(EventFixtures.SQS_BATCH), output, context);

        assertThat(first.handled).isTrue();
        assertThat(second.handled).isFalse();
        assertThat(output.toString(StandardCharsets.UTF_8)).isEqualTo("first");
    }

    @Test
    @DisplayName("skips strategies whose matcher does not fire")
    void skipsStrategiesWhoseMatcherDoesNotFire() throws IOException {
        RecordingStrategy ignored = new RecordingStrategy(root -> false, "ignored");
        RecordingStrategy claiming =
                new RecordingStrategy(
                        root ->
                                LambdaEventDiscriminators.firstRecordSourceIs(
                                        root, LambdaEventDiscriminators.SQS),
                        "claimed");

        dispatcherOf(ignored, claiming)
                .dispatch(EventFixtures.bytes(EventFixtures.SQS_BATCH), output, context);

        assertThat(ignored.handled).isFalse();
        assertThat(claiming.handled).isTrue();
    }

    @Test
    @DisplayName("passes the raw payload through to the strategy untouched")
    void passesTheRawPayloadThroughToTheStrategyUntouched() throws IOException {
        byte[] body = EventFixtures.bytes(EventFixtures.API_GATEWAY_GET);
        RecordingStrategy strategy = new RecordingStrategy(root -> true, "ok");

        dispatcherOf(strategy).dispatch(body, output, context);

        assertThat(strategy.received.body()).isEqualTo(body);
        assertThat(strategy.received.context()).isSameAs(context);
    }

    @Test
    @DisplayName("fails loudly when no strategy claims the event")
    void failsLoudlyWhenNoStrategyClaimsTheEvent() {
        LambdaEventDispatcher dispatcher =
                dispatcherOf(new RecordingStrategy(root -> false, "never"));
        byte[] body = EventFixtures.bytes(EventFixtures.UNKNOWN_KINESIS);

        // Previously an unrecognised event fell through into Spring MVC and died with an
        // unrelated deserialization error; it should be an explicit, alarmable failure instead.
        assertThatThrownBy(() -> dispatcher.dispatch(body, output, context))
                .isInstanceOf(UnsupportedLambdaEventException.class)
                .hasMessageContaining("firstRecordSource=aws:kinesis");
    }

    @Test
    @DisplayName("keeps payload contents out of the unroutable-event error")
    void keepsPayloadContentsOutOfTheUnroutableEventError() {
        LambdaEventDispatcher dispatcher = dispatcherOf();
        byte[] body = EventFixtures.bytes(EventFixtures.UNKNOWN_KINESIS);

        assertThatThrownBy(() -> dispatcher.dispatch(body, output, context))
                .isInstanceOf(UnsupportedLambdaEventException.class)
                .hasMessageNotContaining(EventFixtures.SENTINEL_EMAIL);
    }

    @Test
    @DisplayName("rejects a payload that is not JSON at all")
    void rejectsAPayloadThatIsNotJsonAtAll() {
        LambdaEventDispatcher dispatcher =
                dispatcherOf(new RecordingStrategy(root -> true, "unreachable"));
        byte[] body = EventFixtures.bytes(EventFixtures.NOT_JSON);

        assertThatThrownBy(() -> dispatcher.dispatch(body, output, context))
                .isInstanceOf(UnsupportedLambdaEventException.class)
                .hasMessageContaining("not JSON");
    }

    @Test
    @DisplayName("rethrows what a strategy threw rather than masking it")
    void rethrowsWhatAStrategyThrewRatherThanMaskingIt() {
        LambdaEventDispatcher dispatcher =
                dispatcherOf(
                        new LambdaEventStrategy() {
                            @Override
                            public boolean matches(JsonNode root) {
                                return true;
                            }

                            @Override
                            public void handle(LambdaEvent event, OutputStream out) {
                                throw new IllegalStateException("boom");
                            }
                        });
        byte[] body = EventFixtures.bytes(EventFixtures.SQS_BATCH);

        assertThatThrownBy(() -> dispatcher.dispatch(body, output, context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");
    }

    private static final class RecordingStrategy implements LambdaEventStrategy {
        private final Predicate<JsonNode> matcher;
        private final String response;

        private boolean handled;
        private LambdaEvent received;

        private RecordingStrategy(Predicate<JsonNode> matcher, String response) {
            this.matcher = matcher;
            this.response = response;
        }

        @Override
        public boolean matches(JsonNode root) {
            return matcher.test(root);
        }

        @Override
        public void handle(LambdaEvent event, OutputStream out) throws IOException {
            handled = true;
            received = event;
            out.write(response.getBytes(StandardCharsets.UTF_8));
        }
    }
}
