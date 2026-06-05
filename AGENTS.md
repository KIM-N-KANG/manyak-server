# Manyak Server LLM Harness

This project uses the local team wiki as the primary knowledge source for LLM-assisted work.

## Wiki Source

- Local wiki path: `./wiki`
- Primary entry point: `wiki/index.md`
- Team operating rules: `wiki/CLAUDE.md` and `wiki/.claude/commands/*`
- Curated knowledge pages: `wiki/wiki/**/*.md`
- Source and evidence pages: `wiki/raw/**` and `wiki/wiki/sources/**`

## Required Behavior

- Read this file first when starting work in this repository.
- Prefer local wiki files over GitHub or API access.
- Use GitHub only as a fallback when the user explicitly asks for online lookup or when local wiki content is unavailable and online verification is necessary.
- If `./wiki` is missing, do not repeatedly call GitHub APIs. Tell the user to clone it with:

  ```bash
  git clone https://github.com/KIM-N-KANG/llm-wiki.git wiki
  ```

- When wiki freshness matters, ask the user to run:

  ```bash
  git -C wiki pull
  ```

- Do not copy wiki contents into this repository.
- Do not commit files under `wiki/` to this repository.
- Treat `wiki/` as a separate Git repository managed independently from `manyak-server`.

## Jira Workflow

- Work should usually be tied to a Jira issue.
- When the user mentions an issue key such as `KNK-92` or says "Jira ticket 92", fetch the Jira issue before planning or editing.
- Use the issue title, description, comments, labels, linked issues, subtasks, and attachments as task context.
- If the issue is ambiguous or missing acceptance criteria, state the gap and propose a narrow implementation scope before editing.
- Use the branch naming convention from the team wiki: `{tag}/KNK-{issue-number}-{branch-title}`.
- Use the commit convention from the team wiki: `[KNK-{issue-number}] {Tag}: {commit title}`.
- Do not transition Jira status, assign users, or add Jira comments unless the user explicitly asks.
- After implementation, summarize changed files, verification results, and suggested Jira update text.

## Kotlin Spring Work

- This backend uses Kotlin, Spring Boot, Gradle Kotlin DSL, Java 21, JPA, Flyway, Security, and PostgreSQL.
- Read existing package structure and build configuration before editing.
- Keep boilerplate changes small and boring: prefer Spring Boot defaults unless the project has a clear reason to customize.
- Use `/api/v1` as the prefix for business APIs.
- Use Spring Boot Actuator for operational health checks; do not add custom health endpoints unless there is a concrete product or infrastructure requirement.
- Put externally configurable values in Spring configuration or environment variables. Do not commit real secrets.
- When changing JPA, Flyway, Security, datasource, or API behavior, run relevant Gradle verification before reporting completion.
- If verification cannot run because of local environment limits, explain the exact blocker and the command that should be run.

## Obsidian

- The `wiki/` directory can be opened directly as an Obsidian vault.
- Wiki edits should be committed and pushed from inside `wiki/`, not from this server repository.
