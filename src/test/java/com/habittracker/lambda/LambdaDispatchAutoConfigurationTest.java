package com.habittracker.lambda;

import static org.assertj.core.api.Assertions.assertThat;

import com.habittracker.lambda.dispatch.HttpRequestProxy;
import com.habittracker.lambda.dispatch.LambdaEventDispatcher;
import com.habittracker.lambda.dispatch.LambdaEventStrategy;
import com.habittracker.lambda.dispatch.strategy.ApiGatewayHttpEventStrategy;
import com.habittracker.lambda.dispatch.strategy.KeepWarmEventStrategy;
import com.habittracker.lambda.dispatch.strategy.SesNotificationEventStrategy;
import com.habittracker.lambda.dispatch.strategy.SqsEventStrategy;
import com.habittracker.lambda.ses.SesNotificationHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class LambdaDispatchAutoConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(LambdaDispatchAutoConfiguration.class));

    @Test
    @DisplayName("a worker with no HTTP proxy, SQS handler or SES handler still starts")
    void startsWithNothingProvided() {
        runner.run(
                context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(LambdaEventDispatcher.class);
                    assertThat(context).hasSingleBean(SqsEventStrategy.class);
                    assertThat(context).hasSingleBean(KeepWarmEventStrategy.class);
                    assertThat(context).doesNotHaveBean(ApiGatewayHttpEventStrategy.class);
                    assertThat(context).doesNotHaveBean(SesNotificationEventStrategy.class);
                });
    }

    @Test
    @DisplayName("the HTTP and SES strategies register once their collaborators exist")
    void registersOptionalStrategies() {
        runner.withBean(HttpRequestProxy.class, () -> (input, output, context) -> {})
                .withBean(SesNotificationHandler.class, () -> event -> {})
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(ApiGatewayHttpEventStrategy.class);
                            assertThat(context).hasSingleBean(SesNotificationEventStrategy.class);
                        });
    }

    @Test
    @DisplayName("strategies reach the dispatcher with HTTP first and keep-warm last")
    void ordersStrategies() {
        runner.withBean(HttpRequestProxy.class, () -> (input, output, context) -> {})
                .run(
                        context ->
                                assertThat(
                                                context.getBeanProvider(LambdaEventStrategy.class)
                                                        .orderedStream()
                                                        .map(Object::getClass))
                                        .containsExactly(
                                                ApiGatewayHttpEventStrategy.class,
                                                SqsEventStrategy.class,
                                                KeepWarmEventStrategy.class));
    }
}
