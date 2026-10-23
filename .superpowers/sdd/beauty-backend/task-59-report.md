# Task59 design audit report

Status: **DESIGN_READY_FOR_ROOT_CHECK**. DESIGN_ONLY; implementation is not released.

Design: `.superpowers/sdd/beauty-backend/task-59-design.md`.
Base: `317c1f7c78259e170f9c182b7f785991aad861f5`; accepted Task58 product `ea2ead5c63f4830de566fdb3dcdda4b0a32424a9`.
Sole worktree `/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex`, branch `codex/beauty-foundation`.

## Outcome

Completed a source-audited, executable Task59 design: 18 public routes, 19 new bound SERVER routes, four reverse B checkout routes, complete real Service signature/DTO/permission/scope/idempotency matrix, explicit deployment Bean table, exact neutral binding/error/lifecycle types, canonical052/054 replay algorithm, native/TCP selectors, closed owned surface and frozen migration audit. Existing original160 target numbers remain; arrival challenge and preexisting recovery are separate increments. BM05 and `canStart=false` remain closed.

Only the two requested design/report files were written. No product/test/SQL file changes, builds/tests, native resources, staging/commit/push/merge/deployment. No subagents/helpers/reviewers were spawned. Initial and final tracked diff show only root-owned progress.md, which was not touched. The new Task59 files are ignored by repository rules; `git check-ignore` confirms both, so ordinary git status does not list them. They exist on disk and are ready for root review, not staged.

## Audited facts and file:line evidence

All paths below are relative to the sole worktree. `B=backend-plugins/shop-industry-beauty`; `J=src/main/java/ai/neargo/shop` as in design.

| Fact | Source evidence | Design consequence |
|---|---|---|
| Actual WorkOrderService method spelling is submitServices | `B/beauty-api/J/beauty/service/WorkOrderService.java:12`; `B/beauty-core/J/beauty/service/impl/WorkOrderServiceImpl.java:57` | Exact existing interface preserved; no invented submit method. |
| Guest2/reception5/submission4/bill2 actual interfaces | `B/beauty-api/J/beauty/service/{GuestRegistrationService.java:9,ReceptionService.java:15,ServiceSubmissionService.java:9,WorkOrderBillService.java:9}` | Five Remote services cover delivered methods only; no BM05 placeholders. |
| Appointment OPEN real local but unsupported remote default | `B/beauty-api/J/beauty/service/AppointmentService.java:30`; `B/beauty-core/J/beauty/service/impl/AppointmentServiceImpl.java:66` | Implement real Remote OPEN; remove fallback; update full constructor callers. |
|052/054 share operation kernel but distinct endpoint fingerprints | `B/beauty-core/J/beauty/service/impl/WorkOrderCoordinator.java:80`, `:87`, `:422`, `:447`; `ReceptionPreparation.java:58` | Minimal canonical052 correction, preserve full054 validation and legacy intent/derived keys; no migration. |
| Source authorization and parent checks already real | `B/beauty-core/J/beauty/policy/ServiceSubmissionAccess.java:39`; `WorkOrderServiceImpl.java:43`; `WorkOrderBillServiceImpl.java:579` | Transport cannot relax BOOKING/CUSTOMER/ORDER/conditionalPREPARE or trace parent chain. |
| Operation query/recovery already dispatch schema2 | `B/beauty-core/J/beauty/service/impl/ServiceOperationQueryServiceImpl.java:37`; `ServiceOperationRecoveryServiceImpl.java:45` | New bound Remote routes retain old appointment/reception/source semantics and public160 path. |
| Machine auth has token, no nonce/session token | `backend/shop-base/J/svc/InternalHttp.java:28`; `InternalServiceResponses.java:12`; `backend/shop-base-auth/J/auth/DelegatedIdentityContext.java:15` | Reuse machine auth/finally; state actual limits; no second auth system/C impersonation. |
| HOST actual token and scope lifecycle | `backend/shop-base-auth/J/auth/AbstractTokenAuthFilter.java:75`; `SecurityUtils.java:31`; `BizContextFilter.java:95`; `store/DbTokenStore.java:151` | Real token-store/revocation namespace tests at public ingress; no stronger cross-process session guarantee claimed. |
| Current C and cash require actual HOST session | `backend/shop-core/J/trade/policy/CheckoutAccessPolicy.java:51`, `:142`; `trade/api/mp/MpCheckoutController.java:45`; `trade/api/biz/BizOfflineReceiptController.java:50` | No machine confirmation/place/payment/cash. Actual C and cash routes remain HOST-owned. |
| CheckoutInternalApi existing5 B routes lack full request echo | `backend/shop-base/J/spi/trade/CheckoutInternalApi.java:13`; `backend/shop-app/J/portal/internal/InternalCheckoutController.java:24` | Add four bound methods alongside old routes; Remote uses only Task58-used four; other4 Port methods explicitly reject. |
| Source nullable/current observations are real DTO contract | `B/beauty-api/J/beauty/dto/ServiceSubmissionVO.java:8`, `SourceObservationVO.java:6`, `SourceBillVO.java:6`, `CurrencyBillVO.java:4` | Exact typed null serialization/presence validation, no default money0, historical BOUND independent of current UNKNOWN/BLOCKED. |
| No-core HOST and private SERVER bean boundary exists | `B/beauty-channel/J/beauty/config/BeautyChannelAutoConfiguration.java:38`; `BeautyMerchantClientConfiguration.java:33`; `B/beauty-web/pom.xml:14`; `backend/shop-app/pom.xml:322` | New Remote beans follow same condition; SERVER-only real reverse checkout; default/disabled/ops/no-api negative assembly. |
| New error number and actual i18n output locations verified | `backend/shop-base/J/common/ErrorCode.java:1311` ends BEAUTY_SOURCE_CUSTOMER_REQUIRED80106; `backend/shop-app/src/main/resources/i18n/messages{,_en,_ar}.properties` | Proposed80107 available; exact owned paths corrected before ready. |
| Exact generator outputs verified | `scripts/gen-beauty-api-design.py:11`; `gen-beauty-java-method-design.py:12`; `gen-beauty-java-project-design.py:11` | API清单, Java类与方法清单, Controller-Service-Mapper方法清单, Java项目结构与类职责 and existing SVG; source edits then generation. |

