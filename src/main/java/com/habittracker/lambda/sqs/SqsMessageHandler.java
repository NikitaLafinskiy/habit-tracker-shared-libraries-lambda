package com.habittracker.lambda.sqs;

// Handling for one kind of SQS message: the dispatcher's routing, one level down. Add a consumer by
// adding one @Component implementing this; SqsEventStrategy finds it.
public interface SqsMessageHandler {

    // Called on handlers in turn until one matches, so it must be cheap and side-effect-free.
    boolean supports(SqsMessage message);

    // Throwing reports this message alone for redrive; a normal return deletes it. Per message, not
    // per batch - one poisoned message must not take its batch-mates down.
    void handle(SqsMessage message);
}
