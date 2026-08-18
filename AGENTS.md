# Backend Repository Agent Entry

## Common authority routing

- This file is a repository entry point, not a copy of team policy.
- Cross-repository policy, roles, Wave/WBS state, assignments, Git/PR workflow, and shared safety rules are owned only by the sibling `../urizo-final-master/AGENTS.md` and its required current-status documents.
- Before planning or editing, read that Master authority from the canonical parent workspace. If the sibling Master checkout is unavailable, do not infer current work from this repository alone; reopen the canonical four-repository workspace or synchronize Master first.
- Claude Code uses `CLAUDE.md`, which imports this file. Do not add a second copy of common policy there.

## Repository-local scope

- Own Spring platform APIs, Spring Batch, public/coding contracts, Flyway Core migrations, Tool Gateway, integrated Compose, and development bootstrap scripts.
- Flyway is the only Core DDL owner. Runtime accounts and ORM/schema auto-creation must not mutate the Core schema.
- The sibling Orchestrator owns Python LangGraph graph/checkpoint implementation; the Backend owns the authority and contracts it consumes.
- `README.md`, current source, contracts, migrations, and executable verification scripts describe the implemented Backend. Documents marked historical or non-normative preserve evidence only and never override Master status or policy.
