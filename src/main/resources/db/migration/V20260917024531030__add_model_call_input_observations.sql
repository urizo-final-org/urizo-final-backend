-- Unknown historical observations stay NULL; never backfill synthetic zeroes.
ALTER TABLE app.ai_job_model_call
    ADD COLUMN input_tokens BIGINT CHECK (input_tokens >= 0),
    ADD COLUMN output_tokens BIGINT CHECK (output_tokens >= 0),
    ADD COLUMN cached_input_tokens BIGINT CHECK (cached_input_tokens >= 0),
    ADD COLUMN input_processing JSONB CHECK (jsonb_typeof(input_processing) = 'object'),
    ADD CONSTRAINT ai_job_model_call_cache_bound
        CHECK (cached_input_tokens IS NULL OR input_tokens IS NULL OR cached_input_tokens <= input_tokens);
