package com.habittracker.lambda.dispatch.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.habittracker.lambda.dispatch.HttpRequestProxy;
import com.habittracker.lambda.dispatch.LambdaEvent;
import com.habittracker.lambda.dispatch.LambdaEventDiscriminators;
import com.habittracker.lambda.dispatch.LambdaEventStrategy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

// The API Gateway path: hands the payload to the serverless container, which runs the real Spring
// MVC/Security chain. Errors are already HTTP statuses by then, so the default PROPAGATE fits.
// Matched positively (not a fallback) and ordered first - it is nearly all traffic.
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class ApiGatewayHttpEventStrategy implements LambdaEventStrategy {
    private final HttpRequestProxy httpRequestProxy;

    @Override
    public boolean matches(JsonNode root) {
        return LambdaEventDiscriminators.isHttpProxyRequest(root);
    }

    @Override
    public void handle(LambdaEvent event, OutputStream output) throws IOException {
        httpRequestProxy.proxy(new ByteArrayInputStream(event.body()), output, event.context());
    }
}
