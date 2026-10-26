# JB-014 Pay Entitlement Events Implementation Plan

> **For agentic workers:** Execute this plan inline with test-first changes. Each phase ends with a focused verification and commit.

**Goal:** Persist pay-owned entitlement event facts transactionally and deliver them through the existing durable outbox pump, while providing idempotent pay inbox processing.

**Architecture:** Pay domain owns append-only `pay_ent_outbox` and idempotent `pay_ent_inbox` storage. A typed event contract and publisher validate allowlisted event data and persist only to the pay-owned table within `payTxManager`. After commit, a relay copies it to existing `sys_outbox` using the same event number; a recovery scan repeats the copy after process restart, and the bridge marks the pay row forwarded only after the shared outbox row exists. The stores use distinct transaction managers, so the bridge is idempotent rather than pretending to be one cross-domain transaction. Inbox application locks/deduplicates `(tenant, consumer, eventNo)`, rejects same-event/different-payload, and invokes the domain operation in that transaction.

**Tech Stack:** Java 21, Spring transactions, MyBatis-Plus, existing `OutboxEventBus`, Flyway/MySQL, JUnit 5.

**Spec:** `docs/technical/任务表-活动卡券储值-Java后端.md` JB-014; `docs/technical/TDD-活动卡券储值-数据库设计.md` `pay_ent_outbox` and `pay_ent_inbox`; `docs/technical/TDD-活动卡券储值-代码结构与类清单.md` `PayEntitlementEventPublisher`.

## Global Constraints

- Pay owns entitlement records; core activity orchestration must not write pay tables.
- Event and business changes share one transaction; delivery is after commit through the durable outbox.
- Delivery is at-least-once; consumers must be idempotent.
- Event payloads contain only explicitly allowlisted identifiers and business facts, never plaintext codes, full credentials, or sensitive tokens.
- Existing `sys_outbox` delivery semantics remain unchanged for current callers.
- Migrations are additive and production databases are not accessed in this work.

## Review Focus

- Rollback must leave neither pay outbox nor system delivery event.
- Exact event replay must remain a no-op; same event number with changed payload must fail closed.
- Dispatcher redelivery/restart must not duplicate the pay event or consumer effect.
- Unknown event type and forbidden payload fields must be rejected before either outbox write.
- Inbox insert and business callback must commit or roll back together.

### Task 1: Pay event and inbox persistence

**Files:**
- Create: `backend/shop-app/src/main/resources/db/migration/V482__pay_entitlement_event_outbox_inbox.sql`
- Create: pay-domain entitlement event contract, outbox/inbox entities, MyBatis mappers, publisher and inbox processor.
- Create: `backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/entitlement/event/PayEntitlementOutboxRelay.java` for after-commit forwarding and restart/poll recovery.
- Modify: `backend/shop-store-mybatis/src/main/java/ai/neargo/shop/event/OutboxEventBus.java` and mapper for a stable event number overload.
- Test: `backend/pay/pay-domain/src/test/java/ai/neargo/shop/pay/entitlement/event/EntitlementEventFlowTest.java`; shared `OutboxEventBusPumpTest`.
- Modify generated test schema and entitlement native verifier fixtures for V429.
- Modify task table and class/API/database design documents with actual evidence.

**Interfaces:**
- `PayEntitlementEventPublisher.publish(PayEntitlementEvent event)` accepts only typed events with stable `eventNo` and writes under `payTxManager`.
- `PayEntitlementOutboxRelay.relayPending()` idempotently copies durable pending events into `sys_outbox`, then marks source rows forwarded.
- `PayEntitlementInboxProcessor.process(tenantNo, consumer, eventNo, payload, action)` computes the hash itself, executes `action` once inside the caller's pay transaction, and rejects changed replay payloads.
- `OutboxEventBus.publish(eventNo, DomainEvent)` preserves the supplied event identity; existing `publish(event)` remains compatible.

- [x] Add tests for transaction rollback, event-number replay/conflict, typed payload allowlist, inbox callback idempotency/conflict, stable shared outbox insertion, and restart recovery.
- [x] Run focused tests and verify the transaction/replay failure cases before implementation.
- [x] Implement additive migration, entities/mappers, typed publisher/inbox processor, and stable event-number outbox bridge.
- [x] Run focused tests, V429 native MySQL verification (4/4), full clean backend suite (27 modules; 3,604 report-total tests, 0 failures/errors, 4 skipped), generated-doc and SQL guards.
- [x] Update JB-014 task evidence and commit the task files.

**Implementation notes:** Relay code lives in pay-domain under `pay.entitlement.event`. Bridge status `SENT` means durable insertion into shared `sys_outbox`; the shared pump owns final delivery. Inbox payload hash is computed from the received typed data inside pay, never accepted as a caller-provided digest. Design/code review found no actionable findings.

**Implementation commit:** `d1e260aba` (task-table evidence commit follows).
