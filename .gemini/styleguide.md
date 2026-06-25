# Manyak Server Code Review Style Guide

## Review Language

- Write review comments in Korean.
- Keep comments concise and specific.
- Prefer actionable comments over broad advice.
- Do not block a pull request for personal preference, minor formatting, or speculative refactoring.
- If a comment is optional, clearly mark it as a suggestion.

## Review Priorities

Focus review comments on issues that can affect correctness, security, maintainability, or team conventions.

High-priority review areas:

- Bugs, broken behavior, incorrect assumptions, or missing edge-case handling
- Security issues, including secret exposure, unsafe authentication rules, authorization gaps, and unsafe input handling
- Database risks, including unsafe Flyway migrations, schema drift, destructive changes, and missing transaction boundaries
- API contract issues, including path changes, incompatible response changes, unclear status codes, and missing validation
- Missing or weak tests for meaningful behavior changes
- Changes that conflict with existing project conventions or team workflow

Lower-priority review areas:

- Small naming preferences
- Pure formatting issues that should be handled by tooling
- Refactoring ideas that are not necessary for the current Jira issue
- Comments requesting abstractions before duplication or complexity is real

## Project Context

This repository is a Kotlin Spring Boot backend for Manyak.

- Language: Kotlin
- Framework: Spring Boot
- Build tool: Gradle Kotlin DSL
- Runtime: Java 21
- Persistence: Spring Data JPA, Flyway, PostgreSQL
- Security: Spring Security
- Documentation: Springdoc OpenAPI / Swagger UI
- Operational health checks: Spring Boot Actuator

## Team Workflow

- Work should usually be tied to a Jira issue.
- PR titles should follow `[KNK-{issue-number}] {Tag}: {title}`.
- Branch names should follow `{tag}/KNK-{issue-number}-{branch-title}`.
- Business APIs should use the `/api/v1` prefix.
- Operational health checks should use Actuator endpoints instead of custom API endpoints unless there is a concrete requirement.
- Do not require Jira transitions, assignments, or comments from code review.

## Kotlin And Spring Guidelines

- Prefer Spring Boot defaults unless the project has a clear reason to customize.
- Keep boilerplate simple and boring.
- Prefer constructor injection.
- Avoid unnecessary framework abstraction.
- Avoid nullable types unless null is a meaningful domain state.
- Do not introduce global mutable state.
- Keep package structure consistent with the existing codebase.
- Do not add custom exception, response, or logging frameworks without clear need.

## API Review Guidelines

- Check whether new business endpoints are under `/api/v1`.
- Check request validation and error handling for user-provided input.
- Check status codes and response shapes for consistency.
- Flag accidental public exposure of endpoints.
- Do not require API versioning for Actuator health endpoints.

## Security Review Guidelines

- Flag real secrets, tokens, production passwords, or private keys committed to the repository.
- Local development defaults may exist only when they are clearly local and not production credentials.
- Check that sensitive endpoints are not accidentally permitted by Spring Security.
- Check authentication and authorization behavior when security configuration changes.
- Do not suggest weakening security only to make local development easier.

## Database Review Guidelines

- Flyway migrations should be append-only after being shared.
- Flag destructive schema changes unless the PR explains the migration strategy.
- Check entity and migration consistency when JPA models are introduced or changed.
- Prefer explicit database constraints for important invariants.
- Avoid comments about production-scale indexing until query patterns are known, unless there is an obvious missing constraint.

## Test Review Guidelines

- Ask for tests when behavior, persistence, security, or API contracts change.
- For configuration-only changes, smoke tests or context-load tests may be enough.
- Do not demand broad test suites for narrow boilerplate changes.
- Prefer comments that name the exact missing scenario.

## Documentation Review Guidelines

- Ask for README or setup documentation updates when local setup, environment variables, endpoints, or developer workflow changes.
- Do not ask to copy local wiki content into this repository.
- Files under `wiki/` should not be reviewed as part of this server repository.

## Comment Style

Use this format when possible:

```text
[severity] Problem: what is wrong.
Why it matters: the concrete risk.
Suggested fix: the smallest practical fix.
```

Severity labels:

- `[blocking]` for correctness, security, data loss, or broken build/test issues
- `[important]` for maintainability, missing tests, or likely production problems
- `[suggestion]` for optional improvements

Avoid comments that only say code is "cleaner", "better", or "more elegant" without a concrete project reason.
