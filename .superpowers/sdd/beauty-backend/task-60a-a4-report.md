# Task60A-A4 implementation report

Status: implementation complete for root independent review; no stage/commit/push/merge/deploy performed. Tier 0, accepted A4 brief is requirements/exact-values authority. Worktree `/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex`, branch `codex/beauty-foundation`, implementation base `756c918808db1fbdb59e4c652355823be435e4ef`; tracked worktree was clean at dispatch. A3 predecessor is accepted; no historical report/replay overhaul performed.

## Result and scope

Exactly five owned product files, recorded with absolute paths/bytes/SHA-256 in `task-60a-a4-evidence/owned-product-manifest.json`. New `SchemaDdl.java` is package-private and test-only. Both real guard consumers call `SchemaDdl.createColumns(String)`; neither Test depends on the other Test. `SchemaDriftTest` migration ordering and rename/drop/add replay remain unchanged. JS reuses the existing ALTER `splitTopLevel` for CREATE, with one quote/comment lexer; the output Map/object shape, type/nullability/comment/index collections remain intact. CREATE definitions split only at top-level commas, complete type tokens prevent CHAR from accepting CHAR_LENGTH, and constraints are classified as whole definitions (no AND blacklist). Matching CREATE parentheses and the next-CREATE boundary prevent swallowing another table; missing quotes/comments/open/close/terminator and extra close parentheses fail explicitly. JS table options still permit multiple lines and the existing exactly-600-character tail bound.

Python generator, constraint test file, schema fixture, all applied SQL, POMs, known files and entity/owner registrations remain unchanged. No new dependencies, Java production/entity changes, native server, Docker or deployment. Root-owned progress/main TDD/preflight changes are outside this product manifest and were not restored or edited.

## Requirements → design → implementation → tests

| Brief requirement | Implementation | Real behavioral assertion |
| --- | --- | --- |
| CREATE definitions, complete type tokens, no fake CHECK/AND columns | ddl.mjs shared lexer/splitter; SchemaDdl structural reader | existing `column lexer separates inline fields without treating multiline CHECK clauses as columns`; `multiline V517 and V533 checks cannot become AND columns`; Java `checksAreNotColumns` |
| Frozen V506 one-space columns and V517/V533 checks; shared Java helper | JS frozen-file cases; both Java guard callers | `frozen V506 checks remain constraints at one-space indentation`; `singleSpaceColumnsAndMultilineChecksAreParsedStructurally`; full `migrationAndTestSchemaMatch` |
| Quotes/backticks, doubled/escaped strings, comments, nested expressions; retain metadata/indexes and existing ALTER | reused JS splitTopLevel/sqlText; Java helper | `quotes comments nested expressions and ALTER preserve column metadata and indexes`; Java `checksAreNotColumns`; generator parity and real H2 load |
| Explicit malformed failure and bounded multiline tail | structural matching/boundaries; JS 600 tail | `malformed definitions fail without swallowing the next table`; `CREATE table tail keeps the existing 600 character limit and multiline options`; Java `malformedCreateCannotConsumeFollowingTable` and malformed cases through generator parse |
| Preserve original replay and frozen generator/fixture | existing replay remains; readonly generator/H2 execution | Java single-space test also exercises table/column rename, drop column, add column and drop table; `generatorReproducesCommittedSchema`; readonly Node constraint test |

The implementation file list matches the approved five-file design. `product-base.diff` contains all five product changes, including the untracked new helper via no-index diff against `/dev/null`; no new helper is missing from the review diff.

## Behavioral RED → GREEN → ablation → exact restore

Initial RED was recorded before creating SchemaDdl: Node 7 tests, 5 failures; inline fields disappeared and AND became a false column; frozen V517 returned 24 rather than 23 columns. Java compiled and ran 6 tests with 4 assertion failures, 0 errors/skips: old real collectCreates/replay read only id in V506, generator parse missed inline fields, malformed input did not throw, and migration/schema equality failed. No missing-helper compile failure was counted as RED. Missing-open and extra-close syntax cases added a second focused behavioral RED (Node 1 failed; Java 2 failures/0 errors/skips) before tightening those explicit malformed checks.

