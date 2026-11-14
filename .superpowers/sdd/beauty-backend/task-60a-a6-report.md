# Task60A-A6 report

Status: DONE. Implementation BASE `58024f052053d092b0ffbbf0e3376207fc9d9983`; worktree `/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex`, branch `codex/beauty-foundation`. Tier 0, binding main TDD §2.30 and A6 brief. Root retains independent spec/quality review and commit authority. No claim of Task60A/BM04/P1–P4 completion.

## Product scope and behavior

Exactly two owned product files:

- `scripts/check-sql-portability.mjs`: exports frozen fixed two-root `SCAN_ROOTS` and `scanSqlPortability(root, scanRoots=SCAN_ROOTS)`, returning `{hits,fresh,stale,knownCount}`. Direct CLI calls this same function with default roots. Import has no scanning, output, or exit effects. Missing scan roots throw visibly. Existing `target`, `node_modules`, `.git` directory skips remain; `src/test` is covered. CLI argv offers no root-exclusion path.
- `scripts/tests/sql-portability-scope.test.mjs`: nine runtime fixture tests exercising production function and actual byte-copied checker/mask CLI in temporary repositories; fixtures always cleaned in `finally`. No bad production SQL/Java migrations were written.

Java source comments/character literals are skipped, ordinary strings/textblocks are extracted independently, and basic Java escapes are decoded. A per-character source-offset map retains original file lines even when a Java `\\n` becomes a payload newline. Each SQL payload and `.sql` file uses the single original `maskSqlNoise` and unchanged ten `RULES`. Baseline remains `backend/known-sql-dialect.txt`, keyed by file + TAB + rule without source line.

Static boundary: scans individual literal contents conservatively; no Java AST, Unicode preprocessing, octal escape decoding, full textblock incidental-indent normalization, concatenation/evaluation, interpolation, method flow, or runtime SQL resolution. SQL assembled across literals/runtime values may remain undetected; no complete Java/runtime SQL claim. Textblock newlines and basic escaped quotes/backslashes/whitespace are handled. Strings resembling SQL can be treated as SQL candidates without semantic context.

No SQL, Java, SQL mask, known baseline, package/POM/lock, other generator/script, or root documentation change. No stage/commit/push/merge/native/deploy/subagents. Parent-owned concurrent `progress.md` change is excluded from product scope and untouched.

## TDD and actual command evidence

All logs below are in `task-60a-a6-evidence/`.

1. Necessary interface-only extraction first retained backend-only root and whole-file Java SQL masking. Snapshot: `interface-only.mjs`. No import/export/environment error counted as RED.
2. `node --test scripts/tests/sql-portability-scope.test.mjs` → exit **1**, **9 tests / 2 pass / 7 assertion failures** (`behavior-red.log`). Shared-rule test actually found backend SQL only; copied actual CLI wrongly exited 0 for bad beauty SQL and Java textblock. Stale baseline and import tests already passed.
3. Implemented two roots and bounded Java extraction. Same full command → exit **0**, **9 pass / 0 fail** (`green.log`). Tests cover both roots, SQL/annotation/textblock hits, second rule, exact original lines, Java escapes, SQL/Java noise with adjacent real bad payloads, fresh/stale exact path/rule baseline, no exclusion argv, missing root, original directory skips, and import without directories.
4. Actual worktree `node scripts/check-sql-portability.mjs --check` → exit **0** (`actual-cli.log`): **162 hits / 51 files / knownCount 54 / fresh 0 / stale 0**. Actual exported function count probe confirmed these exact fields. No newly uncovered real violations or baseline ruling needed.
5. `git diff --check -- scripts/check-sql-portability.mjs scripts/tests/sql-portability-scope.test.mjs` → exit **0** (`diff-check.log`). Only scoped Node tests and the actual portability gate were run; no unchanged broad business/native suite repeated.

Actual CLI output:

```text
SQL 方言扫描｜命中 162 处 / 51 个文件（已知欠账 54 条）

✓ 没有新增的方言依赖
```

## Required production mutations and exact restore

`ablations.json` records actual commands, mutated/restored SHA256; each `*.diff` contains the minimal production mutation. Mutations were applied sequentially and restored in `finally`.

- Remove only beauty from production `SCAN_ROOTS`; `node --test --test-name-pattern=badBeautySqlFailsCliCheck scripts/tests/sql-portability-scope.test.mjs` → exit **1**, **1 test / 0 pass / 1 assertion failure** (`without-beauty-red.log`, `without-beauty.diff`). This runs the actual copied CLI and observes its incorrect exit 0.
- Replace only payload selection with `[{ text: source }]` so the same SQL mask again erases whole Java literals; `node --test --test-name-pattern='beautySqlAndJavaUseTheSamePortabilityRules|badBeautyJavaTextblockFailsCliCheck|sqlNoiseDoesNotCreateHits' scripts/tests/sql-portability-scope.test.mjs` → exit **1**, **3 tests / 0 pass / 3 assertion failures** (`whole-java-mask-red.log`, `whole-java-mask.diff`). Missing real Java hits fail both pure-function and actual Java CLI paths; noise test requires neighboring real hits.
- Restored exact frozen bytes after both mutations: checker SHA256 **`f743c0c4706a5789541c66f6ddebdeb90d5e49e5e32ace3122f8ed0dfdafef29`** (`checker-frozen.mjs`). One final full `node --test scripts/tests/sql-portability-scope.test.mjs` → exit **0**, **9 pass / 0 fail / 0 skip** (`final-node-green.log`). Product source unchanged since that final run.

## Freeze, manifest, and self-review

`manifest.json` is the exact two-file manifest with BASE/final SHA256, sizes, Node version, unchanged RULES proof, frozen dependency hashes, and actual gate counts. `product-base.diff` includes the **entire BASE-to-worktree product diff plus the new test file**, not only tracked-file diff. No staging performed.

Frozen files byte-identical to both implementation BASE and root-provided source baseline:

| File | SHA256 |
|---|---|
| `scripts/lib/sql-mask.mjs` | `06d8a13a364fdc911433cfecc4e989d8a3f27a55c63bdbbf5994a2b275df7e48` |
| `backend/known-sql-dialect.txt` | `0edd37e78ae6e67d805327e9f2afe33361b6cee403a3bd75e0072bbc1c96f502` |

Self-review checked the brief ACs against implementation and actual tests: one rule/mask path, fixed production roots, matching preserved baseline behavior, accurate escape-to-file line mapping, no cross-literal regex matching, genuine import isolation, real CLI exit assertions, exact restoration, and two-file product scope. No known blocking finding. Author self-review is weaker than independent review; parent must perform fresh spec/quality gate before exact commit. Static Java limits above are intentional; no deviations from approved scope, no hidden debt waivers, and no A7/A11 work.
