CREATE TABLE app.ai_profile_default_template (
    profile_key VARCHAR(32) PRIMARY KEY,
    snapshot_json JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_ai_profile_default_template_key
        CHECK (profile_key IN ('LLM_OPS', 'NATURAL_CMS')),
    CONSTRAINT ck_ai_profile_default_template_snapshot
        CHECK (
            jsonb_typeof(snapshot_json) = 'object'
            AND snapshot_json ?& ARRAY[
                'nodes', 'edges', 'config', 'modelBindings',
                'toolPolicy', 'guardrailProfileKey'
            ]
        )
);

INSERT INTO app.ai_profile_default_template (profile_key, snapshot_json)
VALUES
(
    'LLM_OPS',
    $$
    {
      "nodes": [
        {"id":"start","type":"start","handlerKey":"common.start","resultPorts":["next"],"config":{}},
        {"id":"guardrail","type":"guardrail","handlerKey":"common.guardrail","resultPorts":["passed","failed"],"config":{"locked":true}},
        {"id":"analyze","type":"agent","handlerKey":"coding.analyze","resultPorts":["feasible","infeasible"],"config":{}},
        {"id":"scope_approval","type":"approval","handlerKey":"coding.approval","resultPorts":["approved"],"config":{"stage":"SCOPE","requiredRole":"GENERAL_ADMIN"}},
        {"id":"code","type":"agent","handlerKey":"coding.code","resultPorts":["completed"],"config":{}},
        {"id":"review","type":"agent","handlerKey":"coding.review","resultPorts":["passed","changes_requested"],"config":{}},
        {"id":"rework_gate","type":"check","handlerKey":"coding.rework_gate","resultPorts":["retry","handover"],"config":{"maxReworkRounds":3}},
        {"id":"preview","type":"tool","handlerKey":"coding.preview","resultPorts":["ready"],"config":{}},
        {"id":"preview_approval","type":"approval","handlerKey":"coding.preview_approval","resultPorts":["approved","rejected"],"config":{"stage":"CANDIDATE","requiredRole":"GENERAL_ADMIN"}},
        {"id":"pr_request","type":"tool","handlerKey":"coding.pr_request","resultPorts":["requested"],"config":{}},
        {"id":"github_approval","type":"approval","handlerKey":"coding.approval","resultPorts":["approved"],"config":{"stage":"GITHUB","requiredRole":"SUPER_ADMIN"}},
        {"id":"pr_complete","type":"tool","handlerKey":"coding.pr_complete","resultPorts":["completed"],"config":{}},
        {"id":"deploy_request","type":"tool","handlerKey":"coding.deploy_request","resultPorts":["recorded"],"config":{"mode":"request_record_only"}},
        {"id":"deploy_approval","type":"approval","handlerKey":"coding.approval","resultPorts":["approved"],"config":{"stage":"DEPLOY","requiredRole":"SUPER_ADMIN"}},
        {"id":"dev_merge_check","type":"check","handlerKey":"coding.dev_merge_check","resultPorts":["merged","not_merged","blocked"],"config":{}},
        {"id":"deploy","type":"tool","handlerKey":"coding.deploy","resultPorts":["completed","blocked"],"config":{}},
        {"id":"end","type":"end","handlerKey":"common.end","resultPorts":[],"config":{}}
      ],
      "edges": [
        {"from":"start","resultPort":"next","to":"guardrail"},
        {"from":"guardrail","resultPort":"passed","to":"analyze"},
        {"from":"guardrail","resultPort":"failed","to":"end"},
        {"from":"analyze","resultPort":"feasible","to":"scope_approval"},
        {"from":"analyze","resultPort":"infeasible","to":"end"},
        {"from":"scope_approval","resultPort":"approved","to":"code"},
        {"from":"code","resultPort":"completed","to":"review"},
        {"from":"review","resultPort":"passed","to":"preview"},
        {"from":"review","resultPort":"changes_requested","to":"rework_gate"},
        {"from":"rework_gate","resultPort":"retry","to":"code"},
        {"from":"rework_gate","resultPort":"handover","to":"end"},
        {"from":"preview","resultPort":"ready","to":"preview_approval"},
        {"from":"preview_approval","resultPort":"approved","to":"pr_request"},
        {"from":"preview_approval","resultPort":"rejected","to":"analyze"},
        {"from":"pr_request","resultPort":"requested","to":"github_approval"},
        {"from":"github_approval","resultPort":"approved","to":"pr_complete"},
        {"from":"pr_complete","resultPort":"completed","to":"deploy_request"},
        {"from":"deploy_request","resultPort":"recorded","to":"deploy_approval"},
        {"from":"deploy_approval","resultPort":"approved","to":"dev_merge_check"},
        {"from":"dev_merge_check","resultPort":"not_merged","to":"deploy_request"},
        {"from":"dev_merge_check","resultPort":"merged","to":"deploy"},
        {"from":"dev_merge_check","resultPort":"blocked","to":"end"},
        {"from":"deploy","resultPort":"completed","to":"end"},
        {"from":"deploy","resultPort":"blocked","to":"end"}
      ],
      "config": {
        "maxNodes": 17,
        "maxAttempts": 3,
        "loopLimits": [
          {"from":"rework_gate","resultPort":"retry","to":"code","maxIterations":2},
          {"from":"preview_approval","resultPort":"rejected","to":"analyze","maxIterations":2},
          {"from":"dev_merge_check","resultPort":"not_merged","to":"deploy_request","maxIterations":2}
        ]
      },
      "modelBindings": {
        "analyze":{"primary":"llm-ops-analyze","fallback":[]},
        "code":{"primary":"llm-ops-code","fallback":[]},
        "review":{"primary":"llm-ops-review","fallback":[]}
      },
      "toolPolicy":{"allowedTools":["read_file","search_code","read_diff","apply_patch","run_check","check_package_allowlist","scan_changed_files"]},
      "guardrailProfileKey":"central.default"
    }
    $$::jsonb
),
(
    'NATURAL_CMS',
    $$
    {
      "nodes": [
        {"id":"start","type":"start","handlerKey":"common.start","resultPorts":["next"],"config":{}},
        {"id":"guardrail","type":"guardrail","handlerKey":"common.guardrail","resultPorts":["passed","failed"],"config":{"locked":true}},
        {"id":"analyze","type":"agent","handlerKey":"cms.analyze","resultPorts":["feasible","infeasible"],"config":{}},
        {"id":"preview","type":"agent","handlerKey":"cms.preview","resultPorts":["ready"],"config":{}},
        {"id":"approval","type":"approval","handlerKey":"cms.approval","resultPorts":["approved","rejected"],"config":{"stage":"PREVIEW","requiredRole":"GENERAL_ADMIN"}},
        {"id":"discard","type":"tool","handlerKey":"cms.discard","resultPorts":["retry","discarded"],"config":{}},
        {"id":"apply","type":"tool","handlerKey":"cms.apply","resultPorts":["applied"],"config":{}},
        {"id":"end","type":"end","handlerKey":"common.end","resultPorts":[],"config":{}}
      ],
      "edges": [
        {"from":"start","resultPort":"next","to":"guardrail"},
        {"from":"guardrail","resultPort":"passed","to":"analyze"},
        {"from":"guardrail","resultPort":"failed","to":"end"},
        {"from":"analyze","resultPort":"feasible","to":"preview"},
        {"from":"analyze","resultPort":"infeasible","to":"end"},
        {"from":"preview","resultPort":"ready","to":"approval"},
        {"from":"approval","resultPort":"approved","to":"apply"},
        {"from":"approval","resultPort":"rejected","to":"discard"},
        {"from":"discard","resultPort":"retry","to":"analyze"},
        {"from":"discard","resultPort":"discarded","to":"end"},
        {"from":"apply","resultPort":"applied","to":"end"}
      ],
      "config":{"maxNodes":8,"maxAttempts":3,"loopLimits":[{"from":"discard","resultPort":"retry","to":"analyze","maxIterations":2}]},
      "modelBindings": {
        "analyze":{"primary":"natural-cms-analyze","fallback":[]},
        "preview":{"primary":"natural-cms-command","fallback":[]}
      },
      "toolPolicy":{"allowedTools":["resolve_cms_target","validate_cms_command","create_cms_preview","discard_cms_preview","revalidate_cms_preview","apply_cms_preview"]},
      "guardrailProfileKey":"central.default"
    }
    $$::jsonb
);

GRANT SELECT, INSERT, UPDATE ON app.ai_profile_default_template TO ai_workspace;
