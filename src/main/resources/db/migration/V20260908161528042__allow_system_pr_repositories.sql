ALTER TABLE app.coding_handler_result
    DROP CONSTRAINT ck_coding_handler_result_ai04_009_payload,
    ADD CONSTRAINT ck_coding_handler_result_ai04_016_payload CHECK (
        (handler_key <> 'coding.pr_complete' OR (
            payload ->> 'repository' IN ('backend', 'frontend')
            AND payload ->> 'base' = 'dev'
            AND payload ->> 'head' ~ '^system/llmops-[a-z0-9][a-z0-9-]*$'
            AND payload ->> 'candidateSha' = candidate_sha
            AND payload ->> 'headSha' ~ '^sha1:[0-9a-f]{40}$'
            AND jsonb_typeof(payload -> 'prNumber') = 'number'
            AND payload ->> 'prNumber' ~ '^[1-9][0-9]*$'
            AND length(payload ->> 'prUrl') > 0))
        AND (handler_key <> 'coding.dev_merge_check' OR (
            payload ->> 'repository' = 'backend'
            AND payload ->> 'base' = 'dev'
            AND payload ->> 'head' ~ '^system/llmops-[a-z0-9][a-z0-9-]*$'
            AND payload ->> 'candidateSha' = candidate_sha
            AND payload ->> 'headSha' ~ '^sha1:[0-9a-f]{40}$'
            AND payload ->> 'status' IN ('MERGED', 'NOT_MERGED', 'BLOCKED')
            AND (result_port <> 'merged'
                 OR payload ->> 'mergeSha' ~ '^sha1:[0-9a-f]{40}$')))
        AND (handler_key <> 'coding.deploy_request'
             OR NOT (payload ? 'deploymentRequestId') OR (
                payload ->> 'jobId' = job_id::text
                AND jsonb_typeof(payload -> 'pipelineAttempt') = 'number'
                AND payload ->> 'pipelineAttempt' = pipeline_attempt::text
                AND payload ->> 'repository' = 'backend'
                AND payload ->> 'candidateSha' = candidate_sha
                AND payload ->> 'sourceValidationHash' ~ '^sha256:[0-9a-f]{64}$'
                AND length(payload ->> 'adapterKey') > 0
                AND length(payload ->> 'targetKey') > 0
                AND payload ->> 'configDigest' ~ '^sha256:[0-9a-f]{64}$'
                AND payload ->> 'deploymentRequestId'
                    ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'))
        AND (handler_key <> 'coding.deploy' OR (
            payload ->> 'deploymentRequestId'
                ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
            AND payload ->> 'deploymentExecutionId'
                ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
            AND payload ->> 'mergeSha' ~ '^sha1:[0-9a-f]{40}$'
            AND payload ->> 'configDigest' ~ '^sha256:[0-9a-f]{64}$')));
