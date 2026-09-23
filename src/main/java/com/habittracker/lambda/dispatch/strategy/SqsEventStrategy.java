package com.habittracker.lambda.dispatch.strategy;

import com.amazonaws.services.lambda.runtime.Context;
import com.fasterxml.jackson.databind.JsonNode;
import com.habittracker.lambda.dispatch.AbstractJsonEventStrategy;
import com.habittracker.lambda.dispatch.FailureContract;
import com.habittracker.lambda.dispatch.LambdaEventDiscriminators;
import com.habittracker.lambda.dispatch.LambdaEventMapper;
import com.habittracker.lambda.sqs.SqsBatchResponse;
import com.habittracker.lambda.sqs.SqsEvent;
import com.habittracker.lambda.sqs.SqsMessage;
import com.habittracker.lambda.sqs.SqsMessageHandler;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.lang.Nullable;

// Routes each message in an SQS batch to the first SqsMessageHandler that claims it, one at a time
// because that is the granularity the mapping redrives at. Reports per-message failures rather than
// failing the invocation (which would redo the batch's successes). Logs no message body - it is the
// only field that can hold caller data.
@Slf4j
@Order(100)
public class SqsEventStrategy extends AbstractJsonEventStrategy<SqsEvent> {
    private final List<SqsMessageHandler> handlers;

    // @Nullable is load-bearing: a required List<T> with no candidate beans is an unsatisfied
    // dependency that fails the whole context. ApiApplicationTests pins this.
    public SqsEventStrategy(LambdaEventMapper mapper, @Nullable List<SqsMessageHandler> handlers) {
        super(mapper);
        this.handlers = handlers == null ? List.of() : List.copyOf(handlers);
    }

    @Override
    public boolean matches(JsonNode root) {
        return LambdaEventDiscriminators.firstRecordSourceIs(root, LambdaEventDiscriminators.SQS);
    }

    @Override
    public FailureContract failureContract() {
        return FailureContract.PARTIAL_BATCH;
    }

    @Override
    protected Class<SqsEvent> eventType() {
        return SqsEvent.class;
    }

    @Override
    protected Object process(SqsEvent event, Context context) {
        List<SqsMessage> messages = event.getRecords() == null ? List.of() : event.getRecords();
        List<SqsBatchResponse.BatchItemFailure> failures = new ArrayList<>();
        for (SqsMessage message : messages) {
            if (!handle(message)) {
                failures.add(new SqsBatchResponse.BatchItemFailure(message.getMessageId()));
            }
        }
        return new SqsBatchResponse(failures);
    }

    private boolean handle(SqsMessage message) {
        for (SqsMessageHandler handler : handlers) {
            if (handler.supports(message)) {
                return invoke(handler, message);
            }
        }
        // Reported, not dropped: no handler appears on retry, so maxReceiveCount parks it in the
        // DLQ with its payload instead of the mapping deleting it as a success.
        log.error(
                "No handler claimed SQS message {} from queue {}",
                message.getMessageId(),
                message.queueName());
        return false;
    }

    private boolean invoke(SqsMessageHandler handler, SqsMessage message) {
        try {
            handler.handle(message);
            return true;
        } catch (RuntimeException e) {
            log.error(
                    "Handler {} failed on SQS message {} from queue {}, reporting it for redrive",
                    handler.getClass().getSimpleName(),
                    message.getMessageId(),
                    message.queueName(),
                    e);
            return false;
        }
    }
}
