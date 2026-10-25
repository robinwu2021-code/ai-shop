# Task61 BM05 design-only audit report

Status: READY_FOR_INDEPENDENT_DESIGN_REVIEW; R1 requires root architectural reconciliation before financial implementation. BM05 business remains closed.
Baseline observed at entry: `01c5aa64b6a656d7857b8a260797d54943621354`, branch `codex/beauty-foundation`; workdir `/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex`. Entry working tree clean. BM04 module gate/I1 conclusion read from mainTDD §2.37/§2.38/§32.24; no rerun or deployment claim.

## Delivered scope

Only `.superpowers/sdd/beauty-backend/bm05-design.md` and this report written. No product/mainTDD/ledger/SQL/config/ADR/schemaJSON edits, evidence directory, subagents, stage/commit/push/merge, tests/build/generator/install/native/containers/remote/deploy. Writes used narrow escalation for these authorized paths outside default root. Read-only file searches, excerpts, git status/rev and SHA256 were the audit method.
Read CLAUDE, local dev standards/Java, brainstorming/writing-plans and verification-before-completion. User automatic/no-new-question instruction and exact two-file task scope override generic skill plan locations/commit/builder dispatch; root independent review follows. Existing root-owned `progress.md` modification appeared during work and was not touched.
Design covers brief answers1–8: actual owners §2; financial/source authority and contrary writers §4–5; actual occupation/journal §6; task/quantity/closure §7; permission/protocol/API §3/§8; schema §9; slices/exact first task §10; release/deployment §11. No generated API/Java/schema change claimed.

## Findings verified against current source

- Canonical active permission resolver exists and returns accountNo outside caller TX; exact MCH/USR namespaces and ambiguity predicate are already in OperatorAccessMapper. RemoteMerchantOperatorAccessPort currently only implements allowed(), so inherited resolveAuthorizedOperator is empty. Proposed own-person provider resolves locally at merchant owner and does not export accountNo.
- V532 already owns unique account links and link/person versions; no new personnel identity ledger is needed. PersonnelFactsPort deliberately excludes account identity. Own-person fact remains observation; it does not grant service permission. Existing booking codes and role defaults do not authorize actual service.
- All eight ResourceCommandService.Action/ResourceSourceAuthorityPort.Binding actions are booking-only; ResourcePlanCodec expects booking plan; current owner head is hold-specific. Frozen V535 nonblank CHECK cannot be used to pretend occupation protocol exists. No actual occupation/block tables or ResourceOccupationPort/ServiceTaskService implementation found.
- Core source() is an RR observed snapshot with real funding IO before op lock and two mapping retries. Hash includes economic/status/refund fields, not row versions/new grants. It requires ORDER_VIEW. Design therefore uses purpose-scoped Core execution authority for SELF technician, not old source() as permission or technician money export; grant usage is not subtracted from net source quantity a second time.
- Actual class is AfterSaleSlots, not root draft ActualAfterSaleSlots. AfterSaleSlotMapper CAS and StatusCasMapper.lockOrder serialize suborder; preparation/result/points/ledger/transition writers are listed in design §5. Generic managed completion remains blocked in both policy and CAS.
- Core suborder lock alone misses actual Pay preparation. Both RefundPaymentStore.prepare and PaymentLedgerServiceImpl.refund lock original stl_payment under independent payTxManager. RefundCapacityLedger uses same global original receipt and includes legacy refund rows lacking originPaymentNo. Protection must see Pay refund state before Core projection catches up, retain late-success truth and prevent new unbound managed refunds.
- Pay migrations share platform Flyway despite separate transaction manager; actual SQL max platform/Pay V540, beauty V8, food V1, inventory V13, job/report V2; Java platform migration max V397. Proposed V541/V542/V543 and beautyV9 are design-only candidates, not allocated/applied files.
- Quantity refinement: WorkOrderCoordinator copies item.qty, but WorkOrderPreparation.loadDefinition requires qty1; matching/unassigned preparation expands request repetitions. ADD calls preparation.unassigned and explicitly inserts BigDecimal.ONE. Frozen groups are appointmentItemNo:requirementNo:unitOrdinal with Demand capacity qty1. Raw V1/Mapper decimal/multiple COUNT capability is not proof of public support; starts reject unsupported legacy shapes. Task→appointment→arrangement/preview→hold→group→unit proof is required.
- Acceptance has no actual segment; existing Task/Step/Participation/Submission fields and CAS are reusable but not business implementation. Same grant remains across legitimate steps/handover; new segment versions are separate from immutable binding/quantity. Partial done does not infer refund/void/closure. BAPI155 already exists and receives execution projection only.
- Personnel/resource/schedule maintenance currently fences pending booking commands, counters/units/open pointer; new occupation/block/fences must join those guards. Direct merchant account/role revocation is not covered by personnel locks and cannot be represented as global session-lock atomicity.

