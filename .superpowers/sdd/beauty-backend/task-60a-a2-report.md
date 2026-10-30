# Task60A-A2 runner implementation report

Status: DONE_WITH_CONCERNS — A2 implementation and specified verification complete; environmental warnings and a redacted XML archive are explained below. Root independent spec/quality review and exact commit remain outside this implementer's work. This does not close other Task60A slices or BM04/BM05.

2026-10-24 · 档位0 · requirement: `task-60a-a2-brief.md`; design §1/2/A2; source audit `task-60a-report.md` selector/secret/XML rows. IMPLEMENTATION_BASE `e615a6603c9e3749f17c42853f21c5ed282bce53`. Unique worktree `/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex`, branch `codex/beauty-foundation`. No subagent/helper/reviewer dispatched by this implementer.

## Delivered scope

Only product files are `scripts/verify-checkout-payment.mjs` and new, still untracked `scripts/tests/checkout-payment-runner.test.mjs`. Allowed report/evidence live beside this report. No Java/SQL/POM/known/Task59 runner/root TDD/brief/progress/ADR/other slice edits; root's concurrent progress ledger update is outside this manifest. No stage/commit/push/merge, cache clearing, VM changes, shared/remote database, real payment, deployment or dual-JVM/JAR launch.

`validateSelection(mode, selected)` is pure and precedes effects construction, file access, random UUID/secret generation, serial lock, tool detection, memory and Docker. Only absent (`undefined`) selection defaults. Exact known class/method capabilities preserve original Maven `+`, payment comma combinations and disjoint same-class comma methods, rejecting duplicates and malformed/unknown/mixed-mode choices. CLI accepts no flags or one `--beauty-source`. Importing the module does not run native effects.

`inspectReport(xml, requested)` checks expected suite and every testcase class, nonzero consistent counts, zero failures/errors/skips, every requested method with exact or JUnit parameterized boundary, and supplied report watermark/mtime. Main removes every selected class's old XML before recording start time, requires successful non-signalled/error-free Maven completion, and checks every selected class. Whole-class selection remains whole-class Maven selection.

Secrets go only through child env: `MYSQL_ROOT_PASSWORD`, `MYSQL_PWD`, checkout/Spring datasource passwords. Docker args carry variable names, SQL is stdin. Necessary effects isolate Node filesystem/process/random/time/log boundaries; no generic runner framework was introduced. Maven stdout/stderr are bounded at 16 MiB with 600000 ms timeout and printed after redacting both the runner credential and Spring's generated test security credential. Status, signal and safe error reason remain visible on failure; warning text is retained.

The runner takes the same `/tmp/codex-beauty-bm04-native.lock` as Task59, only removes a successfully acquired own lock, and marks its UUID creation attempted before Docker run. A lost create response still triggers own UUID removal. Authoritative `No such container:` is recorded as absent; unknown cleanup errors fail the run without claiming success. Own temporary paths and own lock cleanup are attempted independently. No foreign-name resource removal is possible from selection input.

## Actual TDD commands and results

All `node --test` commands below are exactly `node --test scripts/tests/checkout-payment-runner.test.mjs` in the unique worktree.

| Stage | Exit | Actual result | Evidence |
|---|---:|---|---|
| First safe CLI RED, old product | 1 | 1 test, 0 pass, 1 fail; unknown payment class reached `limactl` lookup before rejection | `red.txt` |
| First implemented full safety suite | 0 | 11 tests, 11 pass, 0 fail/skip | `unit-first.txt` |
| Minimum ablation: `selected === undefined` → `!selected` | 1 | 11 tests, 8 pass, 3 fail; CLI, zero-effects selector and defaults assertions detect empty-value regression | `ablation-red.txt` |
| Exact bytes restored | 0 | 11 tests, 11 pass, 0 fail/skip | `green.txt`, `ablation-restore.json` |
| New bounded-output behavior RED | 1 | 12 tests, 11 pass, 1 fail: expected warning-preserving captured output absent | `output-red.txt` |
| Final code GREEN after output sanitization and safe status diagnostics | 0 | 12 tests, 12 pass, 0 fail/skip | `final-green.txt` |

