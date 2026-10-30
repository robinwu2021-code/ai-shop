# Task64 M1 implementation report

Status: **DONE** — implementation and authorized finite verification complete; root independently reviews before committing. Only `/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex` on `codex/beauty-foundation` was edited. BASE `03a4a70482bf480210eff759bae4830ab653096d`. No git mutation, install, deployment or original restaurant worktree edit occurred.

## Delivered behavior

- A persisted `MANUAL_OFFLINE` lane uses real current C buyer confirmation/place and the existing shared goods, prices, common order/suborder/item/checkout-line owners. Manual service preparation works without Pay configuration. No fake C account, guest-as-user, Pay receipt, points, quota, funded ledger or ordinary paid event is manufactured.
- Current authorized HOST cashiers or exact token/route/purpose/request-digest verified bound CONFIRM calls record positive exact CASH/OFFLINE_TRANSFER declarations. Both actual canonical account resolutions must agree; the immediate pre-transaction check covers current cashier and manual-confirm permissions, entity/store/account and original scope. Full actor/account/order/operation namespace protects request-key replay. Different key/payload/amount/currency/time conflicts fail closed. Legacy delegated cash denial is unchanged.
- Receipt durability and stock admission are separate: dedicated conditional parent PAID/child FULFILLING transitions record manual provenance, declaration/action evidence and manual-only events. Receipt can remain STOCK_PENDING; only genuine original APPLIED+ALLOCATED/CONSUMED evidence admits. `canStart` stays false.
- Actual selected original stock authority, lifecycle, complete manifest and confirmation identity are frozen. Real platform lock owners and inventory reservation/balance/outbound owners prove affected mutations and replay stability. Inventory v2 must complete actual queued consumer allocation/issue before admission. Missing/released/expired/partial/failed/unavailable originals never become usable admitted source lines. DUAL remains platform-authoritative; current configuration cannot reinterpret old originals. Real nullable inventory aggregate `disposition_revision` advances under the reservation root.
- Recovery runs through the existing bounded indexed job slice and ordered original roots/CAS. Unchanged STOCK_PENDING observations reschedule `nextRetryAt` without incrementing admission revision, rewriting canonical observation pairs, or emitting false action/event changes; an actual two-original limit1 test proves a permanently unavailable first original does not starve the second real provider result.
- Manual source observations use fresh RR snapshots and include parent/child revisions plus stable sorted checkout mapping and original item identities/versions. Their manual revision/hash remain separate from the legacy financial pair; own operation version and timestamps do not self-invalidate observations. UNKNOWN/unadmitted views expose no usable lines. Bound responses validate shape, domain consistency and exact request operation/scope/ref/receipt correspondence.
- Financial payment/offline/markPaid/after-sale/refund preparation/result/execution/common locked paths reject manual orders, including imported contradictory rows and existing execution replays. Settlement excludes manual rows before LIMIT. Unexpected Pay success stays anomalous. Local financial contamination is rejected with existing CHECKOUT_PAYMENT_CONFLICT before stock IO and under roots/RR; recovery suspends only that original using ADMISSION_CHANGED/reason SOURCE_MODE_CONFLICT while preserving genuine stock fields. No new ErrorCode or financial feature was added.
- Public/internal controllers, remote provider configuration, bound context helper, existing framework job and three locale permission labels are wired. Default roles gain no permissions. Local Service interfaces declare consumed signatures without inheriting SPI Port; proper existing adapters remain in dedicated port packages and the architecture rule is unchanged.

## Exact change and migration inventory

[Final candidate inventory](task-64-evidence/pre-green/green-native-inventory.json) records all **69 owned product/test/runner paths**, **82 source/fixture pins**, **195 compiled class pins**, exact finite methods, compile command and source origins. Root-owned design/metadata/generator/ledger files were not edited by the implementer. [Self-review](task-64-evidence/pre-final/self-review.md) covers the complete own diff and concrete corrections. No new library, infrastructure or extra public DTO/route beyond the consumed M1 design was introduced.

Both applied migrations are frozen byte-exact since first native use:

