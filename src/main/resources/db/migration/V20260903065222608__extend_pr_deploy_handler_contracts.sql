ALTER TABLE app.coding_handler_result
    DROP CONSTRAINT ck_coding_handler_result_type,
    ADD CONSTRAINT ck_coding_handler_result_type CHECK (
        result_type IN ('ANALYSIS', 'CANDIDATE', 'DIFF', 'REVIEW',
                        'PULL_REQUEST', 'DEV_MERGE', 'DEPLOY_REQUEST', 'DEPLOYMENT')),
    DROP CONSTRAINT ck_coding_handler_result_port,
    ADD CONSTRAINT ck_coding_handler_result_port CHECK (
        result_port IN ('feasible', 'infeasible', 'completed', 'passed',
                        'changes_requested', 'ready', 'requested', 'recorded',
                        'merged', 'not_merged', 'blocked')),
    DROP CONSTRAINT ck_coding_handler_result_registry,
    ADD CONSTRAINT ck_coding_handler_result_registry CHECK (
        (handler_key = 'coding.analyze'
            AND result_type = 'ANALYSIS'
            AND result_port IN ('feasible', 'infeasible'))
        OR (handler_key = 'coding.code'
            AND result_type = 'CANDIDATE'
            AND result_port = 'completed')
        OR (handler_key = 'coding.review'
            AND result_type = 'REVIEW'
            AND result_port IN ('passed', 'changes_requested'))
        OR (handler_key = 'coding.preview'
            AND result_type = 'DIFF'
            AND result_port = 'ready')
        OR (handler_key = 'coding.pr_request'
            AND result_type = 'PULL_REQUEST'
            AND result_port = 'requested')
        OR (handler_key = 'coding.pr_complete'
            AND result_type = 'PULL_REQUEST'
            AND result_port = 'completed')
        OR (handler_key = 'coding.dev_merge_check'
            AND result_type = 'DEV_MERGE'
            AND result_port IN ('merged', 'not_merged', 'blocked'))
        OR (handler_key = 'coding.deploy_request'
            AND result_type = 'DEPLOY_REQUEST'
            AND result_port = 'recorded')
        OR (handler_key = 'coding.deploy'
            AND result_type = 'DEPLOYMENT'
            AND result_port IN ('completed', 'blocked'))),
    DROP CONSTRAINT ck_coding_handler_result_shape,
    ADD CONSTRAINT ck_coding_handler_result_shape CHECK (
        (result_type = 'ANALYSIS')
        OR (result_type = 'CANDIDATE' AND candidate_sha IS NOT NULL)
        OR (result_type = 'DIFF' AND candidate_sha IS NOT NULL
            AND diff_digest IS NOT NULL AND validation_hash IS NOT NULL)
        OR (result_type = 'REVIEW' AND candidate_sha IS NOT NULL)
        OR (result_type IN ('PULL_REQUEST', 'DEV_MERGE', 'DEPLOY_REQUEST', 'DEPLOYMENT')
            AND candidate_sha IS NOT NULL AND validation_hash IS NOT NULL)),
    ADD CONSTRAINT ck_coding_handler_result_ai04_009_payload CHECK (
        (handler_key <> 'coding.pr_complete' OR (
            payload ->> 'repository' = 'backend'
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

ALTER TABLE app.coding_runner_task
    DROP CONSTRAINT ck_coding_runner_task_kind,
    ADD CONSTRAINT ck_coding_runner_task_kind CHECK (kind IN (
        'CREATE_WORKTREE',
        'PREPARE_SCAN_WORKTREE',
        'BUILD',
        'TEST',
        'PREVIEW_UP',
        'PREVIEW_DOWN',
        'CREATE_PR',
        'CHECK_DEV_MERGE',
        'DEPLOY_LOCAL_COMPOSE'));