The first RED deliberately starts the old CLI with empty executable PATH. Its assertion fails because production reached a forbidden pre-validation resource-tool effect; Docker cannot run. It is not a missing export/compiler/setup failure presented as behavioral coverage. Later invalid-selection tests use an effects Proxy whose enumeration itself fails, proving no effects boundary is reached. Output RED is a controlled real-production-main assertion, without tool execution.

Ablation byte restoration at that stage:

- Before SHA256: `a48c09a3548183e3e3a02e0bc45e8a5f8839b3e8eba2bdfe16bd765ec827d84b`.
- Restored SHA256: `a48c09a3548183e3e3a02e0bc45e8a5f8839b3e8eba2bdfe16bd765ec827d84b`.
- Exact byte equality: `true`. The subsequently discovered native output issue has its own real RED/GREEN above; final source hash is separately recorded in the manifest.

The 12 test methods cover the brief's nine required names/behaviors plus zero-effect flags/modes, Maven exit/timeout/signal cleanup, and bounded warning-preserving output redaction. Tests use controlled effects rather than resource tools; parameterized method names accept correct delimiters and reject mere prefixes. Every currently known class and explicit method is exercised as a selection capability, without running 14 business classes.

## Finite method allowlist provenance

Read-only verified source table follows. All current entries are real `@Test`-annotated Java void declarations. Multiline method declarations were inspected, including OfflineReceiptFlowTest and ReceiptEvidenceFlowTest; before acceptance, annotation/declaration names in uncommented source were independently compared against the preparation inventory and source SHA. Non-test helpers such as `stalePreparedAlias`, `assertLateSuccessfulOriginalBlocksUnsentReplay`, and `openUser` are rejected. No runtime Java-source discovery is used. There are no Unicode method names in these present files; the selector does not use an ASCII identifier gate. Pure XML matching accepts Unicode names.

`verified-source-methods.json` preserves each source path, SHA, annotation, declaration candidate and line anchor. This is a reviewed finite table, not a claimed Java AST parser. The observed count below is not a frozen test-total contract.

| Scenario class | Reviewed methods | SHA256 of actual Java source |
|---|---:|---|
| `PaymentOperationNativeMysqlTest` | 16 | `d8085f54e78ea2e1feb1685589da75b1cfe43dd841d7d8f607bf26b897a0ab02` |
| `PaymentOperationProcessRestartNativeTest` | 2 | `239caef4338a6b1f5ccb73ff35c139a4f76c26638358a4db6847a96681276a96` |
| `OfflineReceiptFlowTest` | 9 | `3c42229d7fbef012b3a08f8ad8dca6bc88f3dcad272dbd391d007377b057149f` |
| `CheckoutPaymentAuthenticationFlowTest` | 18 | `f93dbd330cfa9590333228ccdddcce15172659c77f7f9ebd6739c835da039c38` |
| `CheckoutCashPermissionFlowTest` | 3 | `0757695000cb59a93d065b0cd0fd81607190db1b82be23c3b637ce135ddf4aeb` |
| `ReceiptEvidenceFlowTest` | 6 | `6e0614a39c3177b56478e7ddadcd42880abf58f1a832cf1fdc6c21d075b2ff49` |
| `CheckoutNativeMysqlTest` | 11 | `f1bc5e143640bff5bb9c76aa6386a3e52354d84d28d72d9beb5d694e84346901` |
| `CheckoutRestartNativeTest` | 1 | `578054151b86201013cc272471e7b23c0e98b95d372e9377eb950e73a4a92540` |
| `CheckoutPermissionFlowTest` | 7 | `945011c90646f492c87f000ff21a838d548430493f5cdf5b24a0e6d3c1cb6f78` |
| `CheckoutPricingFlowTest` | 20 | `9f5d0c4b30c88748f411816a52078fa712dbf81b16a41d7779c07cbb016290eb` |
| `CheckoutHttpFlowTest` | 8 | `d4b795516917cd7e86bb52c679b228c5ca4fac1e7e8d428db3ac0d30f3320de3` |
| `CheckoutManagedLifecycleFlowTest` | 12 | `561124559028cc7b6f30ee9d39af3ae077ecb926e0987d0812d392579512155a` |
| `CheckoutOwnerBoundaryFlowTest` | 5 | `831b9fe257e69aeb13310bd36bf1638e183b344dfb3efccc222be202592b57f5` |
| `CheckoutSourceFactsFlowTest` | 9 | `3995757033698e1f76b012ed8b480290f9ecf454d9aa23af6e2a0a59f0cb39c5` |
| `BeautySourceCheckoutIT` | 4 | `50d75fdb11ff5852e58314ea17b8bf6d52ee2319b57ccb913fe20d3850c1cef1` |