Initial repaired Node/Java guards passed. Product sources were then frozen with a five-file byte/hash manifest before final verification and mutation. Minimal ablation changed only the CREATE definition separator: JS splitTopLevel(body) to body.split(newline), Java top-level comma condition to newline. Node became 5 pass/2 fail (missing delivery_status/title); Java two selected methods became 1 pass/1 fail, with real SchemaGeneratorTest parse omitting title. The frozen V506 method remained green under this particular separator mutation; it is not falsely reported as failing. `ablation.diff` records both one-line mutations.

All five files were restored byte-for-byte from snapshots and compared against the frozen manifest; `ablation-exact-restoration.json` records matching SHA-256 values. Final Node full selector: 7 tests, all pass, 0 skip, exit 0. Final Maven full two-class selector: 6 tests, 0 failures/errors/skips, exit 0. Fresh copied XML: SchemaDriftTest 4 tests; SchemaGeneratorTest 2 tests. `final-surefire-summary.json` records exact XML hashes/mtime and zero failure/error/skip counts. H2 execution here validates a generated test fixture, not a MySQL deployment. No product source changed after freeze other than the temporary mutations and their exact restoration.

## Commands and genuine shared-suite failure

All commands ran from the sole worktree. Full commands, exit codes, result counts and actual log paths are in `commands.json`; logs are retained, including failed runs. Final validation commands:

```sh
node --test scripts/tests/ddl-column-definitions.test.mjs scripts/tests/test-schema-constraints.test.mjs
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 -f backend/pom.xml -pl shop-app -am test -Dtest=SchemaDriftTest,SchemaGeneratorTest -Dsurefire.failIfNoSpecifiedTests=false
```

Shared requested command initially exited 1 before running tests because pnpm v11 did not locate the existing vitest ancestor binary. Re-running with the already-existing worktree node_modules/.bin on PATH (no install/dependency change) executed exactly the two requested files: **22 tests, 19 pass, 3 fail; whole suite remains RED**. The filename `shared-green-scope.log` is historical naming only, not a green claim.

1. `entity-alignment.test.ts:263` — 12 tables lack entity/NO_ENTITY_YET registration: sys_outbox_stream, sys_outbox_delivery, sys_outbox_operation, prd_stock_sync_operation, prd_stock_alignment_batch, prd_stock_alignment_line, ord_refund_coupon_scope, ord_refund_obligation, inv_outbox_stream, inv_outbox_delivery, inv_outbox_operation, inv_event_consumed. This is the explicit A5 debt; no registration/guard weakening was done.
2. `schema-lineage.test.ts:454` — nine unclassified cross-table keys, detailed below. Root accepted this as an A5b candidate for later source audit, not released here.
3. `schema-lineage.test.ts:477` — gen-erd KEY_OWNERS differs: 18 missing generator keys plus voucher_no maps to pts_voucher in generator and sv_voucher in the test. Test duplicate voucher_no at lines 134/148 overwrites its earlier value. Also A5b candidate, not fixed/released in A4.

Read-only dynamic import of the implementation-base ddl module from /tmp using the same actual worktree root showed **363 tables before and after, no added/removed tables, identical all-nine key→table memberships**. Thus these are existing owner/classification debt, not parser-created table/column differences. `baseline-table-attribution.json` and `lineage-owner-attribution.json` retain precise facts. The side-effecting shared suite was not rerun to manufacture a baseline.

