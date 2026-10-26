# Task68A independent review — R0

Spec: **FAIL**. Code quality: **NEEDS_FIXES**.

Critical: **0**. Important: **1**. Minor: **0**.

This is a static review of the shared marketing owner-fence prerequisite only. It does not claim runtime acceptance, pricing activation, whole Task68 completion, or a working cash chain.

## Reviewed scope and evidence

- Requirements: `task-68a-brief.md`, exact `task-68a-release-inventory.json`, and binding `task-68-design-addendum.md` §4, relevant §1/§5 and the fence scenarios in §6.
- Read `task-68a-report.md`, `review-task-68a-r0-pins.json`, and the complete 138593-byte `review-task-68a-r0-package.diff` once, in sequential non-overlapping chunks. Base: `791d5f6e086cd371a5821ae684894b41c7b42ca9`; target: the frozen working tree. Package SHA256: `c95048e52be16a7d9ffb73f1b03c92f0a5c5f0d4670ccb5d88fbe9de75206dd6`.
- Independently hashed all eleven released source/artifact files and the implementer report. Every hash matches the supplied R0 pins. No changed file was reread for source analysis.
- Focused outside-diff reads addressed specific risks: actual predicate table identities/indexes/defaults and collation (V1/V242/V337/V484), primary datasource/factory/transaction-manager wiring and mapper scanning, actual inherited fixture behavior, and existing owner equality predicates for the finding below. The existing runner was searched only for the native database/collation context. No whole-branch/P3 audit was performed.
- Read the three supplied bounded compilation logs. Their BUILD SUCCESS records and run 1/2 javac counts agree with the report: shop-core 1067 main / 86 test, shop-app 413 main / 750 test; run 3 reports success without a fresh javac invocation. This is inspection of existing compilation evidence, not a new build or test result.
- Root-owned ER/generated documentation and ledger changes are excluded from this eleven-file review.

## Important I1 — make fence identity agree with owner predicate identity

**Actionable location:** `backend/shop-app/src/main/resources/db/migration/V543__counter_campaign_owner_fence.sql:27`. The table declares binary identity columns at lines 4–6, while this INSERT trigger copies `NEW.tenant_no` and `NEW.entity_no` directly into the fence key. The same construction is used by the corresponding UPDATE/DELETE triggers and by merchant activity/enrollment scope resolution. Reader-side acquisition uses the supplied tenant/entity bytes in `backend/shop-core/src/main/java/ai/neargo/shop/marketing/campaign/CounterCampaignFence.java:111` and line 113.

The actual owner data has a different equality relation. `mkt_campaign` inherits `utf8mb4_uca1400_ai_ci` in `backend/shop-app/src/main/resources/db/migration/V1__baseline.sql:204`; its `entity_no` and `tenant_no` have no binary override. `pmt_activity` likewise uses that case-insensitive table collation in V242. Existing owner selection, for example `backend/shop-core/src/main/java/ai/neargo/shop/marketing/port/CampaignPortImpl.java:277`, uses ordinary equality on entity number. The reviewed §4.2/§4.3 contract requires complete owner reads after acquiring the fences; it does not establish a different byte-exact ownership model for the future consumer.

Concrete interleaving: a reader locks `(MAIN, ENTITY, QE-X)`. An old/raw writer inserts a valid campaign with a new campaign number and `entity_no = 'qe-x'`. The INSERT trigger takes X on `(MAIN, ENTITY, qe-x)`, which is a different binary fence row, so it need not wait for the reader. Yet `WHERE entity_no = 'QE-X'` includes that campaign under the actual owner-table collation. Updates or deletes of an already stored case-variant head have the same mismatch. Case-variant tenant values create the analogous separation. Immutable UPDATE checks do not protect INSERTs or pre-existing aliases. The current tests use identical byte spellings on both sides and do not exercise this case.

This violates §4.1's mixed-version/raw-writer coordination and §4.3's empty-result/late-insert ordering guarantee. The absence of the Task68B consumer is expected, but the prerequisite must establish a sound scope-identity contract before that consumer can use it.