All sources are under `backend/shop-app/src/test/java/ai/neargo/shop/scenario/`, with the class name plus `.java`. Observed total: 131 methods across 15 files; no fixed count is enforced by runtime/tests.

## Secret-free argv evidence

`keepsSecretsOutOfAllChildArguments` runs real `main` with controlled process effects and checks every captured limactl/python/docker/Maven argv against the generated credential, while verifying Docker and Maven env carry the credential and SQL exec input is stdin. It also verifies redaction on cleanup failure without printing the credential. `preservesPlusSelectionsAndChecksEveryRequestedMethod` verifies original `-Dtest=` string reaches Maven. `redactsNativeOutputWithoutSuppressingEnvironmentWarnings` asserts native output redacts both credential categories and retains stdout/stderr warnings plus timeout/output bounds. Test fixture values are never printed. Final native log contains `Using generated security password: [REDACTED]` and no raw generated value.

## Actual isolated native execution

Authorized command, run twice only because final code changed the native stdout/stderr path after the first smoke:

```sh
CHECKOUT_PAYMENT_TESTS=BeautySourceCheckoutIT#realGuestClaimThenCurrentConsumerPurchaseFeedsBindingAndBill node scripts/verify-checkout-payment.mjs --beauty-source
```

Initial invocation accidentally omitted `--beauty-source`: exit1, `invalid checkout selection: class or mode`, no resource effects. Preserved as `native-wrong-mode-rejected.txt`; it is not counted as native execution.

First proper smoke: exit0, 1 test / 0 failure / 0 error / 0 skipped, own UUID cleanup and lock cleanup. This used the pre-captured-output code and is not final-code evidence. After discovering Spring's unrelated temporary default credential in inherited child output, the first log was sanitized before archival (`native-first-smoke-redacted.txt`, `native-first-log-sanitization.json`). No credential value is reproduced here. First XML was archived with that system-out value redacted; its original and archive SHA/mtime are retained in `native-first-xml.json`.

Final-code smoke: exit0, fresh `BeautySourceCheckoutIT` suite with exactly the requested real testcase, tests=1 / failures=0 / errors=0 / skipped=0; Maven `BUILD SUCCESS`. Command execution uses JDK21 `/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`, `mvn -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 -f backend/pom.xml -pl shop-app -am test`, the exact method selector, `-Dcheckout.test.native=true -Pbeauty-embedded -Dbeauty.source.test.native=true`. No clean or unrelated suite run. Evidence: `native-smoke.txt`, `native-exit-code.txt`, `native-xml.json`.

- Final runner SHA256: `70522977e63311f2fcb3d8d2ca8b64d579a1b500977293349492c06258de33f2`.
- MemAvailable: 2619080 KiB, above 524288 KiB.
- Existing `colima-hxkey-tests` context / `hxkey-tests` VM; LIMA_HOME `/Users/robin/.colima/_lima` retained.
- Cached `mysql:9.7.2`, `--pull never`, normal disk datadir, 64M buffer pool, random 127.0.0.1 port, env-only credential.
- UUID: `codex-checkout-payment-ca6667e0-87fd-4811-8469-78976f0ae40c`.
- Original fresh XML: `/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex/backend/shop-app/target/surefire-reports/TEST-ai.neargo.shop.scenario.BeautySourceCheckoutIT.xml`.
- Original XML SHA256: `33bb0b4b119f57d6ab9cbe4c2408cacbe4397740a1cc0201cbf31b877fe51227`; mtimeMs `1792810250046.0283`.
- Shareable XML archive: `TEST-ai.neargo.shop.scenario.BeautySourceCheckoutIT.redacted.xml`, SHA256 `ebf37b8d48a18aab9861ca4a086f2ad8b61400184d215f962695bfa2bb56f9c3`.