| Unclassified key | Actual tables (same before/after) |
| --- | --- |
| receipt_no | stl_payment, ent_migration_return |
| source_event_no | sys_outbox, pmt_effect_projection |
| actor_account_no | mch_staff_log, ord_checkout_payment_request, stl_payment_operation |
| execution_no | ord_refund_execution, ord_refund_obligation |
| entry_no | pts_ledger_entry, ent_ledger |
| line_no | prd_stock_alignment_line, sv_posting_line |
| catalog_no | prd_store_catalog, prd_store_catalog_version |
| buyer_no | ord_checkout_operation, ord_checkout_confirmation, ord_checkout_payment_request, stl_payment_operation |
| confirmation_no | ord_checkout_operation, ord_checkout_confirmation |

Missing generator owners: tier_no→pmt_recharge_tier, grant_no→ent_grant, instance_no→ent_instance, usage_no→ent_usage, source_order_no→ord_order, recharge_no→sv_recharge, lot_no→sv_lot, exception_no→pay_ent_exception, recon_no→pay_ent_recon_run, stage_no→ent_migration_stage, alias_no→ent_migration_alias, checkout_no→ord_legacy_coupon_checkout, plan_no→ent_migration_plan, import_no→ent_migration_import, return_no→pmt_migration_return, target_template_no→ent_template, template_version_no→ent_template_version, activity_version_no→pmt_activity_version.

## Freeze, formatting, incidental side effects and self-review

`frozen-sources.json` compares base/current/final SHA-256 for 553 tracked SQL/known/POM/Python/constraint-test/registration/owner sources: every entry unchanged. The original 15-item root source baseline is consistent: the four existing owned files changed; the eleven frozen entries did not; new helper has its own manifest entry. Frozen V506/V517/V533 were read directly, never rewritten. No schema-test.sql generation was persisted.

AOSP formatter used `/tmp/print-google-java-format.jar` (1.25.2), only selected new/changed Java methods and the entirely new helper. Formatter before/diff evidence is retained. An incidental rewrite of old SchemaDriftTest.RENAME declaration was inspected and restored before final tests. No whole old file reformat or incidental provenance churn remains; all three changed Java files are at most 120 characters per line.

The requested shared suite imports gen-erd, which rewrote six generated tracked artifacts; pnpm also created an untracked packages/shared/pnpm-lock.yaml. Root explicitly approved restoring only those six generated files to BASE bytes and removing the lock after confirming it is absent in BASE. `incidental-restoration.json` records each base/restored SHA, all equal; lock absent in BASE and after. Generated intermediate SHA values were not captured before restoration; base/restored proof is retained instead. The six restored files:

- docs/technical/diagrams/db-mbr.svg — 88a9ff2180a49fb14e09dd7ac39c49223fec35b99fe090dd02b6246168b3a489 = 88a9ff2180a49fb14e09dd7ac39c49223fec35b99fe090dd02b6246168b3a489
- docs/technical/diagrams/db-ord.svg — 113d90828038ff3b7d1ab8c1a707255cef8e549cb74ec379cf408dc5de17dc52 = 113d90828038ff3b7d1ab8c1a707255cef8e549cb74ec379cf408dc5de17dc52
- docs/technical/diagrams/db-overview.svg — 0ce278323ce95673fc8d4370c6de2bcaaa22f73fdb6c73b10001d3343e922e1c = 0ce278323ce95673fc8d4370c6de2bcaaa22f73fdb6c73b10001d3343e922e1c
- docs/technical/diagrams/db-prd.svg — de69ff46ffcb48c595e85b00740f8bc0cb80af34f21250c5a5bb4c5f2338c06d = de69ff46ffcb48c595e85b00740f8bc0cb80af34f21250c5a5bb4c5f2338c06d
- docs/technical/diagrams/db-stl.svg — 7b589c12d3a83e212e1f9d2d8c21140badf2e0212fce2bf64b0b2e0be3efba48 = 7b589c12d3a83e212e1f9d2d8c21140badf2e0212fce2bf64b0b2e0be3efba48
- docs/technical/reference/数据库-ER图.md — 2d8bc86515207b813d9b3d00c32056d7fef8d5fb69dced913d798b626bc3a9fd = 2d8bc86515207b813d9b3d00c32056d7fef8d5fb69dced913d798b626bc3a9fd

