package com.habittracker.lambda.dispatch;

import com.amazonaws.services.lambda.runtime.Context;
import java.io.IOException;
import java.io.OutputStream;
import org.slf4j.MDC;

// Base for non-HTTP strategies: deserializes into T, calls process(), writes the result (null ->
// {}). Stamps the AWS request id into RequestLoggingFilter's MDC key so async invocations get a
// correlation id; HTTP does not come through here - that filter owns the id itself.
public abstract class AbstractJsonEventStrategy<T> implements LambdaEventStrategy {
    private final LambdaEventMapper mapper;

    protected AbstractJsonEventStrategy(LambdaEventMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public final void handle(LambdaEvent event, OutputStream output) throws IOException {
        String requestId = event.context() == null ? null : event.context().getAwsRequestId();
        if (requestId != null) {
            MDC.put(RequestIdMdc.KEY, requestId);
        }
        try {
            mapper.write(output, process(mapper.read(event.root(), eventType()), event.context()));
        } finally {
            MDC.remove(RequestIdMdc.KEY);
        }
    }

    protected abstract Class<T> eventType();

    protected abstract Object process(T event, Context context) throws IOException;
}