**Bounded correction:** define one owner equality contract for both writers and readers, then enforce it in the trigger renderer, packaged descriptor and `lock` boundary. Either resolve/validate a canonical authoritative tenant/entity identity for all trigger paths and reader inputs, with an explicit policy for already stored aliases, or make the fence comparison semantics cover the actual owner-query equivalence classes and review that change against the binary-key design. An alternative byte-exact counter-read contract requires explicit design agreement and handling of existing aliases; it must not silently omit otherwise applicable owner rows. Merely uppercasing values or assuming old/raw writers always use canonical spelling is insufficient. Do not edit applied migrations to fix this.

Add unexecuted native source cases for a canonical reader versus case-variant head insertion and an existing variant head update/delete, plus tenant/enrollment variants as applicable to the chosen contract. The writer must block on the same scope or be rejected before changing a predicate input. Execute these only in the authorized unified test phase.

## Other inspected behavior

- The released schema, generated SQL and packaged descriptor contain all seven required groups and all 21 BEFORE INSERT/UPDATE/DELETE triggers. Parent resolution is indexed by actual business number, constrained to the same tenant, binary-checked, and uses current shared parent reads. Missing parents, cross-tenant parents and changed parent identity/linkage are rejected. PLATFORM heads and enrolled ENTITY scopes are distinguished. The identity-equivalence gap above remains independent of those checks.
- The Java fence uses the named primary datasource, factory and transaction manager. Existing wiring supplies a matching `DataSourceTransactionManager`; the exact mapper scan does not broaden the BaseMapper scan. `lock` requires an existing active writable READ_COMMITTED transaction, checks its bound connection, locks platform before sorted/distinct entity rows, inserts and rereads missing entity rows, and retains locks in the caller's transaction. There is no REQUIRES_NEW, selected owner-row lock, fence deletion API, or new pricing consumer.
- Failure handling marks the bound connection holder rollback-only before returning dependency-unavailable. Native timeout/deadlock source cases assert rollback of actual SQL effects. Their runtime behavior remains unverified.
- Readiness begins false; startup inspection catches capability validation failure, while strict validation/lock paths fail closed. Runtime validation reads a uniquely packaged resource and compares the fence columns/defaults/collations/engine/ordered PK/CHECKs and trigger definitions. The tokenizer preserves quoted literal spelling; the altered-trigger source case changes literal case. Actual server metadata normalization and ordinary/M1 startup remain runtime checks.
- The renderer's `--check` branch reads and compares files without writing or executing SQL. The NO_ENTITY_YET addition is exact. The scope_no lineage discriminator and exact collision membership retain unknown-source rejection and avoid an unconditional FK; no parser/prefix/global-key bypass was introduced.
- The twelve finite native tests directly extend a fixture with no inherited test methods. Their SQL operations, revision assertions, rollback assertions and child-first owned-row cleanup are meaningful source coverage of the intended boundary. The four Node cases and two lineage cases are authored checks, not execution evidence.

## Cannot verify in this phase / limits

No tests, build, renderer, serializers, runtime probes, SQL, migrations, Docker, app startup, network, staging/index mutation, commits or product edits were performed by this reviewer. Only this review report was written.

All native/JUnit, Node/Vitest, architecture, concurrency, timeout/deadlock, capability drift, startup and ablation results remain **UNEXECUTED / pending unified phase B**. Compilation cannot prove trigger installation, information_schema representation, lock retention, ordinary/M1 startup, or the actual failure translation behavior.

The implementer's frozen-migration aggregate, pre-creation maximum-version check, renderer execution history and compiled-artifact identity were not independently re-executed or exhaustively reconstructed. The reviewed package contains only the new V543 migration, and the current source/report pins match; that is the scope of this review's direct evidence. Task68C still owns the native runner/setup, and Task68B still owns pricing consumption and final owner-read semantics. Those expected boundaries are not additional findings.
