# AX Module Studio Backend

Spring Backend repository and intended local development execution root for AX Module Studio.

## Current state

This is the governance-only owner bootstrap. Spring source, Maven Wrapper, Compose, Dockerfiles, Flyway SQL, and bootstrap scripts have not been created yet.

The implementation target is Spring API and Batch, PostgreSQL/pgvector, Valkey, a Flyway one-shot migration process, and integration with the sibling React and LangGraph repositories.

## Canonical repositories

- Frontend: `https://github.com/urizo-final-org/urizo-final-frontend.git`
- Orchestrator: `https://github.com/urizo-final-org/urizo-final-orchestrator.git`
- Backend: `https://github.com/urizo-final-org/urizo-final-backend.git`

The three repositories were transferred to the `urizo-final-org` organization and are the only canonical development remotes. Normal work uses a feature branch and a pull request to `dev`; direct `main` or `dev` pushes are not the normal workflow.

## Canonical documents

- Start with `docs/architecture-variants/spring-ai-primary-langgraph-coding-v0.2/README.md` and follow its reading order.
- Use `docs/AX_Module_Studio_IMPLEMENTATION_HANDOFF_v0.2.md` for implementation handoff state.
- Use `docs/TEAM_DEV_SETUP_v0.2.md` for local development setup.
- Use `docs/DATABASE_MIGRATION_POLICY_v0.2.md` for schema and Flyway work.
- Use `docs/GIT-WORKFLOW_v0.2.md` for branch, push, and pull-request rules.
- Use `docs/AX_Module_Studio_LLM_Function_Tool_Job_Harness_Design_v0.2.md` for the coding-agent tool and job harness.

## Workflow

Normal changes use `latest dev -> feature/<work-slug> -> pull request to dev`. Direct pushes to `dev` and `main` are reserved for explicitly approved owner bootstrap or emergency recovery.