The shareable XML copy only redacts one generated Spring security credential in system-out; suite attributes and testcase are unchanged. The raw Surefire target XML is untouched to retain provenance and may contain Spring's transient default security credential in captured application output; it is not copied into reports or returned inline. `native-xml.json` distinguishes raw and archive hashes. The final runner's displayed native log is already redacted.

`native-cleanup.json` independently records exact-name `docker --context colima-hxkey-tests container inspect --format '{{.Name}}' <own UUID>` exit1 with authoritative `No such container`, own temp directory absent, and shared serial lock absent. Runner logs its own successful force/volumes removal and own lock removal. Lost-create response, already-absent container, unknown cleanup failure, and foreign lock cases are separately covered without native resource execution.

Migration boundaries remain exact: V538 structural fixture, not whole-history replay; exact frozen V539 8444 bytes / `5a8e346341e5b378403761f2a77555c0558ff6b27a6071584207a9d8f9e14d3b`; V540 7477 bytes / `ca76b6512cf79c6f25fa71a4441f533e03cc609c6320611a0dcdc9634a10f745`; beauty V8 4582 bytes / `c5b54d574624a5265366cf4bf7897ec949d107bd302f171539c461a89a9d8343`. Native log also confirms actual beauty Flyway V1–V8 migration. No migration file changed.

## Warning attribution and limits

`warning-attribution.txt` and full final native log retain actual warnings. Existing test/runtime configuration emits duplicate MyBatis mapper registration/missing mapperLocations, H2/MySQL newer than Flyway verified versions, logistics route/key omissions, disabled OTP rate limit, development tracking/security defaults and seeder, missing MacOS native Netty DNS provider, TEST settlement currency fallback, plus Mockito dynamic agent/JDK CDS notices. These are environment or existing test-configuration warnings, not zero-skip failures, and have not been suppressed or claimed fixed by A2. MySQL and beauty migrations actually executed despite the version warnings. The Spring default credential value alone is replaced, its warning text remains.

Maven stdout/stderr are emitted after bounded capture rather than streamed. An output-buffer overflow is a failed Maven run, with status/signal/error and captured safe diagnostics; timeout remains 600000 ms. The raw XML note above is the only artifact handling concern. No other native business suite, deployment or A3 work was started.

## Exact delivery manifest

`task-60a-a2-evidence/owned-manifest.json` has exactly two product entries, absolute paths, bytes and SHA256. `task-60a-a2-evidence/owned-full.diff` includes both the tracked runner diff against IMPLEMENTATION_BASE and the complete untracked new test via `git diff --no-index /dev/null <test>`; no staging was used.

- `scripts/verify-checkout-payment.mjs`: 22636 bytes, SHA256 `70522977e63311f2fcb3d8d2ca8b64d579a1b500977293349492c06258de33f2`.
- `scripts/tests/checkout-payment-runner.test.mjs`: 23473 bytes, SHA256 `25ebf08b39ac4414977b329e9c3d2e75838392688f01fd4cd9a2fdb031ebb8d1`.

Full two-file diff SHA256: `b557dc950c16f22c83138e9575312302eadbcf8ba3a4f17c077ad5e1a97c515f`. Product whitespace check `git diff --check -- scripts/verify-checkout-payment.mjs` exited0. No broad or repeated unrelated checks were added. All required safety checks and the one authorized native method are complete; root independent reviews/commit remain the next owner.


---

# R1 — independent review Important fixes

Status: DONE for R1 implementation/verification; ready for fresh scoped root review. Original text above and all earlier evidence are frozen **R0**. They are historical evidence, not the final implementation claim. In particular, the R0 retained-raw-XML exception is superseded by the verified R0 target remediation and R1 persisted-artifact lifecycle below. The root's frozen R0 source/report/package were not changed.

