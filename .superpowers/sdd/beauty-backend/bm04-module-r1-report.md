# BM04 module R1 implementation report

Bounded I1 fix and verification complete; exact four products frozen for the original module reviewer’s focused re-review. **BM04’s code gate remains blocked pending that verdict.** No staging or commit.

## Identity and change

Cwd/worktree: `/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex`; branch `codex/beauty-foundation`; unchanged BASE/HEAD `16b182ad536e730ec2c6bf30e9c30d38dad1cac1`. Read the released R1 brief, full module I1, main TDD §2.38/Task56 §3, CLAUDE/dev/Java standards and debugging/TDD/verification skills before edits. Tier 0, existing contract correction.

`CheckoutServiceImpl` adds eight lines: a private actual-current-buyer equality helper and two calls. One follows all catalog/campaign/coupon preparation immediately before metadata; the other follows the completed metadata transaction/catch immediately before the final writer transaction. Both are outside transaction lambdas and stale-quote catches. Buyer mismatch is `FORBIDDEN`; revoked/disabled authority preserves `UNAUTHORIZED`. Entry authorization/CREATED replay, quote freshness, permanent original keys and writer/completion gates remain intact.

Only the four authorized products changed. Policy, real fixture, owner writer/guard, TokenStores, root TDD/ledger/acceptance, POMs, migrations and known-* stayed read-only. No install, deployment, stage, commit, push, merge, subagents or unrelated suites.

## Actual-owner proof

The new `CheckoutPlaceAuthorityFlowTest` extends unchanged `CheckoutFlowFixture`, using each test’s own actual account/store/SKU/coupon/quote/confirmation and genuine consumer TokenStore sessions. There is no mocked authorization/provider success, forged-principal success, service replacement or production hook.

| Exact method | Final GREEN proves |
| --- | --- |
| `revokedDuringPlacePreflightCannotClaimOrCreateOrder` | Real catalog call-through finishes outside a transaction with the genuine original session; only its token is revoked. Actual place fails exactly UNAUTHORIZED. Full operation/quote/confirmation rows remain unchanged, place hashes null; owned intent/order/suborder/item/source counts zero; full SKU/coupon rows unchanged. |
| `disabledDuringPlacePreflightCannotClaimOrCreateOrder` | Same real preflight disables only its own buyer; exact UNAUTHORIZED and identical zero-effect/snapshot checks pass. Cleanup restores own buyer and resets spy/SecurityContext/DataScope. |
| `revokedAfterPlaceClaimCannotReachWriterAndOriginalKeyRecovers` | Own-operation-only actual MyBatis update proceeds, returns 1 and registers actual afterCommit revocation. Both flags fire before UNAUTHORIZED. Only original place hashes/version+1 and metadata timestamps survive; other operation facts, quote/confirmation/economic rows remain unchanged. A fresh genuine same-buyer session with SAME request/key/confirmation creates exactly one intent/order/suborder/item/source line; replay returns that order/suborder; changed key conflicts. Locked stock=3, coupon USED/times_used=1, quote/confirmation CONSUMED. Finally removes only its own interceptor. |

The last check is the chosen authorization linearization point. Revocation after it is outside this contract; no database-atomic Redis-revocation claim.

## Finite verification

Whole-class native command: `CHECKOUT_PAYMENT_TESTS=CheckoutPlaceAuthorityFlowTest node scripts/verify-checkout-payment.mjs`. Ablations use the same command with the exact indicated `Class#method`. Every batch executed only this Java class. Each phase’s `.json` records cwd/command/selector/JDK/timestamps/exit and source/class fingerprints; corresponding raw logs, fresh actual XML/text and sanitization certificates are retained in `bm04-module-r1-evidence/`.

| Phase | Native selection | Exit | Tests/failures/errors/skips | Meaning |
| --- | --- | ---: | --- | --- |
| `behavior-red` | exact three | 1 | 3/3/0/0 | Valid pre-fix RED: actual entry/preflight or metadata-update/afterCommit assertions passed; each denial assertion fails with actual throwable null. Production source/class still frozen pre-fix. |
| `first-green` | exact three | 0 | 3/0/0/0 | Minimal fix passes all owner assertions. |
| `ablate-first` | `#revokedDuringPlacePreflightCannotClaimOrCreateOrder` | 1 | 1/1/0/0 | Only first late check removed. Final check denies, but operation equality fails because place hashes were committed and version advanced 2→3. |
| `ablate-final` | `#revokedAfterPlaceClaimCannotReachWriterAndOriginalKeyRecovers` | 1 | 1/1/0/0 | Only last check removed. Actual update/afterCommit flags pass; denial assertion fails because place returns successfully. |
| `final-green` | exact three | 0 | 3/0/0/0 | Both checks restored; same three pass again. |

