package com.habittracker.lambda.dispatch.strategy;

import com.amazonaws.services.lambda.runtime.Context;
import com.fasterxml.jackson.databind.JsonNode;
import com.habittracker.lambda.dispatch.AbstractJsonEventStrategy;
import com.habittracker.lambda.dispatch.FailureContract;
import com.habittracker.lambda.dispatch.LambdaEventDiscriminators;
import com.habittracker.lambda.dispatch.LambdaEventMapper;
import com.habittracker.lambda.ses.SesNotificationHandler;
import com.habittracker.lambda.ses.SnsLambdaEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;

// SES bounce/complaint notifications, delivered via a direct SNS "lambda" subscription (not API
// Gateway). Failures are swallowed - SNS retry and any DLQ never engage, so the logged error is
// the only signal (worth a CloudWatch metric filter).
@Slf4j
@Order(100)
public class SesNotificationEventStrategy extends AbstractJsonEventStrategy<SnsLambdaEvent> {
    private final SesNotificationHandler sesNotificationHandler;

    public SesNotificationEventStrategy(
            LambdaEventMapper mapper, SesNotificationHandler sesNotificationHandler) {
        super(mapper);
        this.sesNotificationHandler = sesNotificationHandler;
    }

    @Override
    public boolean matches(JsonNode root) {
        return LambdaEventDiscriminators.firstRecordSourceIs(root, LambdaEventDiscriminators.SNS);
    }

    @Override
    public FailureContract failureContract() {
        return FailureContract.SWALLOW;
    }

    @Override
    protected Class<SnsLambdaEvent> eventType() {
        return SnsLambdaEvent.class;
    }

    @Override
    protected Object process(SnsLambdaEvent event, Context context) {
        try {
            sesNotificationHandler.handle(event);
        } catch (RuntimeException e) {
            log.error("SES notification handling failed", e);
        }
        return null;
    }
}
