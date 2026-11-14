# C01 / Task67 compatibility and literal-style closure

Status: **DONE_PENDING_REVIEW**. Coding, static review, and authorized compilation are complete. All test execution, runtime verification, ablation, deployment, and acceptance remain pending the unified phase. This report does not claim behavioral test success.

## Authorization and boundaries

- Followed `.superpowers/sdd/beauty-backend/task-67-compatibility-brief.md`, the compatibility preflight, local `CLAUDE.md`, `ai-shop-dev-standards`, and `references/java.md`. Classification: **0**, existing-contract bug compatibility and literal-only style correction; no API/schema/dependency/auth changes.
- Sole worktree: `/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex`; branch verified as `codex/beauty-foundation`. No work in the original restaurant workspace.
- The three initial SHA256 values matched root's saved `task-67-compatibility-base/manifest.json` before editing. Existing uncommitted Task67 sources were preserved; no reset, clean, checkout, index changes, commits, or subagents.
- Wrote only the three authorized existing source paths below and this requested report. Root's ledger, main TDD, metadata, and other Task67 changes were not edited. No additional source path was needed. Worktree writes and Maven target writes outside default writable roots used narrowly scoped escalation.
- User's code/compile-first override was authoritative over normal red/green and ablation workflows: tests were authored first but never executed. No unit/Node/loopback HTTP/MySQL/native/integration tests, probes, Docker, migrations, application startup, deployment, Pay runtime activity, or generators' test suites were run. The host reactor necessarily compiled existing Pay dependency modules; no Pay source was edited or Pay flow invoked.

## Source-backed shape and correction

Actual `backend/shop-core/.../ManualServiceAdmissionServiceImpl.java` was read, including `source` (467 onward), `sourceView` (975 onward), and both `view` overloads (1078 onward). The parent-null path calls `sourceView(current, null, null, "AWAITING_RECEIPT", List.of(), false, null)`. Its honest pre-order response carries absent order/suborder, zero order/admission revisions, no due amount, CNY, no source lines, and an awaiting view whose provenance/declaration/method/amount/receipt times are null and whose operation state is `AWAITING_RECEIPT`. Observation revision/hash remain the operation's originals; the genuine pre-order fixture has zero/null. The existing-order/no-declaration path supplies the parent's known due and order revision, retains an awaiting view, and observes the source normally.

Both current authoritative record constructors in `backend/shop-base/.../ManualServiceAdmissionPort.java` were read. `ManualAdmissionView` enforces paired references and empty receipt fields for awaiting state; `ManualSourceFacts` enforces source/view correspondence and source-line/state constraints, but permits missing due in a declared tuple. The adapter therefore retains its own strict declared-order due check and adds no parallel records or copied model classes.

`RemoteManualServiceAdmissionPort.sourceDisplay` now:

1. Preserves the old-provider `declaration == null` path unchanged and explicitly incomplete.
2. Preserves exact operation, order, suborder, order revision, admission revision, and currency correspondence before considering either current shape. Existing scope/request/suborder predicates and signed internal binding/identity validation remain unchanged.
3. Allows absent-order current responses only with null due, zero observation/order/admission revisions, null observation hash, awaiting source and receipt states, and no receipt reason. Authoritative constructors continue to reject nonnull awaiting declaration fields, inconsistent references, and nonempty awaiting source lines.
4. Retains a mandatory known due for existing orders, including legitimate created-awaiting responses, and exact declared/due correspondence when a declaration amount exists.

The returned record is unchanged: no manufactured zero due, party revision, order, authority, financial/bank proof, or readiness is introduced. Source/receipt states are not globally forced equal, preserving the owner's valid existing-order `UNKNOWN` observation with its original declaration.

## Exact owned source deltas

All paths below are relative to the sole worktree. Delta counts compare the saved C01 baseline, not HEAD (which already contains accepted uncommitted Task67 changes).

| Source path | C01 delta | Final SHA256 |
|---|---:|---|
| `backend-plugins/shop-industry-beauty/beauty-channel/src/main/java/ai/neargo/shop/beauty/port/RemoteManualServiceAdmissionPort.java` | +22 / -7 | `f01c2194eedbddfe7924b101222e992724d7c65b21b0f9c3c5861a6e0aac07b1` |
| `backend-plugins/shop-industry-beauty/beauty-channel/src/test/java/ai/neargo/shop/beauty/port/RemoteManualServiceAdmissionPortTest.java` | +287 / -0 | `36d7c6b30405a1b30c388b76a1089d28fe3409ae5256a1d283a3332c55915ffc` |
| `backend/shop-app/src/test/java/ai/neargo/shop/scenario/ManualSourceBindingIT.java` | +4 / -2 | `058cd57591026580e7bc7dc906b4f0f7b17613006c7fdf61f7854ca907875bfc` |

Native test changes are exactly the two original overlong literal lines (baseline 307: 128 columns; 311: 127 columns). Each is split into Java compile-time concatenation, preserving every SQL character, including spaces and the trigger prefix's final space. No native test semantics or other formatting changed.

## Authored cases versus executed cases

All seven new methods live in the existing real bound HTTP fixture. A test-owned source-response selection is serialized through the existing `InternalBindings.reply` and actual HTTP handler/client; negative JSON mutations retain the original request binding. No permissive adapter/predicate stub was introduced. Existing tests and UTF-8 response serialization remain; the baseline test delta has zero deletions.

