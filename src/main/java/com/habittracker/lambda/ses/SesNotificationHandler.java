package com.habittracker.lambda.ses;

public interface SesNotificationHandler {
    void handle(SnsLambdaEvent event);
}
