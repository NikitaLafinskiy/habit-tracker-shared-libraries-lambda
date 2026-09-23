package com.habittracker.lambda.dispatch;

import com.amazonaws.services.lambda.runtime.Context;
import com.fasterxml.jackson.databind.JsonNode;

// body is the raw payload (the invocation stream reads only once); root is it parsed, for routing.
public record LambdaEvent(byte[] body, JsonNode root, Context context) {}
