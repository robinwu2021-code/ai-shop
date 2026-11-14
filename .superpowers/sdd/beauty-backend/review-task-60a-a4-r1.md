# Task60A-A4 R1 scoped re-review

Worktree: `/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex`.
Implementation BASE remains `756c918808db1fbdb59e4c652355823be435e4ef`; R1 is uncommitted. Review comparison is the immutable R0 snapshot `/tmp/task60a-a4-r0-frozen-source` to the R1 two-file fix, not BASE to R1.

## Finding Verdicts

**I1 — ADDRESSED.** Original finding, verbatim as dispatched:

> A legal quoted semicolon in ALTER now aborts schema parsing. scripts/lib/ddl.mjs:325 calls newly strict sqlText, whose :279 throws on an unclosed quote. Existing ALTER caller :184–185 uses /ALTER TABLE\s+(\w+)\s+([\s\S]*?);/gi, passing truncated body for ALTER TABLE probe ADD COLUMN title VARCHAR(80) COMMENT 'left;right';. Actual temporary fixture readSchema throws. Ensure splitter receives complete ALTER statements using quote/comment-aware boundaries or preserve ALTER contract retaining strict CREATE validation; add legal regression. Do not weaken malformed CREATE checks.

The locations in that finding refer to R0. In R1, `scripts/lib/ddl.mjs:318` introduces `alterActions` using the existing `sqlText` mask: the ALTER header is found in code, and `:323` selects a semicolon in that masked code. Quoted/comment semicolons therefore cannot truncate the body passed at `:325` to the existing structural `splitTopLevel`. Comment-stripped, literal-preserving text supplies the complete action. This reuses the same lexer rather than adding a blacklist or another dialect parser.

The downstream change at `scripts/lib/ddl.mjs:182–191` is necessary and correct: an anchored full-action regex captures the complete tail with `[\s\S]*`, replacing the old independent `[^;]*` truncation. The ADD/RENAME/DROP/MODIFY switch bodies and renamed-table fallback retain their prior behavior. Earlier history, table rename/drop/comment and standalone-index stages are unchanged. CREATE validation, structural splitter validation, output collections and the 600-character table-tail bound are unchanged by this fix.

The three new tests exercise actual `readSchema` through temporary SQL files, with full column-object assertions:

- `scripts/tests/ddl-column-definitions.test.mjs:121`: the exact legal quoted-semicolon ADD, complete comment, nullability and a following ALTER.
- `:134`: multi-clause ALTER, single/double and doubled quotes, decimal parentheses, quoted commas/semicolons, SQL comments containing fake ALTER statements, and a following statement.
- `:155`: complete MODIFY metadata followed by ADD, RENAME, DROP and another ADD.

`r1/node-red.log` records 10 tests / 7 pass / 3 fail, with all three new legal cases failing on `Malformed SQL: unclosed quote` through the R0 caller. `r1/node-final-green.log` records 10 tests / 10 pass / 0 fail / 0 cancelled / 0 skipped; `r1/commands.json` records exit 0 for the final exact selector. The existing malformed CREATE, next-table, metadata/index and 600-tail tests are part of that retained green evidence. No weakened CREATE check appears in the fix diff.

## New Breakage

**Critical: none found. Important: none found in the R1 fix.**

The complete-action extraction preserves existing supported operation semantics and fixes both statement and metadata truncation. Statement/comment masking and structural clause splitting are consistent with the accepted lexer contract. No specific unanswered code risk warranted an extra probe. This is a bounded fix review; it does not certify new SQL dialect support.

The R0→R1 diff contains only `scripts/lib/ddl.mjs` and `scripts/tests/ddl-column-definitions.test.mjs`. Independently hashed current bytes of all five canonical owned products match the R1 manifest. The root evidence gate records all 553 frozen sources unchanged and all three Java files identical to R0. Java was appropriately not rerun; its retained R0 proof is six tests / zero failures, errors or skips for those same Java bytes.

## Out-of-Scope

No new out-of-scope observations were raised. Existing M1 compiler diagnostics remain Minor debt for A11, outside this fix. The previously recorded shared suite remains 22 tests / 19 pass / 3 fail, assigned to A5/A5b; R1 does not resolve or relabel it. H2 evidence concerns a fixture, with no native MySQL, Docker, deployment or release claim. Later whole-branch checks remain the root's responsibility.

## Fix-round verdict

**APPROVE R1 for this scoped fix round. I1 is addressed; no new Critical or Important issue was found.**

Evidence identity independently checked:

- R0→R1 fix diff SHA-256: `23c2c58b7dae1520f15564f848a467e7dc2ebab172c362a4af756b6069bb2fc6`.
- Canonical five-product manifest SHA-256: `348be17417a6865cc72e8e592ea743071f4c0e303941c957eb36b055a85576b1`.
- Implementation report SHA-256: `488ea912f929c6dd97182e3415695bef12ab576ace452529db470e5569d236c3`.
- Root evidence gate read: `/tmp/task60a-a4-r1-root-evidence-gate.json`.

Read the supplied fix diff once and inspected its immediate parser integration plus the named report, original review and R1 logs/commands. No passed suite was rerun, no focused probe was necessary, and no git command, product/source/index/HEAD edit, subagent, native/Docker action, deployment or root-document write was performed. Only this review report was written outside the checkout.