## Decisions, limitations and next gate

R1: recommend Pay-owned Core line/unit protected budget and permanent managed-source marker, shared with BOTH cash refund lanes; reject unbound legacy managed refunds after completion and preserve performed protection. Allow legitimate unserved refund only with exact Core authoritative unit/paid-share approval. First grant after any existing refund history stays explicitly unsupported until reconciliation extension; no automatic paid Boolean. Alternative requires proof of all reachable Pay lanes already enforcing immutable Core authorization; current source does not provide it. Root must reconcile this cross-owner design/ADR/mainTDD/API/Java/schema before S1.
S0 recommended first: purpose-scoped current authorized account→effective linked person with existing resolver, no DDL/no canStart enable. Exact paths/types/query/permission labels/AC test names/finite native runner and ablation are in design §10. S0 uses existing owner opaque503 and beauty remote80107, existing ObjectProvider<Clock> convention, current combined reactor; no unresolved human preference or silent role grants.
Later rare service-dispute adjudication, post-refund start admission, and advanced workflow policy stay explicitly closed and need their own bounded owner contracts, not speculative DTO inventory. Native runner/tooling described in plan is not executed or proven by this audit. Design is ready for independent review, not coding release; root reconciles exact first task and true sources after review.
No tests, compile, runtime, DB lock race, migration replay, HTTP topology or financial device validation performed. Static source inventory is not dynamic proof. Formal deployment remains after all P1–P4 code/test/review/compile; preserve BM04 M1/M2/M3 and old Flyway/compiler/loader warning boundaries.

## Read-only source pins

Full paths below are relative to the sole worktree; each hash is SHA256 of actual current bytes, not a class/runtime hash.

