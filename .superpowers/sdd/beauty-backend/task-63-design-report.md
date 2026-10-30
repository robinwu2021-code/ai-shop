# Task63 design report

Status: **READY_FOR_SCOPED_REREVIEW** — Task63 fix round1/5, review I1. Design only; no product implementation or execution claim.
Baseline: `4dc45d9da8201dbb439fe95e76e2f3627db031da`, `codex/beauty-foundation`, sole worktree `/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex`.
Deliverables: `bm05-offline-core-design.md` and this report only. Read CLAUDE, local dev standards/Java and Task63 requirements; applied bounded brainstorming/design under explicit no-questions/no-subagents task scope. Tier2 design; root owns main TDD/ADR/API/Java/schema/ledger reconciliation.

## Decisions and first release

- Root provisional R1/R2/R3 accepted: dedicated Core manual PAID with immutable provenance and financial exclusions; genuine typed USER/GUEST common-order extension in M2; identified M1 retains actual C purchase consent with no Pay setup/routing prerequisite. Independent design review and root contract reconciliation remain required; no outstanding user clarification or further root ruling was identified.
- M1 is a consumed Core capability: B manual quote, existing actual C confirm/place, cashier receipt, durable stock completion, query/source, bound local/remote providers; service start remains closed until resource chain is real. Exact classes/routes/records/DDL candidate/permissions/AC methods/finite reactor commands are in design §§3/9/10.
- M2 delivers genuine guest cash service and existing beauty binding/bill integration; M3–M6 deliver real unit reservations/resources/work/fulfillment; M7 delivers concrete BM06 task/resource calendar. Task62-S0 is reused, not redispatched. BAPI155 is extended, not duplicated. Full core cannot be declared complete at M1.
- Existing EntitlementServiceOrderServiceImpl creates ord_ent_service_order and asset/preview/consent source facts; it is not the ordinary order/item/source-line writer and is not safe to repurpose for manual cash. Reuse common product/store facts, not asset proof or that endpoint.

## Audit findings affecting implementation

1. Checkout prepare requires a real user, confirm/place actual C; V1 shared order/suborder, V411 creation intent and V539 checkout buyer all reject NULL. Guest support needs real owner DDL and conditional identity/query/idempotency contracts; no guestNo/cashier user fallback.
2. source() performs funding IO before the source lock and requires successful receipt before usable lines. Manual observation gets separate owner revision/hash and typed state; old financial UNKNOWN/hash/epoch stay intact. No fake PaymentFact.
3. Ordinary OFFLINE timeout already excludes WAIT_OFFLINE_PAY. Old confirmOfflinePay and PaymentLifecycle local shortcut reject managed service. markPaid also triggers inventory/card/member/quota/settlement/events; new manual transition cannot merely remove its receipt check.
4. Four-layer payment selection and additional merchant collection quota/points preflight are entangled with ordinary pricing. Manual mode is server-derived at quote creation and follows a dedicated business-purchase policy; SKU/catalog/qualification/risk/price checks remain, payment configuration/routing checks do not become prerequisites. Global old policy remains unchanged.
5. Automatic readers matter: paidSubOrdersSince feeds FundInvariantReconciler automatic settlement repair; settleSourcesOf/readiness and notPaidOrders feed financial readers/I8 repair. Explicit manual exclusion/anomaly behavior is required before PAID is emitted, including filter-before-limit tests. Manual paid events do not invoke generic paid/award/settlement consumers.
6. I1 correction: the initial design incorrectly treated successful void confirm return as stock completion. Actual InventoryStockPort lifecycle2 only queues ALLOCATE, and StockPortImpl may return normally for missing/released locks. Revised §3.1 requires a new, implemented owner-backed StockDispositionPort with exact original authority/lifecycle/manifest/operation and complete real owner rows. Pending command/SENT/normal return never admits; only matching APPLIED plus complete unreleased ALLOCATED/CONSUMED disposition does. Original supported PLATFORM/DUAL/INVENTORY lifecycle1/2 remain; no guessed no-stock branch.
7. Existing resource actions do not implement occupation. Task61 typed journal/group/unit/no-doublecount/partial-hold/open-all-future/cleanup/overrun/maintenance and own-person restrictions remain explicit; Pay protection is deferred only for immutable new manual sources, never bypassed for funded sources.

## Verification scope

Only read/search/hash and the two permitted Markdown writes were performed. No tests, build, generator, native/container, install, git write, stage/commit or deployment ran. Future acceptance/ablation commands in the design are proposals, not results. Read-only migration rescan found platform V540 and beauty V8; bounded I1 inspection found inventory V13. Proposed platform V541–V544/beauty V9–V10/inventory V14 were not created. Root must recheck maxima and inspect exact new migration bytes/hash before first native use. Prior evidence and old migrations remain untouched by this task.
Source fingerprints below pin inspected owners; root priority-document amendments can coexist in this shared worktree and are not this task's edits. No full BM04 re-audit or test replay was performed.