The design's §10 extracts exact existing record components/source lines for 27 reused public/nested VO types. It is an audited inventory, not duplicate code or a claim future candidate methods exist. Task54–58 source is authoritative over old JSON candidate names.

## Rulings and alternatives

- Root confirmed preserving authorized GuestRegistrationVO.claimToken and ArrivalChallengeVO.code issuance/replay. Incoming private proof echo remains prohibited. Design retains redacted diagnostics and no credential/transport-envelope persistence.
- Root accepted design scope for minimal052/054 canonical correction and conservative asymmetric equivalence. New canonical row stores original endpoint/fullhash evidence; original054 exact replay does not re-demand expired proof; minimal crossURL matching uses frozen shared base. Additional054 assertions after052 conflict. Either legal race winner is accepted; exactly one new intent/order. Legacy double aliases conflict without effects, not arbitrarily selected.
- Recommended bound protocol uses neutral typed records and HMAC of complete canonical request under existing internal-token. It avoids plain contact digest guessing and does not claim nonce authentication. Strict serializers write nested null explicitly even with NON_NULL host mapper; contextual typed decoder refuses omitted primitive/null keys. Retail mapper is unchanged.
- Recommended error metadata is one opt-in extension: two validated headers on actual non2xx business errors, two optional ServiceCallException fields. It preserves old callers and distinguishes bound ownerNOT_FOUND from route404. No response body logging/parsing, retries or new auth. This exact extension remains subject to root technical approval with the overall design.
- Simpler bareVO reuse was rejected because it cannot prove full scope/payload echo. A duplicate business state machine or persisted session/nonce design was rejected for scope/authority costs. Remote C purchase/cash was rejected because real current HOST session authority is required.

## Root draft-check issues closed

1. Full nested null serialization is an explicit dedicated bound serializer/tree mechanism, with NON_NULL roundtrip and missing primitive/nullable negative selectors; not just envelope JsonInclude.
2. AppointmentItemReq.qty uses existing strict COUNT deserializer; decimal/string/missing/null quantity fail. Unknown nested fields and explicit primitive revision presence are covered.
3. Exact claimRevision is positive actual persisted owner value; adapter does not synthesize2.
4. Spring pure JSON decoding may precede controller token check. Token precedes any delegated authority/owner/persistence effect. Shared BoundDelegatedCalls has exact lifecycle and fixed route/scope checks.
5. The authoritative invokeBound signature uses lazy Supplier<String> digest. Blank-token constructors remain valid in default retail; invocation401 before digest/owner, with explicit assembly/test selector.
6. Native standalone is frozen as two child JVMs, exact HOST/SERVER fixture source paths, separately resolved classpaths and no-core/no-owner assertions. It uses real owner Beans and real TokenStores, not FilteredClassLoader evidence disguised as physically separate runtime. Packaged JAR deployment remains Task60.
7. Exact generated filenames, i18n paths, main classes and80107 availability are verified; no placeholder remains for these items.
8. Quote expiresAt presence/type is required, but no client future-expiry policy is introduced for owner-allowed historical confirmed/CREATED/terminal original reads or recovery. Original-key challenge replay preserves its original expired credential. AC5/10 name exact positive historical-expiry transport selectors alongside missing/malformed timestamp negatives.

## Acceptance and carried limits

Design §6 maps AC1–20 to exact new selectors and provider boundaries, §9 names serialization/blank-token supplemental selectors and six execution units. The native runner is a new Task59-only strict allowlist and serial self-owned resource harness; it does not silently fix old Task57 M1. It uses cached mysql:9.7.2/normal disk64M/colima-hxkey-tests, MemAvailable>=524288KiB, random loopback/UUID, env secrets and finally-owned cleanup. Native XML must be fresh, nonzero and0skips.

No tests/builds ran in this design phase; all RED/GREEN/ablation commands/selectors are planned. Task60 packaged artifacts/config/process deployment/upgrade/whole-branch review remain unclaimed. Old FQN/store-job/DOMAINS/parser/V506/SQL-plugin/registry/label/generated/warning debts and Task57/58 M1 are explicitly carried.

## Read-only completion verification

- HEAD checked against DESIGN_BASE; no branch/index mutation.
- Source signatures, DTO record inventory, exact conditional/config locations and permission constants read from actual files.
- Every formal beauty V1–8 and platform539/540 bytes/SHA256 read and recorded in design §8; no SQL execution or old-byte change.
- Document consistency review removed DRAFT status, ambiguous standalone harness, wrong output paths and eager-digest signature; complete design is ready for root technical gate.
- `git diff --name-only` shows only root-owned progress.md; `git check-ignore` confirms the two design documents are ignored. No other workspace file was written.

Next permitted action is root technical review. Product work waits for a separately supplied IMPLEMENTATION_BASE; no generic user permission request is needed.