Historical partial diagnostic is separate: `initial-red` ran 3/3/0/0, but method3 exposed an over-broad test ParamMap lookup. It does NOT count as required three-behavior RED. The new test interceptor was narrowed to immediately proceed for non-operation-update statements, then the same corrected test source produced valid `behavior-red` and both GREEN runs. First-ablation postprocessing initially accepted only java.lang.AssertionError; actual intended AssertJ map equality uses org.opentest4j.AssertionFailedError. Existing fresh XML was certified without rerunning native; finally restoration had already succeeded. Raw diagnostics remain.

Both ablations restore exact fixed source bytes in finally. First/final GREEN source SHA `d8c13a71b874469abbfa8237fc6ac15dda45a5e14daf5610efc9575f2ad3d672` and production class SHA `67ae7ecdd9e5d110af321f4049eb3e76f57c3e3f3728de5b11baea6b07a5b81b` match exactly. All source/main-test/gate-class hashes captured in both phases match; final compile retains them. `native-summary.json` records raw failures/counts/restore comparisons. `verified-green-xml.json` independently uses existing `inspectReport` on BOTH actual GREEN XMLs with all three exact names, nonzero/zero-skip and original freshness metadata.

Additional checks:

- Finite Node baseline 20/20; intermediate/final 21/21, final process exit=0 in `node-final.json`. New selector RED before registry addition is retained (19 pass/2 fail); its early shell ended in tail, so wrapper exit=0 is not presented as Node’s process exit. New exact class/method, plus, unknown/helper/prefix/suffix/duplicate/crossmode/PATH-empty rejection checks pass before provisioning; existing defaults, accepted registry and safety remain unchanged.
- Initial/final default-retail compile both exit=0: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 -f backend/pom.xml -pl shop-app -am test-compile -DskipTests`. Actual JDK 21.0.12.1; no beauty profile or standalone install.
- `git diff --check`, AOSP full-new-file and changed-production-hunk checks exit=0; new Java max width 101. No whole legacy formatting.

## Native safety and warnings

Unchanged runner used cached mysql:9.7.2/pull-never, normal disk/64M, colima-hxkey-tests VM hxkey-tests, memory preflight ≥524288 KiB, shared lock, own UUID/schema, env-only secrets and loopback. No foreign stops, VM changes, remote/shared DB or formal60B. `cleanup.json` verifies all SIX actual owned UUIDs absent by exact-name read-only Docker lookup, own temp dirs absent and shared lock absent; raw logs preserve each cleanup confirmation.

Unchanged runner sanitizes native and generated Spring passwords; retained XML/text certificates preserve original/sanitized hashes, credential absence and XML structure. Existing source-defined zero test keys are test data. `warnings.json`/raw logs preserve duplicate mapper, inventory H2/Flyway and mapperLocations, test logistics/default-key/OTP/seeding/security, Netty DNS, Mockito/CDS/dynamic-agent, implicit processor, deprecation and unchecked notes. Platform checkout uses the runner’s owned native MySQL URL; existing inventory test backend remains jdbc:h2:mem:inv. No warning-free/all-topology claim; module M1/M2/M3 debt remains out of scope.

## Freeze and handoff

`product-manifest.json` contains exact four paths and complete BASE/final/diff hashes. Manifest SHA256 `74ea0d2248277dcece9497da71fec3635b4a9e8ada0d4a386d6e697b3c2a90fc`. `full-base.diff` includes the new untracked Java file; SHA256 `dfccb9e3283115e8ae7ba25ac8a367faf715cac906da90415d7c415c51bb36f2`. `artifact-index.json` covers the report and retained evidence.

| Frozen product | Final SHA256 |
| --- | --- |
| `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/CheckoutServiceImpl.java` | `d8c13a71b874469abbfa8237fc6ac15dda45a5e14daf5610efc9575f2ad3d672` |
| `backend/shop-app/src/test/java/ai/neargo/shop/scenario/CheckoutPlaceAuthorityFlowTest.java` | `b34576b6e9f0fc1a64747246e4137f3f13f896578cd10a2c674d4cc8a812eb28` |
| `scripts/verify-checkout-payment.mjs` | `3e7d0b567cc714559707073e066a948f3d520e20467bac2338045955083c42af` |
| `scripts/tests/checkout-payment-runner.test.mjs` | `df965ff4663edb210cafb601af022edce189cc394dc9f792879bb9384971c929` |

No further product edits planned. Root independently verifies and obtains the same module reviewer’s narrow I1/fix-delta verdict before code-gate closure. BM05/P2–P4/formal deployment are not delivered.
