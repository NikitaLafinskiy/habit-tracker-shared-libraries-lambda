package com.habittracker.lambda;

import static org.assertj.core.api.Assertions.assertThat;

import com.habittracker.lambda.env.SsmBackedPropertiesEnvironmentPostProcessor;
import com.habittracker.lambda.sqs.SqsBatchResponse;
import com.habittracker.lambda.sqs.SqsMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.context.annotation.ImportRuntimeHints;

class LambdaDispatchRuntimeHintsTest {

    private final RuntimeHints hints = registeredHints();

    private static RuntimeHints registeredHints() {
        RuntimeHints hints = new RuntimeHints();
        new LambdaDispatchRuntimeHints()
                .registerHints(hints, LambdaDispatchRuntimeHintsTest.class.getClassLoader());
        return hints;
    }

    @Test
    @DisplayName("every event type the strategies read or write is registered for reflection")
    void registersEveryEventType() {
        for (Class<?> type : LambdaDispatchRuntimeHints.EVENT_TYPES) {
            assertThat(RuntimeHintsPredicates.reflection().onType(type)).accepts(hints);
        }
    }

    @Test
    @DisplayName("Jackson can call the accessors it binds through")
    void registersBindingAccessors() {
        assertThat(RuntimeHintsPredicates.reflection().onMethod(SqsMessage.class, "setBody"))
                .accepts(hints);
        assertThat(
                        RuntimeHintsPredicates.reflection()
                                .onMethod(SqsBatchResponse.class, "batchItemFailures"))
                .accepts(hints);
    }

    @Test
    @DisplayName("the SSM post-processor can be instantiated from spring.factories")
    void registersSsmPostProcessorConstructor() {
        assertThat(
                        RuntimeHintsPredicates.reflection()
                                .onType(SsmBackedPropertiesEnvironmentPostProcessor.class))
                .accepts(hints);
    }

    @Test
    @DisplayName("the auto-configuration imports the hints, so every service gets them")
    void autoConfigurationImportsTheHints() {
        ImportRuntimeHints imported =
                LambdaDispatchAutoConfiguration.class.getAnnotation(ImportRuntimeHints.class);

        assertThat(imported).isNotNull();
        assertThat(imported.value()).contains(LambdaDispatchRuntimeHints.class);
    }
}