## I1 repair scope and exact release changes

Read the full independent I1 finding and only actual stock allocator/providers, inventory consumer/reservations and owner persistence. Root provisionally accepted original owner outcome rather than narrowing away normal inventory configuration. Full service/guest/calendar sequence and R1–R3 are unchanged. No further ruling requested.
Design §3.1 specifies new StockDispositionPort confirmOriginal/observeOriginal records, frozen manifest and original operation, provider selection from persisted authority, real row proof by lifecycle, pending/failure/no-op/replay handling and the exact new/modified file inventory. Metadata extends existing prd_stock_lock/inv_reservation/lines, never a new stock ledger. Confirmation-operation marker activates tracked checks and prevents retroactively attaching authority to unrelated completed rows. Existing void signatures/commands remain compatible; supported allocation algorithms are reused with strict tracked mutation outcome checks.
M1 now explicitly includes inventory V14 and platform V541 stock metadata plus Core disposition snapshot fields; both require actual first-file byte/hash gates. The bounded reactor explicitly selects :shop-app,:shop-inventory,:beauty-channel, uses separate owned platform/inventory schemas and asserts current actual provider/classes/mappers. New ManualStockDispositionIT covers v2 delayed delivery, failed/expired outcome, missing/released platform no-op, already-confirmed replay without second deduction, exact manifest coverage and frozen authority/config changes including DUAL. The fixed ablation admits PENDING and must fail the real pre-consumer admission assertion, then restore exact source and recompile. These tests and ablation are specified, **not run** in this design-only repair.
Ready for the same reviewer's scoped I1 rereview; implementation remains blocked on that design gate and root contract release, not on a user clarification.

## Source pins (SHA256 of actual current bytes)

I1 additional stock owner pins:

