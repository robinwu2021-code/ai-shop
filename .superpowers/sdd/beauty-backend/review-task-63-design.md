# Spec Compliance

- ❌ Issues found: I1 blocks the required actual-owner, recoverable manual admission contract. The design promotes a successful `void StockPort.confirm` return to verified stock completion, although a supported provider only enqueues the effect. See `bm05-offline-core-design.md:43`, `:124`, `:127`.
- ⚠️ This is a design gate only. Production behavior, future tests, generated contract reconciliation and deployment are not verified. Task62 remains accepted and was not retested.

## Strengths

- `bm05-offline-core-design.md:8`–`:11`, `:31`–`:34`: the dedicated manual path preserves common order ownership, distinguishes actual merchant receipt from Pay evidence, and derives immutable mode from the persisted operation rather than a request flag.
- `bm05-offline-core-design.md:49`–`:63`: contrary financial writers and automatic PAID/paid_at readers are explicitly included, with settlement filtering before pagination and no generic paid events. Legitimate manual PAID is not incorrectly prohibited.
- `bm05-offline-core-design.md:67`–`:71`: actual C purchase and genuine shared USER/GUEST order contracts are separate; guest delivery remains mandatory, with scoped nullable identity, distinct in-person purchase authority and historical buyer preservation.
- `bm05-offline-core-design.md:74`–`:92`: source-unit grants, revoke/start recovery, original resource proofs, permanent handoff linkage, maintenance roots, acceptance without start, participation history, task CAS and separate Core fulfillment ACK remain required. The no-Pay choice does not remove resource correctness.
- `bm05-offline-core-design.md:108`–`:118`, `:129`–`:149`: the full service/calendar sequence, specific test assertions, bounded current-source reactor, migration hash gate and meaningful ablation are documented without claiming executed or deployed evidence.

## Issues

### Critical

None identified in this bounded design review.

### Important

**I1 — A normal stock-confirm return is not authoritative completion evidence.**

- Design locations: `bm05-offline-core-design.md:43` explicitly admits after a successful `StockPort.confirm(orderNo,lifecycleVersion)` return; `:124` and `:127` reuse this as original-effect recovery; `:135` does not require the supported asynchronous/no-op cases to remain closed.
- Actual evidence: `backend/shop-app/src/main/java/ai/neargo/shop/invbridge/port/InventoryStockPort.java:119`–`:125` publishes `InventoryLifecycleCommand(ALLOCATE,...)` for lifecycle 2 and returns without observing allocation. The current ordinary order writer assigns that lifecycle from configuration to the stock needs and child at `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/OrderServiceImpl.java:2185` and `:2248`; the manual design has not excluded this reachable configuration. Separately, `backend/shop-core/src/main/java/ai/neargo/shop/product/port/StockPortImpl.java:190` and `:335` process only LOCKED rows, so normal return alone cannot distinguish completed originals from missing/released locks.
- Consequence: the pending stock effect can still be unprocessed, fail later, or have no eligible reservation when Core records ADMITTED and later grants service. Replaying the same void command preserves command identity but does not establish its outcome. This contradicts the requirement for real-provider evidence and UNKNOWN recovery without guessed readiness.
- Required fix: define a bounded, owner-backed stock disposition for M1. Either prove a supported no-stock service branch from authoritative frozen product/order facts, failing closed for unsupported stock-bearing cases, or specify the real original stock operation's authoritative terminal result/observation contract, including provider/lifecycle selection, immutable line/quantity identity and durable recovery. Do not bypass actual stock or fabricate a receipt. Command acceptance stays pending; only authoritative successful disposition can advance admission. Add exact real-provider tests for lifecycle-2 enqueue with delayed/failed outcome and normal-return missing/released reservation, plus replay after already-confirmed completion. Update the M1 path/DTO/DDL/test inventory and implementation report consistently before release.

### Minor

None raised; avoid expanding this repair into another broad audit.

## Assessment

**Task quality: Needs fixes.**

The proposed manual-order lane and full core sequence are coherent, but M1 is not implementation-ready while its stock completion gate uses a weaker provider contract than its admission semantics require. Repair I1 and review that changed boundary; this finding does not require reopening Task62 or the accepted resource design.

## Review evidence and bounded source checks

- Requirements read first: `.superpowers/sdd/beauty-backend/task-63-brief.md`; binding lens `/tmp/task63-offline-review-rubric.md`; task reviewer template and repository CLAUDE/dev standards/Java rules read. Implementer report treated as claims.
- Immutable package: `/tmp/task63-offline-design-review.diff`, SHA256 `8ea329390c0263a246a9bd3c07c934c8470b2c1e894c229b4e5be2760cefbc63`; baseline/current HEAD supplied as `4dc45d9da8201dbb439fe95e76e2f3627db031da`. No git commands run. Initial combined tool output was truncated, so the unseen design was recovered in bounded diff chunks; no changed-file reread was used.
- Named risk: PREPARE protocol target before an order exists. Checked existing `CheckoutPort.PrepareCheckoutReq` at `backend/shop-base/src/main/java/ai/neargo/shop/spi/trade/CheckoutPort.java:192` and bound PREPARE at `backend/shop-app/src/main/java/ai/neargo/shop/portal/internal/InternalCheckoutController.java:90`; the request already carries OperationRef, so no finding.
- Named risk: contrary cancellation/creation recovery could release stock after manual receipt. Checked `OrderServiceImpl.markPaidSource/cancelPayment/closeExpiredOrders/closeOne/closeUnpaid` at `:3017`, `:3459`, `:3524`, `:3553`, `:3610` and `trade/order/service/create/impl/OrderCreationRecoveryServiceImpl.java` register/enlist/claim. Current creation recovery recognizes an existing order before compensation; the design explicitly adds receipt/cancellation serialization. No separate defect inferred from the old implementation.
- Named risk: successful original stock confirm may mean acceptance/no-op rather than completion. Checked `StockPort` confirmation declarations, actual `product/port/StockPortImpl` and `shop-app/invbridge/port/{InventoryStockPort,DualWriteStockPort}`, and the current order lifecycle selection. Result is I1 above. Searches for two guessed service paths returned missing paths; actual implementations were then located. No additional source sweep was performed.
- No tests, builds, generators, native operations, dependency installation, git mutation, product changes or subagents. Only this report was written in the beauty checkout; original restaurant checkout untouched. Root's verified source pins were accepted without recomputation.