The first restoration script used quoted non-ASCII git paths and stopped after restoring five SVGs; a NUL-delimited rerun restored the markdown file and completed cleanup. This tool hiccup is not counted as a product test failure or suppressed; a following Node command on that first invocation still passed. No unowned product content was edited to obtain green results.

Self-review checked both real helper call sites, one scanner per language, full type boundaries, no AND blacklist, next-table/malformed guard, 600 tail boundary, output metadata/indexes, frozen actual migration columns, replay preservation, exact five-file scope, diff whitespace and formatter scope. `owned-diff-check.log` is empty (exit 0). Remaining root document modifications belong to root. No commit/stage or release claims; this report covers only A4 guard/parser repair. Shared registration/classification debt remains visible and is assigned as above.

## Owned products (frozen for root review)

- `scripts/lib/ddl.mjs` — 15765 bytes — SHA-256 `1ee74b5cdc14fff3fbfea65e67c017241ba3b35a7e83c3fc27b06e20589963ab`
- `scripts/tests/ddl-column-definitions.test.mjs` — 5595 bytes — SHA-256 `2a4d3a37e53489c62a2bb96f117daaa4462dde77e4b0ebd2d8c0d370a2836450`
- `backend/shop-app/src/test/java/ai/neargo/shop/infra/SchemaDriftTest.java` — 14066 bytes — SHA-256 `5ee254036041231e99cecbcc0ec746191da2fc8345ee33d7d912f145db355369`
- `backend/shop-app/src/test/java/ai/neargo/shop/infra/SchemaGeneratorTest.java` — 8295 bytes — SHA-256 `f283934bc1a4418c12a855be9bc6e7f4599207e239ff85fc57d35d9f00686de4`
- `backend/shop-app/src/test/java/ai/neargo/shop/infra/SchemaDdl.java` — 6963 bytes — SHA-256 `fb79de912d0f616a2a362ecd3f13f8649881f61c06e5aea1ee1e56065c4fb164`

Product diff SHA-256: `84b7353b29f34936cef9b859d735d48f2d69e7bdcef8ce57b7572a6032fa5187`.


# R1 fix round — Important I1

Status: R1 implementation complete for independent review; this section supersedes R0's unqualified ALTER compatibility conclusion. Root authorized only the existing owned JS parser/test files for this round, and confirmed both statement-boundary repair and downstream full-clause extraction are necessary to close I1. The immutable R0 source/report/manifest is `/tmp/task60a-a4-r0-frozen-source`; R0 logs/diffs/XML/ablation evidence remain intact. Original report and manifest copies are also under `task-60a-a4-evidence/r1/report-r0.md` and `owned-product-manifest-r0.json`. Implementation BASE remains `756c918808db1fbdb59e4c652355823be435e4ef`.

I1 was verified: the earlier ALTER regex ended a statement at the first raw semicolon, passing an incomplete quoted body to the newly strict shared lexer. Its downstream `[^;]*` extractor independently truncated complete comment metadata, so fixing only the first regex would not suffice. R1 uses the existing `sqlText` quote/comment mask to find complete ALTER statements and the existing `splitTopLevel` for structural clauses. The four existing column operations now consume an anchored complete clause with its full tail; ADD/RENAME/DROP/MODIFY operation bodies and earlier rename/history stages retain their semantics. There is no additional lexer, dialect layer or dependency. CREATE validation and the exact 600 tail bound are unchanged.

