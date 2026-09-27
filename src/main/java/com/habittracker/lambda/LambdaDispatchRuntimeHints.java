package com.habittracker.lambda;

import com.habittracker.lambda.env.SsmBackedPropertiesEnvironmentPostProcessor;
import com.habittracker.lambda.ses.SnsLambdaEvent;
import com.habittracker.lambda.ses.SnsMessage;
import com.habittracker.lambda.ses.SnsRecord;
import com.habittracker.lambda.sqs.SqsBatchResponse;
import com.habittracker.lambda.sqs.SqsEvent;
import com.habittracker.lambda.sqs.SqsMessage;
import java.util.List;
import org.springframework.aot.hint.BindingReflectionHintsRegistrar;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

public class LambdaDispatchRuntimeHints implements RuntimeHintsRegistrar {
    static final List<Class<?>> EVENT_TYPES =
            List.of(
                    SqsEvent.class,
                    SqsMessage.class,
                    SqsBatchResponse.class,
                    SqsBatchResponse.BatchItemFailure.class,
                    SnsLambdaEvent.class,
                    SnsRecord.class,
                    SnsMessage.class);

    private final BindingReflectionHintsRegistrar bindingRegistrar =
            new BindingReflectionHintsRegistrar();

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        bindingRegistrar.registerReflectionHints(
                hints.reflection(), EVENT_TYPES.toArray(Class<?>[]::new));
        hints.reflection()
                .registerType(
                        SsmBackedPropertiesEnvironmentPostProcessor.class,
                        MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
    }
}