| Migration | Bytes | SHA256 |
| --- | ---: | --- |
| `backend/shop-app/src/main/resources/db/migration/V541__manual_service_admission.sql` | 7022 | `9d01ae07c112cfdb350f4950c88fe7bfba8297be2053b26f97961f31175f9400` |
| `backend/shop-inventory/src/main/resources/db/inventory/V14__original_stock_disposition.sql` | 935 | `0a84cc624f3dbd7a58eb38067eefa74778a79cc47d34d4127ba751fd3f4c8095` |

V541 adds persisted admission/provenance/manual-observation fields, original platform stock metadata and dedicated declaration/action tables with constraints. V14 adds nullable original inventory manifest/aggregate disposition revision/operation and original line SKU/store metadata. Strong production `ck_manual_admitted` remains intact; no migration was weakened for mutation testing.

## Final verification

Final native evidence: [green-fdcc25ca-8fd4-46e0-8ae4-b3a186e9e1fc](task-64-evidence/green-fdcc25ca-8fd4-46e0-8ae4-b3a186e9e1fc/), actual nine fresh XML reports: **39 tests, 0 failures, 0 errors, 0 skipped**, runner exit0. This is the final gated source/class candidate, including architecture separation and bounded recovery. Native execution used current canonical-relative reactor modules `:shop-app,:shop-inventory,:beauty-channel`, Java21, existing offline cache, actual MySQL9.7.2 and distinct platform/inventory schemas; actual injected target types, canonical code sources and datasource metadata were asserted. No H2 fallback, installed implementation jars or stale `.m2` code were used.

| Final native suite | Tests |
| --- | ---: |
| BoundDelegatedCallsTest | 3 |
| CheckoutCashPermissionFlowTest | 1 |
| BoundInternalProtocolTest | 7 |
| ManualServiceAdmissionIT | 10 |
| ManualStockDispositionIT | 6 |
| ManualServiceAdmissionProtocolTest | 2 |
| ManualServiceFinancialIsolationIT | 4 |
| BizRoleSeedTest | 3 |
| RemoteManualServiceAdmissionPortTest | 3 |

Additional affected checks:

- Full unchanged ArchitectureTest: **17/0F/0E/0skip** after the exact seven-path separation correction. Actual initial17/1F and corrected XML/logs are both preserved. BackendI18nParityTest: **6/0F/0E/0skip**, unchanged messages required no replay.
- Existing finite Node runner checks: **6PASS**; existing cash permission label checks: **9PASS**. Final all27 new Java files pass actual google-java-format1.25.2 AOSP dry-run with supported `--skip-reflowing-long-strings`; all new Java and added old Java lines <=120. Old-file formatting stays limited to intended changed hunks/imports. `git diff --check` passes.
- Final current default23-module testCompile passes (15.249s after the isolated fixture-key correction). Full native current default reactor also proves current retail/default source compatibility; no duplicate default build was added.
- Current food compatibility testCompile passes with Java21/offline cache and an own UUID canonical aggregate of current backend, beauty and food; selected modules `:shop-app,:shop-inventory,:beauty-channel,:food-core`, profile `food-embedded`, no install. [Commands/log](task-64-evidence/post-final/commands.json), [food log](task-64-evidence/post-final/food-compatibility.log). [Post-compat comparison](task-64-evidence/post-final/after-compat-comparison.json) verifies all82 source and195 compiled class pins still exactly match the final gated candidate.

## Preserved diagnostic and mutation evidence

Counts are tests/failures/errors/skipped. Earlier passing phase manifests remain identified by their actual source version; they are not relabeled as final.

