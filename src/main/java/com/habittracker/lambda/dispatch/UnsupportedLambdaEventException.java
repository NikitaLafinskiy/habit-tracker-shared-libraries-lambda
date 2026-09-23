package com.habittracker.lambda.dispatch;

// Thrown, not swallowed, so the invocation fails and shows on the Lambda Errors metric rather than
// an unrecognised event falling through into Spring MVC (what this dispatcher exists to stop).
public class UnsupportedLambdaEventException extends RuntimeException {

    public UnsupportedLambdaEventException(String message) {
        super(message);
    }

    public UnsupportedLambdaEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