Three regressions call actual readSchema through temporary SQL files: a quoted-semicolon ADD with complete comments and a subsequent ALTER; multi-clause ALTER with quoted semicolons/commas, doubled quotes, double-quoted defaults, decimal parentheses and line/block SQL comments containing fake ALTER statements; and downstream MODIFY metadata followed by RENAME/DROP and a subsequent ADD. Assertions compare complete column objects including names/types/nullability/comments. They demonstrate both total-read compatibility and full metadata/subsequent-statement preservation.

Genuine RED was captured before implementation in `r1/node-red.log`: the prior seven tests passed, while all three new legal ALTER cases failed with `Malformed SQL: unclosed quote` through actual readSchema→old ALTER replace→splitTopLevel→sqlText. Initial GREEN is `r1/node-green.log` (exit 0, 10/10). The exact five-source R1 manifest was then frozen, and final verification ran on those exact bytes:

```sh
node --test scripts/tests/ddl-column-definitions.test.mjs scripts/tests/test-schema-constraints.test.mjs
```

Final `r1/node-final-green.log`: **10 tests, 10 pass, 0 failures/cancellations/skips, exit 0**. This includes the unchanged test-schema-constraints file plus all existing malformed CREATE/next-table and 600-tail assertions. Exact commands/exit codes/counts are recorded in `r1/commands.json`. No Java, shared Vitest, broad suite, native/Docker or deployment run was repeated in R1. The three Java source hashes are byte-identical to immutable R0; the existing R0 six-test Maven XML/log proof remains preserved and is not misrepresented as a new R1 run. Review M1's existing compiler diagnostics remain visible in the original Maven log; no suppression or out-of-scope fix was made.

`r1/source-comparison-r0-r1.json` verifies exactly two changed source files and three unchanged Java files. All 553 frozen sources still match BASE (`r1/freeze-check.json`), including SQL/Python/schema-test.sql/known/POM/registration/owner files. The three previously recorded A5/A5b shared-suite failures remain unresolved and are not called green; the side-effecting suite was not rerun. Root documents remain untouched. No stage/commit/subagent/push/merge/deploy.

Review artifacts: `r1/r0-to-r1.diff` contains only this two-file fix; `r1/product-base.diff` contains the full five-product BASE diff including new untracked SchemaDdl.java. The original R0 product-base.diff is retained. Canonical `owned-product-manifest.json` and `r1/owned-product-manifest.json` now describe R1 exact bytes. No product source changed after final source freeze.

| Owned product | Bytes | R1 SHA-256 | R0→R1 |
| --- | ---: | --- | --- |
| `scripts/lib/ddl.mjs` | 15969 | `89cf5d38f8f9250ba91bd908af13efef278f5b7bdbbd39ec9f325702c80c81cd` | changed |
| `scripts/tests/ddl-column-definitions.test.mjs` | 8288 | `c055dd81b28737cb518e1a8f1eb676b9e55144ddc385ca6b4254a9e68fcdebe4` | changed |
| `backend/shop-app/src/test/java/ai/neargo/shop/infra/SchemaDriftTest.java` | 14066 | `5ee254036041231e99cecbcc0ec746191da2fc8345ee33d7d912f145db355369` | unchanged |
| `backend/shop-app/src/test/java/ai/neargo/shop/infra/SchemaGeneratorTest.java` | 8295 | `f283934bc1a4418c12a855be9bc6e7f4599207e239ff85fc57d35d9f00686de4` | unchanged |
| `backend/shop-app/src/test/java/ai/neargo/shop/infra/SchemaDdl.java` | 6963 | `fb79de912d0f616a2a362ecd3f13f8649881f61c06e5aea1ee1e56065c4fb164` | unchanged |

R0→R1 diff SHA-256: `23c2c58b7dae1520f15564f848a467e7dc2ebab172c362a4af756b6069bb2fc6`.

R1 full BASE diff SHA-256: `f598ee689789dbf09e595125a8786b25f868c107355a513cb10f216db93896dd`.

R1 five-file manifest SHA-256: `348be17417a6865cc72e8e592ea743071f4c0e303941c957eb36b055a85576b1`.