| Phase evidence | Actual counts | Interpretation |
| --- | --- | --- |
| [red-b71b7299-b7bb-4110-9e11-a9d981ab7a0e](task-64-evidence/red-b71b7299-b7bb-4110-9e11-a9d981ab7a0e/) | 34/16/1/0 | Target prepare own assertion accepted RED; overall diagnostic includes typed protocol test error. |
| [green-83e176a2-e9b5-488d-b942-cbdc8a13ec06](task-64-evidence/green-83e176a2-e9b5-488d-b942-cbdc8a13ec06/) | 38/10/0/1 | Incorrect injected-provider setup whitelist, imported refund closure fixture and missing native flag; no clean GREEN claim. |
| [green-aeb4bb18-2b82-4594-ad43-586b696dd99f](task-64-evidence/green-aeb4bb18-2b82-4594-ad43-586b696dd99f/) | 38/0/0/0 | Accepted pre-source-observation correction GREEN. |
| [ablation-7f9df4cc-8d5d-4243-93a3-9a8648e70cd6](task-64-evidence/ablation-7f9df4cc-8d5d-4243-93a3-9a8648e70cd6/) | 1/1/0/0 | Sole actual before-LIMIT settlement filter deletion; exact mixed-page own assertion fails; runner exits0/restores. |
| [restored-184a7f5d-2c29-4f04-8ae5-b30494c97d2b](task-64-evidence/restored-184a7f5d-2c29-4f04-8ae5-b30494c97d2b/) | 1/0/0/0 | Focused original filter case passes after exact source/class restore. |
| [green-5edb31a5-5745-40c6-8843-e09cffdfe483](task-64-evidence/green-5edb31a5-5745-40c6-8843-e09cffdfe483/) | 38/0/1/0 | Applied DB action constraint correctly rejected invented action kind; corrected to existing ADMISSION_CHANGED + reason. |
| [green-6b33e2c7-f2f8-436f-b893-0d21194f2cf2](task-64-evidence/green-6b33e2c7-f2f8-436f-b893-0d21194f2cf2/) | 38/0/0/0 | Source-version/contamination corrections pass, before later architecture/recovery correction. |
| [green-9782696d-e116-4754-8829-b2ebe11f1cbb](task-64-evidence/green-9782696d-e116-4754-8829-b2ebe11f1cbb/) | 39/0/1/0 | New two-original test reused buyer confirmation key; fixture error before recovery assertions. |
| [green-fdcc25ca-8fd4-46e0-8ae4-b3a186e9e1fc](task-64-evidence/green-fdcc25ca-8fd4-46e0-8ae4-b3a186e9e1fc/) | 39/0/0/0 | Final gated candidate; all39 pass, including real two-original bounded recovery. |

The sole accepted mutation removed only the actual `SettleSourcePortImpl.paidSubOrdersSince` manual-mode exclusion before LIMIT. Exactly that source/class changed, its real mixed-page assertion failed, original source bytes and full class provenance were restored and the focused original case passed. Root independently verified the two-path mutation/restoration. Later source observation, financial contamination, service architecture and bounded recovery corrections are disjoint; no repeated mutation/restored ritual was run.

Compiler/import formatter failures, typed-reply error, initial native setup expectations, action-schema mismatch and the second-original request-key collision remain real unsuccessful diagnostics. None were relabeled clean GREEN or business RED. Historical checkpoint report, all source/class inventory generations, actual XML/commands/logs and original/restored snapshots are retained. Food helper's logical `/tmp` versus canonical `/private/tmp` preflight assertion happened before Maven; its diagnostic and empty owned-temp cleanup are preserved separately, then the canonical helper ran successfully.

## Cleanup and limits

Every native phase used an own UUID/label, loopback port, cached `mysql:9.7.2 --pull never`, bounded resource precheck/readiness/termination, shared native lock and environment-only scrubbed secrets. Final actual cleanup records owned container removal, temporary removal and original source restoration; final shared lock and own temporary directory are absent. Only owned resources were removed. Food's own UUID reactor was removed, and its post-compile source/class comparison is clean. No VM mutation, unrelated container/cache cleanup, deployment or git mutation occurred.

Reduced V538/V13 structural fixtures plus actual V539/V540/V541 and V14 are **not** a full historical Flyway upgrade claim. All472 fixture input pins and source origins are retained. Existing annotation-processing/deprecation notes remain visible without suppression. Formal deployment/integration remains with root; Paychain, M2 guest and later service execution are deferred. No unresolved consumed M1 implementation gap is known after final self-review and authorized verification.
