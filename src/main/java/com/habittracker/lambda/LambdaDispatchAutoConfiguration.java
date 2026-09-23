package com.habittracker.lambda;

import com.habittracker.lambda.dispatch.HttpRequestProxy;
import com.habittracker.lambda.dispatch.LambdaEventDispatcher;
import com.habittracker.lambda.dispatch.LambdaEventMapper;
import com.habittracker.lambda.dispatch.LambdaEventStrategy;
import com.habittracker.lambda.dispatch.strategy.ApiGatewayHttpEventStrategy;
import com.habittracker.lambda.dispatch.strategy.KeepWarmEventStrategy;
import com.habittracker.lambda.dispatch.strategy.SesNotificationEventStrategy;
import com.habittracker.lambda.dispatch.strategy.SqsEventStrategy;
import com.habittracker.lambda.ses.SesNotificationHandler;
import com.habittracker.lambda.sqs.SqsMessageHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class LambdaDispatchAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public LambdaEventMapper lambdaEventMapper() {
        return new LambdaEventMapper();
    }

    @Bean
    @ConditionalOnMissingBean
    public LambdaEventDispatcher lambdaEventDispatcher(
            ObjectProvider<LambdaEventStrategy> strategies, LambdaEventMapper mapper) {
        return new LambdaEventDispatcher(strategies.orderedStream().toList(), mapper);
    }

    @Bean
    @ConditionalOnBean(HttpRequestProxy.class)
    public ApiGatewayHttpEventStrategy apiGatewayHttpEventStrategy(
            HttpRequestProxy httpRequestProxy) {
        return new ApiGatewayHttpEventStrategy(httpRequestProxy);
    }

    @Bean
    public KeepWarmEventStrategy keepWarmEventStrategy(LambdaEventMapper mapper) {
        return new KeepWarmEventStrategy(mapper);
    }

    @Bean
    public SqsEventStrategy sqsEventStrategy(
            LambdaEventMapper mapper, ObjectProvider<SqsMessageHandler> handlers) {
        return new SqsEventStrategy(mapper, handlers.orderedStream().toList());
    }

    @Bean
    @ConditionalOnBean(SesNotificationHandler.class)
    public SesNotificationEventStrategy sesNotificationEventStrategy(
            LambdaEventMapper mapper, SesNotificationHandler sesNotificationHandler) {
        return new SesNotificationEventStrategy(mapper, sesNotificationHandler);
    }
}