| Path | Methods/facts | Bytes | SHA256 |
|---|---|---:|---|
| `backend/shop-app/src/main/java/ai/neargo/shop/invbridge/port/InventoryStockPort.java` | I1 lock/toLines/confirm(v2) enqueue | 9091 | `9560c4f69b8ddc05d73148eb19e642df2b52c5ed68a113c6da3adb240b96d993` |
| `backend/shop-app/src/main/java/ai/neargo/shop/invbridge/port/DualWriteStockPort.java` | I1 platform authority/confirm/mirror | 14540 | `a564af89a7e096af83c3419d050c80f62ede3deb150df0cb9cb7e59670d27535` |
| `backend/shop-core/src/main/java/ai/neargo/shop/product/port/StockPortImpl.java` | I1 lock/confirm/forEachActiveLock no-op | 18185 | `1757bf5a0b2cf1aca35653f269310e4b65603cdf643da84e801415af97b8aa27` |
| `backend/shop-core/src/main/java/ai/neargo/shop/product/entity/PrdStockLock.java` | I1 original lock fields and states | 1940 | `0b38fc09e7361851bbc96f78b2073863cc18f03d8d432161cbf06e2509441b9a` |
| `backend/shop-app/src/main/java/ai/neargo/shop/invbridge/InventoryLifecycleCommand.java` | I1 original lifecycle command | 583 | `145c343bc2104c422fa380d5466572f7f5e32c635e7aa1b822b49e2168e5cf7d` |
| `backend/shop-app/src/main/java/ai/neargo/shop/invbridge/InventoryMirrorConsumer.java` | I1 consume ALLOCATE | 11705 | `3cfacc8e52d8192918db938de808f922a7bdb459f7aa66c2d7424e92369d6c3f` |
| `backend/shop-inventory/src/main/java/ai/neargo/shop/inventory/service/impl/ReservationServiceImpl.java` | I1 reserve/commit/allocatePaidByRef/expireOverdue | 22265 | `77bd72ee8e4f903b286d976be3d7ca1f91f5c8d8d2ffd86a8b024fa7ebc68b96` |
| `backend/shop-inventory/src/main/java/ai/neargo/shop/inventory/entity/InvReservation.java` | I1 original owner/ref/lifecycle | 1046 | `b2c36c62f1ea2728d89156ef923b6438f207f6ff85ff8fd24d8bc0e1478f1e77` |
| `backend/shop-inventory/src/main/java/ai/neargo/shop/inventory/entity/InvReservationLine.java` | I1 immutable original quantity and issue/release | 727 | `3d1517caaca047cce58e47a736a42c4d276dd7aea888df4990ce465a912e6695` |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/create/OrderStockAllocator.java` | I1 actual reserve invokes StockPort.lock | 1633 | `f51b4d9bd05f7aa6e7d3159facc0662c0d933a5ae6bdb71c1b39622e014565a3` |
| `backend/shop-inventory/src/main/resources/db/inventory/V12__paid_allocation_and_fulfillment_issue.sql` | I1 actual lifecycle2 migration | 703 | `b6be4f7de36a8a18c262e6074d758ba8bd40b8613cab78e357187ce45f7651e8` |
| `backend/shop-inventory/src/main/resources/db/inventory/V13__return_receipt.sql` | I1 inspected inventory maximum | 1040 | `1ccb5d4adcde174723e809eb04dd4c9a8b78df3b5847b247b582063aced0a87a` |

Initial design owner pins (unchanged source bytes):

| Path | Methods/facts | Bytes | SHA256 |
|---|---|---:|---|
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/CheckoutServiceImpl.java` | prepare/confirm/place/source/sourceFunding | 69673 | `d8c13a71b874469abbfa8237fc6ac15dda45a5e14daf5610efc9575f2ad3d672` |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/OrderServiceImpl.java` | checkoutPricing/createConfirmedFor/doCreate/requirePayModeSupported/markPaidSource/cancel/timeout | 275065 | `9bbaef1932486c62dfe60d1f013b0a9970e781a2ccb9e3ce67fed261c2717846` |
| `backend/shop-core/src/main/java/ai/neargo/shop/product/service/impl/PayModeServiceImpl.java` | availablePayModes four-layer policy | 6424 | `3df98da88802ac2b5aafd3c3348e26d891d46690c9afae1419577d8eac189c33` |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/MerchantOrderServiceImpl.java` | confirmOfflinePay/confirmOfflinePayLocked | 83763 | `916ce219185f2c42dc7f857552069088842e01f12a100cc7c2b5c7d1aa7304d3` |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/OfflineReceiptServiceImpl.java` | confirm/payments.confirmOffline | 20006 | `7a26e6b6226eb996969a2ad045cfa6adac6881b206120fa833fd1eb4d602a76e` |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/lifecycle/impl/PaymentLifecycleServiceImpl.java` | readConfirmed/requireConfirmed | 4778 | `d48a4e5abcb0587b27c09e471fe9a96f0aa45993fc1f7faecf248715fb1b1052` |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/mapper/StatusCasMapper.java` | lockOrder/casPayment/casOrder | 4423 | `605eaaebf15dc6ca300f2f5ef0a84ff87341b31b0a41413a347429ae0c8e5911` |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/mapper/AfterSaleSlotMapper.java` | occupy/release | 1091 | `562e6ee7735db35606a758f7726845febaab64e2f21f68bf0c9b36d629a25f6b` |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/aftersale/AfterSaleSlots.java` | occupy/tryOccupy | 1883 | `9cfc880f17f4b3f3a67fd7986239242796f54d7b34153f02bfa87c64506ebca8` |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/refund/service/impl/RefundPreparationServiceImpl.java` | prepare/freezeSource | 8939 | `869cbf43b124e915f8d2dce881b1c12f52a652c2ce32c7d3f54242d59769a2d4` |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/port/SettleSourcePortImpl.java` | paidSubOrdersSince/settleSourcesOf/readiness/notPaidOrders | 16105 | `8834d518b0f1b3aac9de3b77f9352e4822588f701a461d928ef1b44fafb04881` |
| `backend/shop-app/src/main/java/ai/neargo/shop/paybridge/FundInvariantReconciler.java` | scan automatic bill repair | 3645 | `cd23132e38e316bcc3804e4ed3e4c5a9123bf539ae48fe43127ffb7b7ed679b9` |
| `backend/shop-app/src/main/java/ai/neargo/shop/paybridge/OrderPaidReconciler.java` | reconcile automatic markPaid | 6698 | `9465a8800a22a13ab7e10e41d307746af34acf20bff30c26a52e1410141ef73d` |
| `backend/shop-app/src/main/java/ai/neargo/shop/paybridge/OrderPaidReconcileJob.java` | scan/run | 4795 | `3ac9968e2bd507235e98e44c12ad496925fa145fe335a0266b6856e497af04c9` |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/EntitlementServiceOrderServiceImpl.java` | createInternal/bindPreview asset owner | 25110 | `a5165e97918595d7f1c2772ce96824ab2b528b82f2d247964f1dbb6af850c556` |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/dto/ServiceOrderCreate.java` | member reference request | 1014 | `f0240956999288ed471a162f6c2c472cdeaadd416f13a9080ee7eee9455e7706` |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/dto/ServiceOrderIdentifiedCreate.java` | real user/pay locator | 791 | `61ccfbd21061b8ed8a11d2aa787fdfa369e228f00fa547a0f3f9c8b46404f884` |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/dto/ServiceOrderSnapshot.java` | asset/use/decision recovery | 1925 | `f8cfc0ea74263f78e0409008bc085e1b376cc77f3cef270d383e3f4b438b59eb` |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/create/CheckoutQuoteGuard.java` | context locked quote | 11010 | `555020eee1a147a59b30d18443dd570482a9b482934f5a2d0a21a48363828526` |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/create/CheckoutPricingScope.java` | ordinary pricing scope | 525 | `454d8c98cb77d8b6be4054b4d6d67e5a1ef6ab225b6802ad40133db8dbf2c7b1` |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/create/ExpectedCheckoutQuote.java` | quote proof | 410 | `b353dd326ed2a54c1f5887abfcb07b9b946f012ed637b4926d1257b4648fb8d1` |
| `backend/shop-base/src/main/java/ai/neargo/shop/spi/trade/CheckoutPort.java` | source/financial DTO | 18669 | `e7b9b5fe8960acccd6abd8d9001a3718aaea864495e2eecec9dab182b3bad54b` |
| `backend/shop-base/src/main/java/ai/neargo/shop/spi/member/GuestRegistrationPort.java` | register/get/verify/claim | 1544 | `18fc743c2fc7965aed9bfdc186249d689028fc9629aee0c7b694ca59ab071e71` |
| `backend/shop-base/src/main/java/ai/neargo/shop/spi/product/StockPort.java` | void confirm; no query receipt | 9458 | `43789d95c4593a5fe0b9c049a11a10f7a0d4ca9808abe4099dc9ed73570396d8` |
| `backend/shop-base-auth/src/main/java/ai/neargo/shop/auth/BizPerms.java` | pay:cash:receive and five service codes | 15365 | `c40958f4f0674475b82e7bb4aa235016a01c11560ca7dca008d17dcef0d4cf8c` |
| `backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/port/AdmissionPortImpl.java` | ordinary order/fulfillment risk admission | 13899 | `bf70e1980f9528c9ae512f22e9eb20b2cfacb4c7ca31df3c818253b36afa2b8c` |
| `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/ServiceSourceObservation.java` | Canonical/observe/history/epoch | 19816 | `1d904c9fdb5367ecc77caaa9b3a27c5b81e304bdaf5b0cad989c367a0f654df5` |
| `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/policy/ServiceSourceFacts.java` | money verified receipts | 6690 | `c967c338d8c44e8dc753f64c382c6f258a9d62880e31d229225069745eab3e5f` |
| `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/ServiceSubmissionServiceImpl.java` | ADD/BIND source path | 57594 | `b0a68a54c365ceac3ea128a0a0cab3b7ddb5cca2eafef378678d76e19fc7ca76` |
| `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/WorkOrderQuery.java` | detail projection | 15032 | `ff0c833722340c46b6d5e78f2385b2eb0ee90e523589ac57eb3f965720cf1ab2` |
| `backend/shop-app/src/main/resources/db/migration/V1__baseline.sql` | order/suborder user NOT NULL | 81750 | `c8699fd01c8c1739867b7691adcc531f26abd9cce31d6ad8bb4c94d314d22958` |
| `backend/shop-app/src/main/resources/db/migration/V411__order_creation_recovery.sql` | intent user NOT NULL/key | 1003 | `1d9c5d79c8cd71bb1c048f9fa9bde3e87ff377217ade927c8c9f8f685d6c7852` |
| `backend/shop-app/src/main/resources/db/migration/V537__shared_guest_registration.sql` | guest/claim constraints | 3163 | `9c93c44dc328b4cbf47be8baae6972743e0922aca1a0cf62b84906905b4f0b0f` |
| `backend/shop-app/src/main/resources/db/migration/V539__checkout_source_foundation.sql` | buyer NOT NULL/source lines | 8444 | `5a8e346341e5b378403761f2a77555c0558ff6b27a6071584207a9d8f9e14d3b` |
| `backend/shop-app/src/main/resources/db/migration/V540__checkout_payment_operation.sql` | platform max/original payment | 7477 | `ca76b6512cf79c6f25fa71a4441f533e03cc609c6320611a0dcdc9634a10f745` |
| `backend-plugins/shop-industry-beauty/beauty-core/src/main/resources/db/industry/beauty/V8__beauty_source_observation.sql` | beauty max/financial lineage | 4582 | `c5b54d574624a5265366cf4bf7897ec949d107bd302f171539c461a89a9d8343` |
| `scripts/verify-personnel-operator.mjs` | current reactor/native/report precedent | 15061 | `ddd29f7e3e9fa4523049189717727ef5a7263d6271cb6020e61af77ff55b7994` |
| `.superpowers/sdd/beauty-backend/bm05-design.md` | Task61 resource/workflow; Pay deferred | 59578 | `e1108df7a3eb316fbab941bd8a5364f5750a612486ad373ccbadc322015df314` |