Scope remains the exact same two owned product files, plus this append and `task-60a-a2-evidence/r1/`. IMPLEMENTATION_BASE remains `e615a6603c9e3749f17c42853f21c5ed282bce53`. No subagent, dependency/POM, Java, SQL, config, root TDD/progress, deployment or expanded business suite change. No stage/commit/push/merge.

## Review findings addressed

Read full independent review `/tmp/review-bm04-60a-a2.md` and verbatim open findings `/tmp/task60a-a2-r1-findings.md`, verified against the real code, and reproduced both behaviors before product changes.

1. **XML text versus actual evidence:** replaced whole-document tag regex matching with an internal bounded Surefire XML tokenizer. It skips comments, treats CDATA/escaped log markup as text, validates root/parent-child relationships and balanced closing elements, validates attributes/entities and XML characters, rejects unsupported declarations/DTD, malformed comments/CDATA, duplicate attributes and invalid outside-root text. Only direct real testcase children of the one root testsuite count; real failure/error/skipped/retry elements fail inspection, while literal log text does not. Bounds: 16 MiB XML, 100000 elements, nesting <64. This is a narrow Surefire reader, without new dependency or generic XML framework.

2. **Retained raw report credentials:** the native process boundary now runs artifact sanitation in `finally` for success, nonzero status, signal, timeout/error result, and thrown process effect. It only handles exact selected-class XML, class summary `.txt`, and class `-output.txt` files that are fresh and newly created/changed from this run's watermark/snapshot. Foreign/historical unchanged reports are not rewritten. A fresh unassociated `.dump`/`.dumpstream` causes an explicit ambiguous-ownership failure without touching that file; selected own artifacts are still sanitized and own resources cleaned. It does not claim success in an ownership or sanitation failure.

Known runner credentials and discovered generated Spring credentials are replaced by value, including repeated occurrences without the warning label. All diagnostics retain their warning text. Sanitation rereads persisted bytes and verifies credential absence; original and sanitized SHA256 digests, original mtime/start watermark, and XML structural projections are saved in a per-artifact `.runner-sanitization.json` sidecar. Projections contain suite/testcase attributes and outcome names, never system-out or credential bytes. XML statistics/testcase evidence must be unchanged. Maven failure diagnostics are printed safely even when sanitation itself fails. Original secret-bearing bytes are not archived.

## Genuine RED/GREEN commands and output

All safety runs used exactly:

```sh
node --test scripts/tests/checkout-payment-runner.test.mjs
```

| Stage | Exit | Actual result | R1 evidence |
|---|---:|---|---|
| New XML/retained-artifact behavior RED on unchanged R0 product | 1 | 16 tests: original 12 pass, four new behavior tests fail | `red.txt` |
| Initial minimal implementation GREEN | 0 | 16 pass, 0 fail/skip | `first-green-attempt.txt` |
| Strict malformed XML boundary RED (root-outside whitespace) | 1 | 15 pass, 1 fail | `malformed-boundary-red.txt` |
| Repeated generated credential value RED | 1 | 15 pass, 1 fail | `repeated-value-red.txt` |
| Final code GREEN | 0 | 16 pass, 0 fail/skip | `green.txt` |

RED assertions are missing expected exceptions for CDATA-only/malformed evidence and missing raw-credential removal from retained controlled artifacts; no export/compiler/setup failure is used as RED. Additional boundary REDs were resolved before native. The malformed-comment test literal was corrected to represent an actually invalid trailing-hyphen comment while fixing the independently reproduced outside-root whitespace issue.

The four new tests are `requiresActualXmlTestcasesAndIgnoresCdataCommentsAndLiteralLogText`, `rejectsMalformedXmlAndInvalidSuiteTestcaseRelationships`, `sanitizesEveryOwnedRetainedArtifactOnSuccessFailureSignalAndThrownProcess`, and `artifactSanitationFailureCannotPassAndDoesNotRewriteForeignArtifacts`. They test comment/CDATA/escaped fake testcase rejection, accepted literal failure/testcase log text, malformed nesting/entities/attributes/declarations, every retained artifact on success/failure/signal/exception, SHA provenance, unchanged before/after structure, sanitation-write failure causing failure with cleanup, and foreign/ambiguous ownership boundaries. Synthetic credential values are not printed in test evidence.

