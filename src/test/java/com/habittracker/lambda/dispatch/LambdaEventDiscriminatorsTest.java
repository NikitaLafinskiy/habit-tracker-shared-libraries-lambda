package com.habittracker.lambda.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

// The dispatcher takes the first strategy whose matcher fires, so matchers must be mutually
// exclusive or a new strategy could quietly steal another's traffic. Pins that against real
// captured payloads.
class LambdaEventDiscriminatorsTest {

    private static List<Arguments> fixtures() {
        return List.of(
                Arguments.of(EventFixtures.API_GATEWAY_GET, "http"),
                Arguments.of(EventFixtures.API_GATEWAY_POST, "http"),
                Arguments.of(EventFixtures.SNS_NOTIFICATION, "sns"),
                Arguments.of(EventFixtures.SQS_BATCH, "sqs"),
                Arguments.of(EventFixtures.DYNAMODB_STREAM, "dynamodb"),
                Arguments.of(EventFixtures.EVENTBRIDGE_SCHEDULED, "eventbridge"));
    }

    private static List<String> sourcesMatching(JsonNode root) {
        List<String> matched = new ArrayList<>();
        if (LambdaEventDiscriminators.isHttpProxyRequest(root)) {
            matched.add("http");
        }
        if (LambdaEventDiscriminators.firstRecordSourceIs(root, LambdaEventDiscriminators.SNS)) {
            matched.add("sns");
        }
        if (LambdaEventDiscriminators.firstRecordSourceIs(root, LambdaEventDiscriminators.SQS)) {
            matched.add("sqs");
        }
        if (LambdaEventDiscriminators.firstRecordSourceIs(
                root, LambdaEventDiscriminators.DYNAMODB)) {
            matched.add("dynamodb");
        }
        if (LambdaEventDiscriminators.isEventBridgeEvent(root, "aws.events", "Scheduled Event")) {
            matched.add("eventbridge");
        }
        return matched;
    }

    @DisplayName("classifies each captured payload as exactly one source")
    @ParameterizedTest(name = "{0} is {1}")
    @MethodSource("fixtures")
    void classifiesEachCapturedPayloadAsExactlyOneSource(String fixture, String expected) {
        assertThat(sourcesMatching(EventFixtures.tree(fixture))).containsExactly(expected);
    }

    @Test
    @DisplayName("matches a record batch under either spelling of the source field")
    void matchesARecordBatchUnderEitherSpellingOfTheSourceField() {
        JsonNode sns = EventFixtures.tree(EventFixtures.SNS_NOTIFICATION);
        JsonNode sqs = EventFixtures.tree(EventFixtures.SQS_BATCH);

        // The reason firstRecordSourceIs checks both: AWS really does disagree with itself here,
        // and reading only one spelling makes that strategy silently never match.
        assertThat(sns.path("Records").get(0).has("EventSource")).isTrue();
        assertThat(sns.path("Records").get(0).has("eventSource")).isFalse();
        assertThat(sqs.path("Records").get(0).has("eventSource")).isTrue();
        assertThat(sqs.path("Records").get(0).has("EventSource")).isFalse();

        assertThat(
                        LambdaEventDiscriminators.firstRecordSourceIs(
                                sns, LambdaEventDiscriminators.SNS))
                .isTrue();
        assertThat(
                        LambdaEventDiscriminators.firstRecordSourceIs(
                                sqs, LambdaEventDiscriminators.SQS))
                .isTrue();
    }

    @Test
    @DisplayName("claims nothing for an event source no strategy handles")
    void claimsNothingForAnEventSourceNoStrategyHandles() {
        assertThat(sourcesMatching(EventFixtures.tree(EventFixtures.UNKNOWN_KINESIS))).isEmpty();
    }

    @Test
    @DisplayName("does not treat an empty or absent record batch as a match")
    void doesNotTreatAnEmptyOrAbsentRecordBatchAsAMatch() {
        JsonNode empty = EventFixtures.tree(EventFixtures.EVENTBRIDGE_SCHEDULED);

        assertThat(
                        LambdaEventDiscriminators.firstRecordSourceIs(
                                empty, LambdaEventDiscriminators.SQS))
                .isFalse();
    }

    @DisplayName("describes an unroutable payload without echoing its contents")
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"unknown-kinesis.json", "sqs-batch.json", "api-gateway-v1-get.json"})
    void describesAnUnroutablePayloadWithoutEchoingItsContents(String fixture) {
        String shape = LambdaEventDiscriminators.describeShape(EventFixtures.tree(fixture));

        // These payloads carry addresses and bearer tokens, and this string lands in CloudWatch.
        assertThat(shape)
                .doesNotContain(EventFixtures.SENTINEL_EMAIL)
                .doesNotContain("sentinel-token-value")
                .contains("topLevelFields=");
    }

    @Test
    @DisplayName("names the unrecognised source so an unroutable event is diagnosable")
    void namesTheUnrecognisedSourceSoAnUnroutableEventIsDiagnosable() {
        String shape =
                LambdaEventDiscriminators.describeShape(
                        EventFixtures.tree(EventFixtures.UNKNOWN_KINESIS));

        assertThat(shape).contains("firstRecordSource=aws:kinesis").contains("recordCount=1");
    }
}
