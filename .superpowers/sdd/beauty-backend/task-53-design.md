# Task53 BM03 deployment design

Grade 1. Spec: task-53-brief.md and task-53-deployment-notes.md. Controller owns main TDD/progress.

## Acceptance and architecture

- Preserve BM01/BM02 historical harnesses. New independent BM03 harness runs copied embedded, channel-only HOST, private SERVER, default retail artifacts and the existing job-worker artifact; record SHA256 and JAR contents.
- Standalone HOST has beauty-web/channel but no core. SERVER has core/channel but no web. Reserve localhost ports before launch; HOST targets BEAUTY, SERVER targets PLATFORM. Instrument infrastructure forwarding proxies for real reverse source calls and dropping one committed owner response. Embedded must be stopped before standalone business proof.
- Each process has one primary datasource. Private SERVER database contains formal beauty V1–V4/23 private tables and required infrastructure tables only. Platform uses native schema-test fixture (not historical platform migration replay); worker uses its own framework database/migrations.
- Real B/C authentication, dynamically derived UTC future slots, immutable UNRESTRICTED catalog, real personnel/schedule setup and real owner/source providers. Exercise availability/preview/create CONFIRMED/list/detail/overlapping change/conflict retaining old/cancel/noShow; current revocation and second-manager recovery; lost response and restart using durable original key. SQL verifies facts, not booking actions.
- Stop source SERVER and invoke the captured owner command: owner must fail closed without a local source provider. Archive-only source events prove durable dedup, not notifications delivered. Overall readiness can remain 503 for BM04–06.

## Scheduling reuse

Extract the existing JobHandlerEndpoint implementation to a common abstract target endpoint in shop-base; retain the retail class and add a thin server adapter. Share existing registry wiring from shop-base and JDBC ShedLock wiring from shop-store-mybatis using common configuration imports. No shop-app dependency in beauty-server and no private timer. Add an api-profile JobDeclaration for beauty-appointment-recovery (every minute, timeout 60 seconds, lock 120 seconds); worker's existing start-disabled guard remains unchanged. Exact job routes use framework token; no actor/session is fabricated. Existing recovery scanner only reconciles original terminal facts; authority-dependent work remains pending.

## Implementation/verification plan

1. RED: declaration discovery under api and absence under ops/no-api; target security + actual shared run/lock behavior. Extract common target wiring and add declaration, then run affected shared/beauty tests and an ablation.
2. Build changed shared dependencies offline into /tmp/codex-beauty-bm01-m2, then beauty modules, four copied profile artifacts and job-worker using Java 21. Inspect actual archive contents and hashes.
3. New harness/helper: RED infrastructure lost-response test; native fixture, strict formal schema/upgrade gate, real authenticated booking lifecycle, source-owner calls, restart/fault/revocation/manual recovery and actual framework discovery/cron run/restart records. Disposable cached MySQL, localhost ports, TCP readiness, finally cleanup, complete logs/result.json.
4. Run scoped native concurrency/protection and impacted retail/food/config/protocol regressions. Fix concrete runtime defects with reproducing RED tests. Reconcile README, functional matrix, domain objects, DB dictionary/structure, ADR043; baseline160/49 unchanged, private23 and public +1/machine30 explicit.
5. Review own diff, commit only owned files, provide report/commands/evidence/limitations for controller review. Task53 is not complete until independent task and module reviews.