## R0 target remediation without rewriting frozen evidence

Before the R1 native rerun, verified the existing target XML against the exact recorded R0 raw SHA `33bb0b4b119f57d6ab9cbe4c2408cacbe4397740a1cc0201cbf31b877fe51227`. The matching class summary was independently checked for the same 1/0/0/0 result and mtime within 5 seconds. Only these two confirmed R0-own target files were considered; no other report was mutated. The XML's generated credential was removed in place; summary required no replacement. Values were never printed or saved elsewhere. `r0-target-remediation.json` preserves original/sanitized digests and before/after testcase/count equality. The old shareable R0 archive, report text and source snapshots remain unchanged.

- `TEST-ai.neargo.shop.scenario.BeautySourceCheckoutIT.xml`: original SHA `33bb0b4b119f57d6ab9cbe4c2408cacbe4397740a1cc0201cbf31b877fe51227`; sanitized SHA `ebf37b8d48a18aab9861ca4a086f2ad8b61400184d215f962695bfa2bb56f9c3`; credential absence `true`.
- `ai.neargo.shop.scenario.BeautySourceCheckoutIT.txt`: original SHA `ff91ec9f68f27909903bf13b5e7058507f547c788eb72e3cedc73bfcb9bfacef`; sanitized SHA `ff91ec9f68f27909903bf13b5e7058507f547c788eb72e3cedc73bfcb9bfacef`; credential absence `true`.

## Final R1 native verification

After all 16 safety tests were green, ran exactly **one** justified native rerun for the changed persisted-report lifecycle:

```sh
CHECKOUT_PAYMENT_TESTS=BeautySourceCheckoutIT#realGuestClaimThenCurrentConsumerPurchaseFeedsBindingAndBill node scripts/verify-checkout-payment.mjs --beauty-source
```

Exit0, Maven BUILD SUCCESS, fresh suite 1 test / 0 failure / 0 error / 0 skipped; exact requested real testcase present. No other native method/suite was run during R1. JDK21/offline Maven/cache settings, migration pins and V538 fixture + exact V539/V540 + actual beauty V1–V8 boundary remain unchanged. Cached mysql:9.7.2 `--pull never`, colima-hxkey-tests/hxkey-tests, disk64M, random loopback port, env-only secrets, serial own lock/UUID.

- MemAvailable: 3670008 KiB.
- Own UUID: `codex-checkout-payment-d99f099f-c1f6-4e12-9485-58668bd0ed27`.
- `native-smoke.txt`, `native-exit-code.txt`, `native-artifacts.json` record final actual results and credential-absence booleans.
- Both retained target XML and class summary and both archived R1 copies have `credentialsAbsent=true`; XML counts/testcases before/after are equal. No class `-output.txt` was generated and no fresh unassociated dump/dumpstream was detected. This run therefore has no other identified native output artifact requiring sanitation.
- Exact-name Docker inspect confirms only this own UUID absent; own tmp absent and shared serial lock absent. Command/exit/stderr and booleans are in `native-cleanup.json`. No foreign resource cleanup was attempted.

- `TEST-ai.neargo.shop.scenario.BeautySourceCheckoutIT.xml`: original SHA `b476f880477089d0cc47be2fbeb139434321129df468865e41c180f8cc0d05ae`; retained/archive sanitized SHA `e710e38e938f3e70be318650c1467fe1c064b5e61e623fea3ddf44cdbd5657b2`.
- `ai.neargo.shop.scenario.BeautySourceCheckoutIT.txt`: original SHA `21ab8827eabd4f44160d8dc5bea8d22baca097be608ccd3734a0c0e0f587b37d`; retained/archive sanitized SHA `21ab8827eabd4f44160d8dc5bea8d22baca097be608ccd3734a0c0e0f587b37d`.

