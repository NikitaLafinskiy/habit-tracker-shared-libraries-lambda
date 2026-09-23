package com.habittracker.lambda.sqs;

import java.util.List;

// Partial-batch response an SQS mapping reads back: listing failed messages redrives only them,
// leaving the batch's successes deleted. Only read when the mapping sets function_response_types=
// ["ReportBatchItemFailures"] (hardcoded in iac/modules/sqs-event-source-mapping). Field names are
// AWS's - a typo is silent, read as an empty failure list.
public record SqsBatchResponse(List<BatchItemFailure> batchItemFailures) {

    // itemIdentifier must be the failed message's messageId.
    public record BatchItemFailure(String itemIdentifier) {}
}
