# UI push guard repair implementation plan

> **For agentic workers:** Use superpowers:executing-plans with independent diagnostics under superpowers:dispatching-parallel-agents.

**Goal:** Correct reproducible push blockers and push the authorized UI commits without importing unrelated dirty workspace changes.

**Architecture:** Work from committed 693aafd6d in an isolated checkout. Keep checks strict: repair real implementation gaps, parser assumptions and stale generated facts at their sources. Never add blanket exemptions, fabricate API usages, change applied migrations, or bypass hooks. Preserve original staged blobs.

**Tech Stack:** Java/Maven, TypeScript/Next.js/uni-app, Vitest, Node/Python repository guards.

- [x] Reproduce clean committed baseline; classify actual defects versus checker errors.
- [x] Repair backend entity/enum/DDL/schema and inventory ownership checks; targeted red/green verification.
- [x] Repair ops endpoint reachability, feature gate declarations and endpoint permission attribution with real existing UI flows.
- [x] Repair controller validation, data-scope registration, role seed and message placeholders; verify targeted guards and relevant behavior tests.
- [x] Correct documentation links, factual counts and accessible SVG metadata.
- [ ] Regenerate registered artifacts, run full pre-push checks and necessary backend tests/builds.
- [ ] Review final changes, commit scoped fixes, preserve original workspace/index, and normal push to remote.

Status: implementation authorized by user's “继续” after the reported cross-module blockers. The two uncommitted marketing pages remain excluded.