| Authored method | Intended boundary | Execution this phase |
|---|---|---|
| `actualOwnerPreOrderSourcePreservesAbsentOrderAndReceiptFacts` | Exact actual owner-shaped pre-order response succeeds through HTTP with original null due/order/declaration fields, zero revisions, CNY, empty lines, null USER identity revision, and original authority | None; compiled only |
| `createdOrderAwaitingReceiptRetainsKnownDue` | Existing order retains original O/SO/revision/101 due while receipt remains awaiting with no declaration | None; compiled only |
| `createdOrderAwaitingReceiptRejectsMissingDue` | Current created-awaiting tuple cannot use pre-order missing-due allowance | None; compiled only |
| `createdDeclaredSourceRejectsMissingOrContradictoryDue` | Missing due or 102 due contradicting declared 101 is unavailable | None; compiled only |
| `preOrderSourceRejectsInventedRevisionsDeclarationsAndKnownDue` | Fourteen malformed variants: ADMITTED; source UNKNOWN/receipt awaiting hybrid; nonzero order/admission/observation revisions; supplied zero due; nonnull provenance/declaration/method/declared amount/received time/recorded time; changed operation state; invented reason | None; compiled only |
| `preOrderSourceRejectsHybridOrderAndMismatchedOriginalReferences` | Invented matching order refs with no due; receipt-only mismatched refs; mismatched receipt operation; currency mutation; requested SO versus absent original SO | None; compiled only |
| `oldProviderSourceRemainsExplicitlyIncomplete` | Actual legacy constructor preserves numeric M1 party revision and null due/currency/declaration/authority | None; compiled only |

Existing `appendedSourceDisplayFactsStayBoundToExactOriginalWithoutChangingM1PartyRevision`, `boundManualFactsRoundTrip`, `wrongDigestOrMalformedResultIsUnavailable`, and `lostReplyUsesOriginalQuery` bodies were preserved. The new positive pre-order case targets the prior unconditional nonnull-due rejection. Several malformed cases are intentionally rejected by the authoritative record constructors during HTTP decoding; missing due, zero-revision shape, known pre-order due, UNKNOWN/awaiting hybrid, and awaiting reason checks also cover adapter-specific boundaries. No red/green or mutation result is asserted without execution.

## Compilation evidence

Working directory for both commands: `/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex`.

First command, covering final product/native literal edits and the first six authored test methods:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  mvn -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 \
  -f /private/tmp/task66-complete-reactor/pom.xml \
  -Pbeauty-embedded -pl :shop-app,:beauty-channel -am test-compile -DskipTests \
  > /private/tmp/task67-c01-compile.log 2>&1
```

Result: **exit 0, BUILD SUCCESS, 45.695 s**, finished `2026-10-26T12:14:00+08:00`; all 25 selected reactor modules successful. Current source compilation included 413 host main files, **749 host test files**, 35 beauty-channel main files, and **17 beauty-channel test files**. No stale installed provider was substituted: current owner/base/core sources were in this reactor; no Maven install was run.

Final command after self-review added the created-awaiting missing-due method and UNKNOWN/awaiting hybrid variant:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  mvn -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 \
  -f /private/tmp/task66-complete-reactor/pom.xml \
  -pl :beauty-channel -am test-compile -DskipTests \
  > /private/tmp/task67-c01-final-channel-compile.log 2>&1
```

Result: **exit 0, BUILD SUCCESS, 2.743 s**, finished `2026-10-26T12:15:10+08:00`; all eight selected modules successful. Maven explicitly recompiled all **17 current beauty-channel test sources due to changed source code**. Final product/native files were unchanged after the first compile. Both invocations ended at test-compile; neither reached Surefire/Failsafe or executed a test/server.

Warnings/notes were retained rather than hidden: recurring Java 21 implicit annotation-processing notices; an existing removal warning at `OpsServiceImpl.java:920` for `OpsService.setStaffRole`; deprecation notes referencing `ApiAuthEntryPoint`, `CommunityAdminServiceImpl`, `LegacyCouponMigrationNormalizer`, `ShipTemplateParamTest`, `BeautyCatalogIT`, `DevSeeder`, `ActiveAddressFlowTest`, and `BeautyRemoteServiceTest`; unchecked notes referencing `OutboxNotificationOutcomeTest`, `MerchantServiceImpl`, `WechatChannelClient`, `WxFollowWaybillGatewayTest`, and `ManualCounterRecoveryIT`. No compile error or diagnostic named a C01 changed source. The final channel compile retained annotation-processing notices and the existing `BeautyRemoteServiceTest` deprecation note.

## Static verification and self-review

- Google Java Format **1.25.2**, `--aosp --skip-reflowing-long-strings`, applied only to touched ranges. Final `--dry-run --set-exit-if-changed` returned **0** separately for all three files, with final changed ranges: adapter `201:222`; HTTP test `55:55`, `162:164`, `293:575`; native test `307:308`, `312:313`. No imports were added or changed.
- Every C01 inserted/changed Java line is at most 120 columns. Source files have no remaining overlong lines.
- Static native concatenated-literal normalization against the saved baseline returned **equality=True**. This was a text/Java-literal analysis, not native SQL execution.
- `git diff --check --` the three authorized source paths returned **0** with no output. The native test is pre-existing untracked Task67 work, so its exact saved-baseline diff and line checks were also inspected directly.
- Read the complete owned baseline diffs. Product delta is confined to `sourceDisplay`; original request, signed binding, identity, service target, and exception behavior are unchanged. Test delta is additive. Native delta is exactly two same-value splits. No new protocol/model/dependency/schema/auth definition exists.

## Limits and handoff

Coding/static/compile evidence is complete for C01 and awaiting independent scoped review. Tests were **not executed**, so no runtime compatibility, HTTP rejection, native database behavior, or ablation claim is made. Unified phase B must execute the newly authored cases alongside preserved Task67 coverage and the previously scheduled cross-shape/runtime checks. Do not treat the compile success as deployment or acceptance evidence. No further source changes are proposed in this handoff.
