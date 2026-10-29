# Task60A-A11 report — products frozen for independent review

BASE `d54e3f1b3b9a4ad391bd4917c3ebb1cee0e7ac57`; branch `codex/beauty-foundation`; exact assigned worktree. Tier 0, released A11 brief; no contract/config/schema/permission change. Root owns plan/ledger, final BM04 reconciliation, and later P1–P4 deployment gate.

## Product changes

Exactly the five released paths are in the tracked BASE diff (`product-manifest.json` has final SHA256 and `complete-BASE-owned.diff` the full diff). WalkInRecoveryIT has one new exact method plus five necessary imports; legacy code formatting is unchanged. Formatter range expansion was discarded outside the new method, as documented in `format-boundary.txt`.

`expectedRecoveryFailureEmitsAttributedWarningAndKeepsIntentRecoverable` uses ReceptionFixture's existing controlled resource owner: lose the committed CONFIRM response, inject brokenSource on its actual appointment, then run the real ReceptionRecovery scan without authentication. Its own started ListAppender captures only ReceptionRecovery and is detached/stopped in finally. It asserts exactly one attributed WARN (logger/template/two arguments/formatted message, no throwable or private fields), retryable RESOURCE_PENDING, unchanged request/key/derived-key and command receipt/payload evidence, cleared lease, exactly 60-second retry bound, no actor minting or work order. After clearing the fault and a 61-second fixture clock advance, scheduled recovery reaches ARRIVAL_PENDING; explicit authenticated recovery of the same original operation reaches SUCCEEDED with exactly one reception/work order and no duplicate resource commands. Context, clock, owner fault and appender cleanup are in finally. This is controlled-fault evidence, not a real owner replacement proof or the historical 55D case.

The runner adds only the finite WalkInRecoveryIT/new-method aliases mapping; tests cover exact class/method acceptance, unknown/prefix/old-method rejection, cross-mode and empty-PATH rejection before provisioning, plus exact XML method/zero-skips semantics through the existing helper. Defaults, other mappings, deployment gate and resource implementation are unchanged.

Scanner changes are only Java // termination at CR/LF, CRLF-aware lineOf, and bare-CR escape continuation. Seven real-scanner cases add LF/CRLF/CR fixtures, mixed source terminators, decoded Java newline offset and textblock continuation offset evidence. Existing nine cases, roots, rules, frozen mask and known baseline remain unchanged. Bad fixtures exist only in temporary directories with finally cleanup.

## Actual verification

| Batch | Command / selection | Actual result |
|---|---|---|
| Node corrected behavior RED | `node --test --test-reporter=tap scripts/tests/sql-portability-scope.test.mjs scripts/tests/beauty-bm04-wire-runner.test.mjs` before production fixes | exit 1; 22 pass, 4 fail, 26 total: missing finite selector, CR comment, CR continuation, mixed source line |
| Node first/final GREEN | same two finite files | exit 0; 26 pass, 0 fail, 0 skip |
| lineOf-only ablation RED | exact mixed-terminator test name pattern | exit 1; expected source line 4, actual 3 for Java/SQL; exact source restored in finally |
| Native first GREEN | `BEAUTY_BM04_TESTS=WalkInRecoveryIT#expectedRecoveryFailureEmitsAttributedWarningAndKeepsIntentRecoverable node scripts/verify-beauty-bm04-wire.mjs --mode=aliases` | exit 0; fresh XML 1 test / 0 failure / 0 error / 0 skip |
| Native LOG.warn ablation RED | same single selector; remove only exact two LOG.warn lines | exit 1; fresh XML 1 / 1 / 0 / 0; warning-count assertion at Java line 432 expected 1, actual 0 |
| Native restored GREEN | same single selector after exact source restoration | exit 0; fresh XML 1 / 0 / 0 / 0 |
| Actual SQL gate | `node scripts/check-sql-portability.mjs --check` | exit 0; 162 hits, 51 files, 54 known keys, fresh 0, stale 0 |
| Static scope/style | `git diff --check`, exact tracked-path set, added Java width | exit 0; five products; all added Java lines <=120 |

The earliest Node log includes a fixture diagnosis: the hand-counted DROP line was initially 7, actual physical line 6. Corrected the fixture expectation before implementing scanner changes; that count error is not claimed as an implementation RED. `node-corrected-red.log` retains the four genuine failures against the unchanged scanner/runner.

Native uses only the existing cached mysql:9.7.2 runner, pull never, DISK64M, colima-hxkey-tests context/VM, shared native lock, environment-only credentials and own UUID cleanup. MemAvailable values were 2902556 / 2893372 / 2912164 KiB (all >=524288). All three owned UUIDs are absent after cleanup and lock is absent, recorded per batch. No foreign resource was stopped. Native Maven compilation itself proves test integration and production mutation/restoration; no extra compile, whole class, unchanged broad suite or formal topology run was performed.

Production ReceptionRecovery source restored exactly to frozen SHA256 `f2f579e4e99efb13628c70a51bc9f934319aab21fd176fcd9ed13ff9c06e19c1`. Production class initial/restored SHA256 `9659c1fe5821fd2ce423a1bf8c2fb46e0e9021b876419343287dac1e8bcb7451`; ablated class `3a45972d57d95e05d3560588e5528afa767d8aeb42f007686a5b24bedb1bf1a1`. Both changed-production batches log actual javac recompilation. Source/compiled/test hashes and fresh XML are recorded per batch, and original/ablated production source snapshots are evidence only. sql-mask SHA `06d8a13a364fdc911433cfecc4e989d8a3f27a55c63bdbbf5994a2b275df7e48` and known SQL SHA `0edd37e78ae6e67d805327e9f2afe33361b6cee403a3bd75e0072bbc1c96f502` match the released values.

## Warning attribution and limitations

Actual output is retained in raw logs/XML and `warning-ledger.json`. Each successful native batch has seven Flyway warnings (MySQL 9.7 newer than verified 8.1) and exactly one expected ReceptionRecovery IllegalStateException WARN, with no throwable stack. The ablation has seven Flyway warnings and zero ReceptionRecovery warnings; its Maven ERROR lines are the intentional missing-warning assertion failure. Javac reports annotation processor discovery and BeautyCatalogIT deprecated API use. No unchecked/H2, duplicate mapper/mapperLocations/testconfig, Mockito-CDS/DNS, or Node MODULE_TYPELESS locale-loader warning appeared in these finite batches; those broader warning categories are not claimed resolved. No new unique-owner/bean/config warning or business correctness issue was observed.

No credential-bearing XML property was found; existing runner commands pass credentials through environment references only, and no real credential value was observed in archived artifacts (`credential-review.json`). No warnings were suppressed. Existing A3 first-success full-VO comparison and A8 business-only duplicate-boundary concerns remain root final-review triage. No stage/commit/push/merge/install/deploy occurred. All five products are frozen for independent specification/quality review; final BM04 code review/compile/reconciliation and formal deployment after all P1–P4 remain root-owned.