Per-artifact provenance sidecars are copied to R1 evidence, with original watermark and both structural projections. Original raw XML exists only as its digest; the target XML itself is sanitized. This closes the retained raw-credential concern stated in the historical R0 report.

`warning-attribution.txt` retains the actual environment warnings. Minor warning debt remains assigned to A11; no warning suppression or Java/config cleanup was added. The sole R1 scope is the two Important review findings. stdout/stderr remain bounded to 16 MiB and 600000 ms; display may follow bounded capture, as documented in R0.

## Final two-file package

R1 `owned-manifest.json` identifies exactly two final product files and hashes, the complete base diff including the still-untracked test, and the full delta against root's frozen R0 sources. It does not replace R0 artifacts.

- `scripts/verify-checkout-payment.mjs`: 30342 bytes; SHA256 `e01bc93f1e3a33333b365a0cb7dbb56456e9d3a5f41ba55ca13904bd37532fcb`.
- `scripts/tests/checkout-payment-runner.test.mjs`: 30038 bytes; SHA256 `eebd66455465fff90a2e6b0e8eb6b17c55a1d892ee3b2afe8df2d828afcce8d0`.

Full base two-file diff SHA256: `b6239b793f166df5c97e68a4edaf4b1f089831ea81ac242d3aed19b2d93f6537`. Full R0→R1 delta SHA256: `5927dc34217bdc29e79ede10f7697f90874fb5ea725ceff1202167db18ad1099`. Frozen R0 manifest SHA256: `002c83029c1f36f83871ba72a49338928e025708de4ebe39f9d361c31bec4a8b`.

Product whitespace check exited0. No source edits followed final safety GREEN or native execution. Independent fresh scoped review/acceptance remains with root; no commit was made.


---

# R2 — complete credential discovery before any rewrite/certification

Status: DONE for R2 implementation/Node verification, ready for fresh scoped review of remaining I2 and this delta. Historical R0/R1 text and evidence above remain immutable checkpoints. The R1 statement that I2 was closed is superseded by this fix and independent review `/tmp/review-bm04-60a-a2-r1.md`; I1 is accepted and was not reworked. Read the full review plus verbatim `/tmp/task60a-a2-r2-findings.md` before changes.

## Remaining defect and focused fix

The R1 implementation could certify an earlier artifact containing a bare generated credential before learning its marker from a later artifact. The first R2 RED reproduces actual production `main` leaving that earlier value in memory-backed persisted artifact bytes; assertions reveal only booleans, never a credential value.

Separated discovery from replacement. Main now discovers generated values across both captured stdout/stderr streams and **all** eligible newly generated/changed selected-class artifact contents before any artifact rewrite or certificate. Only after all reads succeed is the discovery set considered complete. It then rewrites all eligible artifacts using that completed set, rereads and verifies **all** persisted artifacts against the same set, and only then writes any credential-absence/provenance sidecar or sanitation-success log. Original/sanitized hashes and structural proof remain intact.

A read/discovery failure prevents all artifact rewrites and new certificates and fails the run. If discovery is incomplete, raw captured Maven streams cannot be safely displayed because they may contain a bare value whose marker was unreadable; an explicit safe withholding diagnostic replaces those streams. When discovery completes, original warning text is still displayed with known credential values replaced, including Maven/sanitation failures. Write/verification errors prevent certification and run success while outer own UUID/tmp/lock cleanup remains active. Foreign/unchanged artifact boundaries and ambiguous dump ownership handling remain unchanged.

The bounded XML tokenizer, pure selector/report contract, method table, provisioning/SQL/migration pin/Maven/JVM topology and child argv are untouched. The entire source prefix before `main` is byte-identical to frozen R1; hash recorded in the manifest. Existing early Docker/error redaction keeps its behavior through an explicit discovery call before replacement. No Java/SQL/POM/config/root documentation beyond this allowed report append, native, deployment, subagent, index or commit action occurred.

## Actual safety commands, RED and GREEN

Every test run used exactly:

```sh
node --test scripts/tests/checkout-payment-runner.test.mjs
```

