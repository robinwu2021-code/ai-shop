# Task60 module gate preparation

Status: DESIGN_PREFLIGHT_READY. Execution order updated2026-10-24: Task60A code/quality closure precedes BM05; Task60B actual deployment is deferred to the final P1-P4 unified gate (main TDD §0.2). Task58 accepted ea2ead5c6; Task59 code accepted c9d951879 after I1/I2 scoped re-review. This checklist releases no product implementation; task-60a-brief.md plus a separate root dispatch authorize design/source audit only. Owner: root; original scope: main TDD Task60 and recorded progress ledger. Current artifact SHA must be rebuilt at the later deployment gate.

## Mandatory carried findings

- Task57 M1: reject unknown/empty/unsupported native selectors before provision; each recognized selected class requires fresh nonzero XML, zero skips. Existing successful selections do not prove invalid selector rejection.
- Task58 M1: ServiceSubmissionIT cross-action race must accept either legitimate SUBMIT_SOURCE or ADD_SOURCE winner, prove exactly one owned source submission and canonical immutable receipt matching the actual winner. Minor deferred outside Task58 Important fix loop; explicitly triage/fix before module acceptance.
- Architecture: old OpsPointsInspectionController FQN remains failing; move store.config.JobLockConfiguration into existing job.config in same module, update ShedLock/BeautyJobTarget imports, preserve unique Bean/disabled-no-DataSource/lock-token behavior. Register beauty in DOMAINS as business domain, then fix actual crossings; no infra exemption or ratchet relaxation.
- Native schema/SQL: old V506 single-space CHECK/parser boundary and shared AND parser; strict 12 no-entity-table handling; permanent SQL portability plugin coverage with same rules and deliberate bad-SQL negative, no rule/baseline weakening. Frozen migrations remain append-only.
- Business missing scenario: CLAIMED-to-USER plus real resource-owner REPLACE integration, not only fixture proof.
- Permissions/labels/generated debt: booking view/manage three languages and duplicate Chinese; old B common.delete/two STOCK_SHARED_SOURCE placeholders; old Ops role matrix eight rows and technical README four status/date drift; old B registry personnel assignments/account-links/status-changes and store-sales-catalog versions. Verify present state before editing; no broad automatic grant.
- Warnings: Task55D fault WARN versus environment notices need explicit triage. Flyway/Mockito/CDS/config warnings are recorded limits, not passing checks.

## Actual module acceptance evidence

1. Reconcile matrix/API/method/schema with real delivered owners, explicitly keep BM05 and unsupported actions closed.
2. Build current JARs and record SHA, configuration and launched commands: embedded, HOST without beauty-core plus private SERVER/schema, default retail, relevant pay/job and food impact.
3. Real current credentials and TCP: identity/guest claim, arrival or walk-in, one work order, quote, actual C confirmation/order/payment, current B cash only where authorized, source binding, nullable bill and original-number query/recovery. No test Bean/readiness in place of deployed providers.
4. Lost-response/restart/revocation/isolation negatives and old receipt/verification/auto-complete fences remain effective. Third-party payment channel may be isolated mock only; no external real funds.
5. Actual upgrade evidence states exactly which formal migration histories ran. Historical source money/identity and current UNKNOWN/BLOCKED are retained independently; no claim of full refund API if only controlled owner facts.
6. Native local cached mysql:9.7.2, normal DISK64M, colima-hxkey-tests/hxkey-tests, available-memory guard, serial own UUID resources and finally cleanup. Do not stop others or use shared/remote DB.
7. Independent BM04 code review and necessary scoped fixes precede BM05 coding; actual module acceptance remains pending until the final unified deployment gate. Update all three design reconciliations, README and main plan only with verified delivery states.

Evidence source for carried items: progress.md Task55-58 entries; review-task-57.md and follow-ups; review-task-58.md M1; main TDD section 2.22 Task60. Recheck exact file locations and accepted commits at dispatch; this is a preparation checklist, not a claim these defects are repaired.