| Source | SHA256 |
|---|---|
| `backend/shop-base/src/main/java/ai/neargo/shop/spi/user/MerchantOperatorAccessPort.java` | `86e711c238413df8ed29d1bb6a183b3f576ad8cf31616bbec7fa8b089f67fb72` |
| `backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/port/MerchantOperatorAccessPortImpl.java` | `8b2f6ea2374f5e3609d6d224deb53f395acb4404958bc410823e4eff9403b6ce` |
| `backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/mapper/MerchantMappers.java` | `c021c3e1f29c533bfeb6e2d0e5a96285d5460ed84afa441512583b20c7c518e7` |
| `backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/service/impl/PersonnelServiceImpl.java` | `5e3a324e0100b8e313ab48cd4ab5e7eb28fb5038b6b4ea10d0061ef0fb2cef06` |
| `backend/shop-base/src/main/java/ai/neargo/shop/spi/resource/ResourceSourceAuthorityPort.java` | `39221ac0c4f44f19e6eae126f03271127732e7f880e94797bafe6c0e4f68dd5e` |
| `backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/service/impl/ResourceBookingServiceImpl.java` | `ca94743df304b4ce319e62c9759045633f6552b8ed94097d874cb5c12eb66c96` |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/CheckoutServiceImpl.java` | `d8c13a71b874469abbfa8237fc6ac15dda45a5e14daf5610efc9575f2ad3d672` |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/mapper/StatusCasMapper.java` | `605eaaebf15dc6ca300f2f5ef0a84ff87341b31b0a41413a347429ae0c8e5911` |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/mapper/AfterSaleSlotMapper.java` | `562e6ee7735db35606a758f7726845febaab64e2f21f68bf0c9b36d629a25f6b` |
| `backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/service/impl/RefundPaymentStore.java` | `04f57b4d0f6fcf38e50ffa560b4fc062747530faf26287118eb95d77418c9ded` |
| `backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/service/impl/PaymentLedgerServiceImpl.java` | `0de338a430712c6ba0efd15dc280c66ff4d23ed21da7f059ae0b1e0cd157101a` |
| `backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/service/impl/RefundCapacityLedger.java` | `7e6b2f09f809f8a2e80b16c028869a8a69cd4b1e866753447f3ae94815b375c9` |
| `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/WorkOrderPreparation.java` | `2ed9e900773578229ff521e9400700642bb74e9e0ff152d6ca6b1fd199c97aa2` |
| `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/ServiceSubmissionServiceImpl.java` | `b0a68a54c365ceac3ea128a0a0cab3b7ddb5cca2eafef378678d76e19fc7ca76` |
| `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/mapper/BeautyMappers.java` | `11577a6d91e727fe03a6b5a7a670ad317d65cc953e4c54d5cd1ecb30b8ec034b` |

## Frozen migration pins

| Frozen file | SHA256 |
|---|---|
| `backend-plugins/shop-industry-beauty/beauty-core/src/main/resources/db/industry/beauty/V1__beauty_p1_tables.sql` | `ac2a539664c42fdb7e4893f1359879fc5affbe4b24f95aae1809cd25f11061a4` |
| `backend-plugins/shop-industry-beauty/beauty-core/src/main/resources/db/industry/beauty/V2__beauty_appointment_resource_fence.sql` | `5b6768928018b68635c9c081b200a2cb6202d40645c5db62c91c558cc49d4114` |
| `backend-plugins/shop-industry-beauty/beauty-core/src/main/resources/db/industry/beauty/V3__beauty_appointment_business.sql` | `c8d54df07cabc5b0903e0dd4fba73e2b7fc4aa75539a148837cc5837deaa8da7` |
| `backend-plugins/shop-industry-beauty/beauty-core/src/main/resources/db/industry/beauty/V4__beauty_appointment_resource_decision.sql` | `7df4e8d2e3aeeb57bf1e704004957d996912a17021c5f58842ac91152e7a003a` |
| `backend-plugins/shop-industry-beauty/beauty-core/src/main/resources/db/industry/beauty/V5__beauty_reception_work_order.sql` | `8e9aec144736ee3502fb9c0f9c4c95edf6f4a02c1cecbd597ff0c3b51aa4eab5` |
| `backend-plugins/shop-industry-beauty/beauty-core/src/main/resources/db/industry/beauty/V6__beauty_reception_identity_guard.sql` | `220ed777c56a77c22cf835f2a5bcc42ccb41cc98a4348831df06e0e404b89f20` |
| `backend-plugins/shop-industry-beauty/beauty-core/src/main/resources/db/industry/beauty/V7__beauty_appointment_assignment_units.sql` | `8894786048bf04ca45eff129a83fa7776da867994d72dff2343a91b215d6d3f2` |
| `backend-plugins/shop-industry-beauty/beauty-core/src/main/resources/db/industry/beauty/V8__beauty_source_observation.sql` | `c5b54d574624a5265366cf4bf7897ec949d107bd302f171539c461a89a9d8343` |
| `backend/shop-app/src/main/resources/db/migration/V532__shared_personnel_foundation.sql` | `299c7ed3dc22f7b5a0510949956e685c702d74b88a606b5a92004ca1cb7d579b` |
| `backend/shop-app/src/main/resources/db/migration/V533__shared_resource_identity.sql` | `2b41485b9b032ffa00b74b5cec31bfaf774a9024f465e3e2d5ec149759c43aff` |
| `backend/shop-app/src/main/resources/db/migration/V534__shared_schedule_rules.sql` | `f52c7ee04d31b4d407287e7cf6b200d9a70b308289053ed403996cf25a130997` |
| `backend/shop-app/src/main/resources/db/migration/V535__shared_resource_booking_foundation.sql` | `0518429c19edf0f411aefb2e1c296c2df6aab72e93137699ccef0fd54a9dd9c0` |
| `backend/shop-app/src/main/resources/db/migration/V539__checkout_source_foundation.sql` | `5a8e346341e5b378403761f2a77555c0558ff6b27a6071584207a9d8f9e14d3b` |
| `backend/shop-app/src/main/resources/db/migration/V540__checkout_payment_operation.sql` | `ca76b6512cf79c6f25fa71a4441f533e03cc609c6320611a0dcdc9634a10f745` |

Design bytes SHA256: `5c69bf7c3086b1c29da5d8f89211c05f42014711934afc680625bcae39eb2905`. Final verification is limited to these readable files, stated scope and baseline/hash checks.


## Task61 design fix round 1/5 — I1 (2026-10-25)

Status: I1 corrected in the design; ready for the same reviewer's narrow rereview. Only `bm05-design.md` changed and this report section was appended. No product implementation, application test, build, native SQL execution, generator, dependency install, remote access, or git write was performed. The test names below are future S0 acceptance requirements, not executed results. Future quantity/dispute policies and the S1 Pay decision remain at their existing gates.

Before editing, original bytes were copied to `/tmp/task61-design-r1-before/bm05-design.md` and `/tmp/task61-design-r1-before/task-61-design-report.md`; Python byte comparisons confirmed equality. Original design: 57491 bytes, SHA256 `5c69bf7c3086b1c29da5d8f89211c05f42014711934afc680625bcae39eb2905`; original report: 12831 bytes, SHA256 `76610ef072c6fa78237ff53636fc8fccc606a25dcc66d2795dbf96f3a58c5a65`.

I1 correction: freeze `validFrom<=at AND (validTo IS NULL OR at<validTo)`; explicitly nullable validTo represents indefinite assignment validity. A LINKED wire fact must include validTo, accepting explicit JSON null but rejecting a missing property before typed conversion loses presence. Owner mapping and remote validation both require non-null observedAt, non-null LINKED validFrom, `validFrom<=observedAt`, and, for a finite end, `validTo>validFrom AND observedAt<validTo`; the owner's returned observedAt equals the SQL predicate's server time. Malformed observations fail closed, never become UNLINKED. Legitimate UNLINKED observations do not require a personnel interval.

Named future tests: `PersonnelOperatorFactsIT#indefiniteAssignmentReturnsLinked` exercises a real V532 NULL-ended row through the canonical owner/SQL mapper; `#finiteAssignmentBoundaryIsHalfOpen` covers finite boundaries. `RemotePersonnelOperatorFactsPortTest#explicitNullValidToRoundTripsAsLinked` uses bound local HTTP and checks explicit null preservation; `#missingValidToAndInvalidIntervalObservationsAreUnavailable` rejects omitted end, invalid finite ordering and invalid observedAt/validFrom. Existing finite expiry, ambiguity and expiry ablation remain required; ablation removes only the entire null-aware upper-bound conjunct, retaining the lower bound.

