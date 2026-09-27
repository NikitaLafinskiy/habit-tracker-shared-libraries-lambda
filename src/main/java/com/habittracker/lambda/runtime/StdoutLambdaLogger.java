package com.habittracker.lambda.runtime;

import com.amazonaws.services.lambda.runtime.LambdaLogger;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

final class StdoutLambdaLogger implements LambdaLogger {
    private final PrintStream stream;

    StdoutLambdaLogger(PrintStream stream) {
        this.stream = stream;
    }

    @Override
    public void log(String message) {
        log(String.valueOf(message).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void log(byte[] message) {
        stream.write(message, 0, message.length);
        stream.flush();
    }
}