## Read-only deployment harness audit during Task59 implementation

Root inspected existing scripts/verify-beauty-bm03-deployment.mjs and build-beauty-bm03-artifacts.sh as reuse references, without running them. Do not run the old harness unchanged for BM04: it provisions tmpfs and passes internal/job secrets in Java arguments; BM04 must use approved ordinary DISK64M and env/mode0600 config. Its HTTP evidence helper retains full non-login result objects: redact newly issued guest/challenge credentials, request proofs, payParams and private evidence before durable HTTP artifacts. Preserve authorized issuance to the actual caller; evidence/log redaction is separate. Old BM03 fixture/history/table counts and five artifact SHAs are historical only. Rebuild current shapes, verify new private ready8/28 and actual platform537-540/bootstrap history scope; no inference of full historical migration replay. Existing artifact-copy ordering, classpath manifests, own-PID/proxy cleanup and true login flow can be reused after current-code review. No deployment or tests were run by this audit.

## 60A source-audited code targets (not released during Task59)

- Actual package owner file: backend/shop-store-mybatis/src/main/java/ai/neargo/shop/store/config/JobLockConfiguration.java. Move within same Maven module to ai.neargo.shop.job.config. Production importers verified: backend/shop-app/.../config/ShedLockConfig.java and beauty-server/.../server/config/BeautyJobTargetConfiguration.java. Existing JobTargetProfileTest and JobHandlerEndpointTest cover lock presence and internal endpoint behavior; add exact singleton/disabled-no-DataSource assembly assertions before move, then preserve lock provider semantics.
- Actual platform domain registration: backend/shop-app/src/test/java/ai/neargo/shop/arch/ArchitectureTest.java DOMAINS currently ends logistics and has no beauty. Register beauty as business domain; run same ratchet-based domain checks with embedded profile so beauty classes are present. Fix real new crossings, do not add infra exemption or broaden known lists.
- Actual FQN debt: backend/shop-app/src/main/java/ai/neargo/shop/portal/ops/pay/OpsPointsInspectionController.java uses fully qualified PathVariable on voucherNo at currentlyline84; import normal annotation, no endpoint/permission change. Recheck current line/content at implementation.
- Payment selector debt exact runner scripts/verify-checkout-payment.mjs: beauty-source branch has regex class selector, default payment branch accepts env string then filters14 known classes. Empty/unknown/mixed selectors must be rejected before memory/Docker provisioning and each allowed selected class must produce fresh nonzero zero-skip XML. Tests must prove no spawn/provision call on invalid selection; no weakening existing pin/UUID cleanup.
- Source race debt exact helper ServiceSubmissionIT.raceEndpointKey(boolean changed, boolean added): asserts a single SUBMIT_SOURCE row after possible ADD_SOURCE competitor. Fix to check one legitimate winner across both actions, match its immutable endpoint receipt and exactly one prepare. Preserve conflict-count and same-request same-result assertions; do not force scheduler winner. This is a deferred minor from accepted Task58, not reopened Task58 Important review.

Read-only findings only; no product edits/test execution in this preparation. Latest Task59 accepted baseline and actual owner file content must be rechecked before60A implementation.

## Task59 review carried fixture quality

- M1 from review-task-59.md: BeautyBm04EmbeddedHttpIT merchant-token confirmation negative currently sends Map.of() and accepts any failure. Before final deployment, change fixture code to otherwise-valid confirmation for its prepared quote, assert specific wrong-realm authority failure and zero confirm/order/payment effects; retain separate malformed-body cases. This is60A code quality, not a request to launch deferred HTTP topology. Task59 R1 onlyI1/I2.
- M2 explicitly keeps existing deprecated-API/dynamic-agent/Flyway9.7-versus-verified8.1/duplicate-mapper warnings visible and attributed; no blanket suppression or pristine-output claim.
