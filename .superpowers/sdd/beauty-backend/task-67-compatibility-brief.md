# C01 / Task67 compatibility follow-up — released code/compile only

Read this first; requirements from mainTDD§0.4/§2.46 and existing task-67-compatibility-preflight.md. User authorized recommended remediation. EarlierTask67I1/I2/M1 independentR1 passed; do not reopen them. Correct R0 M2 legitimate pre-order source shape and R1-S1 two overlong test literals.

## Scope exactly three existing source paths

- beauty-channel/src/main/java/ai/neargo/shop/beauty/port/RemoteManualServiceAdmissionPort.java
- beauty-channel/src/test/java/ai/neargo/shop/beauty/port/RemoteManualServiceAdmissionPortTest.java
  Both relative to backend-plugins/shop-industry-beauty/.
- backend/shop-app/src/test/java/ai/neargo/shop/scenario/ManualSourceBindingIT.java: ONLY split the two >120 literal lines currently307/311 preserving exact runtime string. No other native test semantics/format changes.

Root saved all three current baselines under task-67-compatibility-base/manifest.json. No new product/API/schema/dependency/auth or other files. No commit/index edits/subagents. Root owns metadata/ledger. Need another path? source-backed report first.

## Contract

Inspect actual ManualServiceAdmissionServiceImpl.sourceView/view parent-null fields and both record constructors, actual source state/lines. Preserve exact original operation/scope/order/suborder/revisions/currency correspondence and declared-order amount checks. Allow null due only for genuine absent-order/absent-declaration AWAITING_RECEIPT shape; no fabricated0, identity, authority, bankproof/readiness. Distinguish existing order awaiting receipt with known due from pre-order; retain both legitimate states. Oldprovider declaration=null remains explicitly incomplete (unchanged). Reject malformed hybrid, orderrefs mismatch, nonzero revisions/declared fields in absent-order shape, ADMITTED with missingdue, declared mismatch. Do not weaken signed binding/request predicates or permit financial evidence.

Write focused cases in existing real bound-loopback HTTP test (not a permissive predicate stub): exact actual owner pre-order shape succeeds preservingnull; legitimatecreatedawaiting withknown due remains; createddeclaredmissingdue/mismatch rejects; malformedpreorder ADMITTED/nonzerorevisions/nonnull declaration fields/reference mismatch rejects. Preserve existing identity/UTF8/digest/oldM1partyrevision tests. Test cases authored/test-compiled ONLY, not executed now.

## Workflow override

Only code/static review/compile. NO unit/Node/loopbackHTTP/MySQL/native/integration tests, Docker, migrations, probes, appstartup, deployment, Pay, ablation or additional runtime guard. Compile affected current sources with Java21 /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home, Mavenofflinecache /tmp/codex-beauty-bm01-m2; actualaggregate /private/tmp/task66-complete-reactor/pom.xml. Use test-compile -DskipTests (not mvninstall/staleproviders); for nativehosttest string-only edit compile relevant host sources as needed without running. Do not run generators’ test suites. New/changedJava GoogleJavaStyle/AOSP/imports/120columns; only touched ranges and stringreflowdisabled. Static gitdiffcheck permitted.

Report full requirements/files/proposedcases vs executed(none), exactcompilecommand/result/warnings, selfreview and limits to task-67-compatibility-report.md; finalshortDONE_PENDING_REVIEW. No remote field/model duplicates.