Read-only commands/checks actually run from `/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex`:
- `cat .superpowers/sdd/beauty-backend/review-task-61-design.md`: read I1 and the nonblocking future gate comments; targeted `rg`/`sed` reads located the S0 contract and existing report.
- `sed -n '58,76p' backend/shop-app/src/main/resources/db/migration/V532__shared_personnel_foundation.sql`: confirmed `valid_to DATETIME(3) NULL` and `CHECK (valid_to IS NULL OR valid_to > valid_from)`.
- `sed -n '708,719p' backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/mapper/MerchantMappers.java`: confirmed existing effective SQL uses `a.valid_from <= at AND (a.valid_to IS NULL OR a.valid_to>at)` (XML-escaped lower comparison in source).
- `diff -u /tmp/task61-design-r1-before/bm05-design.md .superpowers/sdd/beauty-backend/bm05-design.md`: exit 1 as expected for a changed document; inspected only the S0 interval/protocol/test/ablation edits. A subsequent read-only `python3 - <<'PY'` check used pathlib/hashlib/re/difflib and `git rev-parse HEAD` to assert the final scope and pins; exact output: `PASS: 11 I1 documentation assertions; 204 design lines; diff confined to original lines 177-196; original report unchanged; 29 source/migration pins matched; HEAD unchanged.` This is document verification, not executable feature coverage.
- `shasum -a 256 .superpowers/sdd/beauty-backend/bm05-design.md`: final design SHA256 `e1108df7a3eb316fbab941bd8a5364f5750a612486ad373ccbadc322015df314`, also confirmed by the final Python check. This supersedes the original design hash above; HEAD remains `01c5aa64b6a656d7857b8a260797d54943621354`.
