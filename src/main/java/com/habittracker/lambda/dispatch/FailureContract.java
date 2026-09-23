package com.habittracker.lambda.dispatch;

// What a strategy does with an unrecoverable failure - logged by the dispatcher, not control flow.
// Each strategy implements its own policy; declaring it makes "will AWS retry?" greppable.
public enum FailureContract {

    // Logs and reports success: AWS retry and DLQ never engage; the log line is the only signal.
    SWALLOW,

    // Never throws; returns per-item ids so only failed items redrive. Inert unless the event
    // source mapping sets function_response_types.
    PARTIAL_BATCH,

    // Rethrows, failing the invocation so the event source's own retry/DLQ acts.
    PROPAGATE
}
