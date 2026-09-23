package com.habittracker.lambda.dispatch;

import com.amazonaws.services.lambda.runtime.Context;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

// Lets the HTTP strategy depend on an interface, not on the static container handler in
// StreamLambdaHandler. Load-bearing indirection - see LambdaDispatchConfig for the init trap.
@FunctionalInterface
public interface HttpRequestProxy {

    void proxy(InputStream input, OutputStream output, Context context) throws IOException;
}
