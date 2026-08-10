# Backend Repository Rules

- Scope: Spring platform API, Spring Batch, Flyway migrations, infrastructure composition, and workspace bootstrap scripts.
- Follow the parent workspace architecture and Git workflow rules.
- Implementation follows the Spring AI primary and Python LangGraph coding-agent candidate architecture v0.2.
- Before implementation, read `docs/architecture-variants/spring-ai-primary-langgraph-coding-v0.2/README.md` and every document in its required reading order.
- For local setup, migrations, Git workflow, or coding-agent harness work, read the matching v0.2 document under `docs/` first.
- Earlier FastAPI documents are preserved snapshots, not executable source backups.
- Flyway is the only DDL owner. Runtime accounts and ORM/schema auto-creation must not mutate schema.
- Local business, RAG, checkpoint, and test data are not synchronized through Git.
- Do not create product scaffolding or run migrations until the next implementation stage is explicitly approved.
- Keep secrets out of source, prompts, logs, commits, and pull requests.
- Normal work branches from the latest `dev` and reaches `dev` through a reviewed pull request.
