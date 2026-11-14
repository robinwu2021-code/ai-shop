# Task60A-A5 independent review

## Spec Compliance

PASS for the supplied one-file uncommitted snapshot relative to BASE `6744b462ee13006d08f43216266a79f1bd4d482a`.

- Reviewed the supplied diff once. Its SHA-256 matches `c3bb47cb23b6eab2344e24a0fe3e7cddd7b6b37c068a99f5c96fc1f14f5a3cfc`. Current product source, implementation report, and owned-product manifest hashes match the supplied bindings. Product source SHA-256 is `2756d20054045dcc8cd6443130fa53d223ac4d84a602f402f4eda411180e06ea`.
- `packages/shared/tests/entity-alignment.test.ts:41-52` adds the brief's 12 exact registrations. The supplied root evidence gate confirms all 57 existing values are preserved and the anchored IGNORE literals are unchanged. The inspected diff adds no broader exemption or unrelated product change.
- `packages/shared/tests/entity-alignment.test.ts:146-152` implements the requested signature and exact own-key classification using `Object.hasOwn`; the actual guard at line 278 calls this helper. IGNORE remains anchored at lines 142-143. Missing-column, ghost-field, and stale-registration logic remains unchanged (lines 241, 259, 320).
- Lines 288-299 exercise actual parsed migration tables, scanned ORM entities, and current registrations with an unknown probe, then remove an actual new registration from a copy and require both that table and the probe. Lines 302-310 verify real schema presence and absence of ORM entities for existing points/logistics representatives and new owner tables before passing those same values through the helper. This is behavioral coverage, not an exact-registration-list mirror. Lines 313-317 reject inherited and default-prototype keys.
- Reasons identify real owners and alternative reads. Read-only owner inspection supports fixed sys/inv source-bound tables and JDBC stream/delivery/operation operations in JdbcOutboxStore, stock operation/batch/line JDBC access, RefundObligationMapper persistent SQL, and inventory inbox selection plus transactional insertion in OutboxConfiguration/TransactionalOutboxHandler. No unused ORM entity is introduced.

## Quality

PASS. No actionable correctness, maintainability, or test-quality finding in the scoped change.

The shared helper is small and pure apart from the unchanged non-stateful IGNORE expression; actual guard and negative tests share it. The own-key predicate closes the inherited-key hole without altering explicit registrations. Tests use real schema/entity inputs, reject unknown tables, and demonstrate that deleting a registration affects the real guard. Representative-table assertions are intentional behavior checks and do not impose a permanent registration count.

## Findings

- Critical: none.
- Important: none.
- Minor: none.

No fix requested.

## Existing evidence checked

Read existing logs and command records; did not rerun suites. All recorded runs use packages/shared cwd and `../../node_modules/.bin/vitest run tests/entity-alignment.test.ts --cache false`, Vitest 4.1.11.

- Actual old guard RED: 6 tests, 1 failed, reporting exactly the 12 requested missing tables.
- Behavior RED: 10 tests, 5 failed; concrete assertions fail, including inherited/default-prototype keys incorrectly returning an empty array. The saved test-first helper uses the prior `in` semantics, so this is not an import or missing-helper failure.
- Implemented GREEN: 10 passed.
- Single-registration ablation RED: 10 tests, 3 failed; real guard reports sys_outbox_stream, probe behavior and shared representative classification also fail. The recorded ablation diff deletes only that registration line.
- Final restored GREEN: 10 passed. exact-restoration.json records byte-identical restoration to the bound source SHA; root gate independently confirms exact restoration.

## CannotVerify / boundaries

This review is bound to the supplied diff, current scoped source, existing logs, manifest/report hashes, and root exact-diff evidence. It does not independently recreate historical run ordering or execution, rerun tests, or independently reconstruct BASE through git. No git command, product edit, native/Java/Docker/generator/deploy action, or subagent was used. The only write is this review report in /tmp.

No claim that the entire shared suite, Task60A, BM04, later milestones, or deployment is complete. A5b schema-lineage debt (nine business keys, 18 ERD omissions and voucher divergence) remains separate and was not rerun, repaired, or waived here.
