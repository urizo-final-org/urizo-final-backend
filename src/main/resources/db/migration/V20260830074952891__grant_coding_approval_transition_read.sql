GRANT SELECT (
    command_type,
    job_id,
    from_status,
    to_status,
    result_state_version
) ON app.coding_job_lifecycle_command_status TO ai_workspace;
