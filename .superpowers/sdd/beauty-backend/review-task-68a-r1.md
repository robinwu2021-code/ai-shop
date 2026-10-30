# Task68A independent review — scoped R1

I1: **ADDRESSED**. Spec: **PASS**. Code quality: **APPROVED** for the fix1 delta.

New Critical: **0**. New Important: **0**. New Minor: **0**. No actionable new finding in the reviewed delta.

This is a static closure of R0 I1 under the explicit root ruling in `task-68a-fix1-brief.md`. It does not claim runtime acceptance, pricing activation, or completion of Task68B/C or the cash chain.

## Scope and evidence

Read the binding fix1 brief first, R0 I1 verbatim, the appended fix1 self-report, the R1 pins, and the complete 62076-byte R0→current package once in non-overlapping chunks. Reviewed the eight changed source/artifact paths and appended report only. Untouched architecture, P3, root metadata/ER/ledger and the prior R0 conclusions were not re-audited.

Package SHA256: `fe7a33f61fba39298ff6e651e2d2365e3a1779a7f388cf56dd9415d5cecad686`. Independent current SHA256 checks match all eleven source pins and the report pin in `review-task-68a-r1-pins.json`. The three unchanged guard files retain their R0 hashes. Hashes of the copied V543 and descriptor in target/classes match the current source resources.

Read the supplied fix1 bounded compile log: BUILD SUCCESS, 24 reactor entries, 40.347s, shop-core 1067 main / 86 test and shop-app 413 main / 750 test sources. This supports the reported compilation evidence; this reviewer did not run a build or test.

## Why I1 is addressed

- `backend/shop-app/src/main/resources/db/migration/V543__counter_campaign_owner_fence.sql:4` now leaves tenant_no/scope_no without explicit charset or collation; line 11 leaves the table without either default clause. The database therefore supplies their comparison semantics. scope_kind at line 5 remains explicitly utf8mb4_bin. This is consistent with the root's explicit override of the original binary tenant/scope design, rather than an undocumented relaxation.
- `backend/shop-core/src/main/java/ai/neargo/shop/marketing/campaign/mapper/CounterCampaignFenceMapper.java:54` inspects exactly the seven tenant_no and three entity_no effective COLUMN bindings. Its mapped statement disables cache and flushes the local cache. The existing column query now includes charset; the table query includes the effective table and database charset/collation.
- `backend/shop-core/src/main/java/ai/neargo/shop/marketing/campaign/CounterCampaignFence.java:204` requires ten expected and ten actual unique bindings, rejects missing/extra/duplicate entries, requires utf8mb4 and a common nonblank utf8mb4 collation C, and verifies the actual set equals the packaged set. At line 155, that C is then required on both fence identity columns, the table default and the database default. A differing historical column override cannot pass merely because the database/table defaults match. Existing schema checks and failure/rollback behavior remain in the reviewed context.
- The truth, packaged descriptor and renderer express DATABASE_DEFAULT/OWNER_IDENTITY explicitly. The renderer rejects omitted bindings and binary identity fallback, emits the inherited table DDL, and preserves its read-only check branch. The delta leaves all 21 trigger bodies and byte-exact immutable identity/parent/link checks unchanged. Caller and trigger strings are preserved; no guessed normalization, old migration rewrite, alias omission or broader global fence is added.

When validation succeeds, the same collation C governs the owner predicates and fence key equality. Case, accent or padding aliases equivalent under that C therefore address the same durable tenant/entity PK; both raw writers and canonical readers contend on that key. The incompatible-source case is deliberately unavailable, as the ruling requires, instead of silently choosing another equality rule.

## Test-source assessment

The six added native methods are meaningful authored coverage of this correction. `CounterCampaignFenceIT.java:346` races real case-variant campaign/activity INSERT, UPDATE and DELETE against a canonical reader and verifies canonical ordinary owner queries include the rows. The accent/trailing-space and tenant/enrollment cases check equivalence using the actual fixture column collation and preserve rejection of byte-mismatched parent linkage. The race helper establishes the durable row first so the retained reader lock is S, then requires the raw mutation to wait until the reader transaction ends.

The effective-column override loop at `CounterCampaignFenceIT.java:432` independently changes each of the ten source columns while retaining table defaults. Additional negative source cases remove a required binding and drift fence column, table and database defaults. They check strict validation failure, dependency-unavailable on lock, and rollback of a real outer-transaction update even when the lock exception is caught. DDL restoration is present in finally blocks. The four Node cases now assert inherited DDL, the exact ten bindings, preserved binary scope/parent rules and rejected policy omissions. No strict lineage guard was weakened.

All **18 native cases, 4 Node cases and lineage/guard cases remain UNEXECUTED**. These are source assessments, not proof that database DDL, collation metadata, blocking order or exception translation has passed at runtime.

## Limits and remaining verification

No tests, builds, renderer, SQL/DDL, migrations, native environment, probes, serializers, application startup, network, index/staging mutation, commit, product edit or subagent was run by this reviewer. Only this review report was written.

Actual environment compatibility, all ten effective column collations, information_schema behavior, alias races, DDL restoration, timeout/deadlock behavior, ordinary/M1 startup and ablation remain unified-phase runtime checks. Existing owner-column or database-default incompatibility may legitimately close the new capability and require the separately reviewed deployment/data decision described in the root ruling. Task68B owner reads must preserve the reviewed equality contract; Task68C still owns runner/setup and runtime evidence. Those planned boundaries are not defects in this fix delta.