| Stage | Exit | Actual output | Evidence |
|---|---:|---|---|
| Initial ordering/certification RED on frozen R1 product | 1 | 19 tests, original 16 pass, 3 new failures | `ordering-red.txt` |
| Direct earlier-bare/later-marker credential-retention RED | 1 | 19 tests, 16 pass, 3 fail; first failure is actual retained bare value | `red.txt` |
| Final R2 GREEN | 0 | 19 tests, 19 pass, 0 fail/skip | `green.txt` |

These are real production-main assertion failures, not missing export/compiler/setup errors. The direct RED case was ordered first so retained credential bytes, rather than only effect sequencing, were the first failed assertion. Throws have no captured result stream; throw scenarios therefore use actually available marker-bearing artifacts, while streams-only marker cases exercise success/nonzero/signal results. No guessed test count was introduced.

New tests:

- `discoversCompleteCredentialSetAcrossStreamsAndArtifactsBeforeRewritingOrCertifying`: class XML bare value/summary later marker; earlier output bare/later XML marker; stream-only marker/artifact bare; stdout/stderr bare values discovered from later XML/output markers; multiple generated values and repeats; both artifact orders; actual success/nonzero/signal and artifact-discovery thrown-process paths. It checks every retained artifact and displayed output, all SHA/structural sidecars, that every eligible source was read before first write, and that all retained artifacts were reread before first certificate.
- `failedCredentialDiscoveryCannotRewriteCertifyOrExposeCapturedBareValues`: unreadable marker-bearing artifact prevents writes/certification and unsafe raw-stream display, with own cleanup still attempted.
- `verifiesAllPersistedArtifactsBeforeIssuingAnyCredentialAbsenceCertificate`: late persisted-artifact verification failure leaves no new certificate or success claim, with own cleanup still attempted.

The earlier 16 tests remain green, including XML structure/literal-log text, secrets in child argv, foreign artifact/lock protection, lost create response and native exit/error/signal handling. Only the owned test helper gained controlled memory-file ordering/read/write observations; no native implementation effect executes except the existing read-only migration pin fixture reads.

## Verification boundary and checkpoint labeling

Per root ruling, **no native/Docker/VM/JVM/deployment operation was run in R2**. The change is credential-discovery ordering inside the unchanged process/artifact topology, verified through real `main` with controlled effects. Optional replay of recorded R1 artifact bytes was unnecessary; the matrix directly exercises the missing cross-source cases. Historical R1 native 1/0/0/0 and cleanup evidence remain an earlier-code checkpoint and are not represented as a final-R2-source native run. R1 raw-target remediation and sanitized archives remain untouched. Existing environment warning debt remains assigned to A11.

## Final exact two-file package

Only the same two owned product files changed. Frozen R1 source/manifest/report copies remain untouched, and prior report bytes above were verified by SHA before this append. `r2/owned-manifest.json` gives final absolute paths and hashes, the complete base diff (including the untracked test), and the complete R1→R2 delta for scoped re-review.

- `scripts/verify-checkout-payment.mjs`: 31797 bytes; SHA256 `ee953b49d799fca5dbecf919e1721d8c04d7ad086e1c48878995969d2333883c`.
- `scripts/tests/checkout-payment-runner.test.mjs`: 36261 bytes; SHA256 `581d44230251d231439b245f6f70595aafb3425add8bb42656bc6d30f928bf90`.

Full base diff SHA256: `10bf8456a75c1cf06cb98943271fa7dfbce8bcfbeaf34c2bb37facea293e4159`. Full R1→R2 delta SHA256: `912f2b50d2b4c203d466700ad827af6d8cd6d1094fa0c754ed7b8a7808b0fb9e`. Byte-identical pre-main source SHA256: `06310ef5347d00c7285788b227669b823f1bf6fa7ab0d2164eda7b8b52d14940`.

`git diff --check -- scripts/verify-checkout-payment.mjs` exited0. Final code is the source tested by R2 GREEN; no later product edit. No stage/commit/push/merge. Root fresh scoped review/acceptance remains next.
