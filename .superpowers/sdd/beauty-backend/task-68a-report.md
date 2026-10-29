# Task68A self-report

Status: **DONE_PENDING_ROOT_REVIEW**. Coding base `791d5f6e086cd371a5821ae684894b41c7b42ca9`, branch `codex/beauty-foundation`, sole worktree `/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex`.

Shared marketing fence prerequisite only. Pricing activation, the whole Task68/C02 discount feature and the cash chain are **not complete**. No runtime acceptance is claimed. Sources are frozen pending root review.

## Exact authored scope and source pins

Three modified files and eight created files, exactly the released eleven product/test/artifact paths, plus this report. Root's dirty `progress.md` was not edited. No staging, commits, resets, checkout, subagents or unrelated edits.

| Path | SHA256 |
|---|---|
| `packages/shared/tests/entity-alignment.test.ts` | `51309bb26f30398667775bf7c725a5a8d3cf2f52d3b3d4be6c7568ff3e0a9120` |
| `scripts/lib/schema-lineage.mjs` | `3520fce93c23528278630929e9843fb60f34b5afbf3ceb47cbd1642f057980e1` |
| `packages/shared/tests/schema-lineage.test.ts` | `cf9dbe849632b1ae53c7b8d19d21eed612588c5d6ae386584605110c62955b4f` |
| `backend/shop-core/src/main/java/ai/neargo/shop/marketing/campaign/CounterCampaignFence.java` | `e583a900b743b5f51a0edbb896853349fbba06b5abc2e01c52c19ed5d85c30d2` |
| `backend/shop-core/src/main/java/ai/neargo/shop/marketing/campaign/mapper/CounterCampaignFenceMapper.java` | `b4f86ad6acf9cbef0f3f190428e7c2e60df58959021c4d5dae88accf417a2bbe` |
| `backend/shop-app/src/main/resources/db/migration/V543__counter_campaign_owner_fence.sql` | `b315ab57d6a63efb2b540c72d6bf28e865e9b3c265033615e17c89ce1ad600a2` |
| `docs/technical/design/schema/counter-campaign-owner-fence-schema.json` | `849a0628cae8b076ac9171f9b73ea4ece3a7cfa61647921230ea4a0d340de7bf` |
| `scripts/gen-counter-campaign-owner-fence-schema.mjs` | `2de2076e912df50fdd3c1722775522fe66bab6cdab91385bd0e4bd6e2c3f54c0` |
| `backend/shop-core/src/main/resources/schema/counter-campaign-owner-fence-descriptor.json` | `55f1478c840de9b3568ace88a551b676c6901a76532216164b924838eff13b8c` |
| `backend/shop-app/src/test/java/ai/neargo/shop/scenario/CounterCampaignFenceIT.java` | `6aedf37a5895448f55b4b8ebb4a898bbe6599e2dca3b9a56e3cc363382a3f52d` |
| `scripts/tests/counter-campaign-owner-fence-schema.test.mjs` | `2853b8df3910ed27882057d58738aa656cfcbe9e17023431bcaf32a71b212d5e` |

## Implemented table/trigger matrix

Generated shared table `mkt_counter_campaign_fence`: `(tenant_no VARCHAR(32), scope_kind VARCHAR(16), scope_no VARCHAR(64), revision BIGINT NOT NULL DEFAULT 0)`, all identity columns `utf8mb4_bin`, InnoDB, exact composite PK `(tenant_no,scope_kind,scope_no)`, CHECKs for `PLATFORM/ENTITY` and nonnegative revision. Seed `(MAIN,PLATFORM,PLATFORM,0)`; entity rows are durable/lazy. No fence-row deletion API.

Each row below has exactly BEFORE INSERT, BEFORE UPDATE and BEFORE DELETE triggers. Exact names are `trg_ccf_<table>_insert`, `trg_ccf_<table>_update`, `trg_ccf_<table>_delete` (21 total). Every successful mutation takes/increments its X fence via `INSERT ... ON DUPLICATE KEY UPDATE revision = revision + 1` within the writer transaction.

| Input table | Fence | Immutable UPDATE identity/linkage |
|---|---|---|
| `mkt_campaign` | head ENTITY | tenant_no, entity_no, campaign_no |
| `pmt_activity` | MERCHANT→ENTITY; PLATFORM→PLATFORM | tenant_no, entity_no, activity_no, owner |
| `pmt_activity_version` | actual parent head ENTITY/PLATFORM | tenant_no, activity_no, version_no |
| `pmt_activity_goods` | actual parent head ENTITY/PLATFORM; child entity must match | tenant_no, activity_no, entity_no |
| `pmt_activity_rule` | actual parent head ENTITY/PLATFORM | tenant_no, activity_no |
| `pmt_enrollment` | enrolled ENTITY; actual same-tenant PLATFORM parent required | tenant_no, entity_no, enrollment_no, activity_no |
| `pmt_enrollment_goods` | actual parent enrollment ENTITY | tenant_no, enrollment_no |

Parent resolution uses indexed business-number equality plus byte-exact tenant/link checks and a current `LOCK IN SHARE MODE` parent read. Missing/cross-tenant parents and invalid owners fail with SIGNAL; no foreign owner is guessed. Immutable comparisons are binary, including case-only changes. Child-first physical cleanup is required. Soft delete and all other UPDATE fields participate. No audience/scope/budget/apply tables or old owner Java writers were edited.

## Readiness, transaction and wiring binding

`CounterCampaignFence` is a cohesive Spring configuration/helper with a narrowly scoped annotated mapper scan (`annotationClass=Mapper`, exact primary `sqlSessionFactory`), avoiding duplication of existing BaseMapper scanning. Constructor names `dataSource`, `sqlSessionFactory`, `transactionManager` explicitly. Binding requires the factory's actual DataSource identity, SpringManagedTransactionFactory, and the primary DataSourceTransactionManager's identical DataSource.

Readiness starts false. `@DependsOnDatabaseInitialization` plus `SmartInitializingSingleton.afterSingletonsInstantiated()` inspects after database initialization. Startup catches capability failure and logs it without failing unrelated ordinary/M1 startup. Strict public `validateSchema()` first closes readiness, reads exactly one classpath `schema/counter-campaign-owner-fence-descriptor.json`, and validates actual columns/types/defaults/binary collation/engine/ordered PK/CHECK names+expressions, CHECK enforcement when exposed by MySQL, and exact trigger table/event/timing/body/count. Application runtime never reads repository docs. SQL normalization preserves quoted literal bytes/case; identifier quoting/case and whitespace outside literals are normalized.

`lock(tenant,entityNos)` requires an active, writable platform READ_COMMITTED transaction, matching actual bound ConnectionHolder/physical connection isolation, non-autocommit and no rollback-only state. It strictly revalidates capability before locks, locks exact-PK platform S first, then sorted/distinct ENTITY S rows. A missing entity uses INSERT IGNORE and a mandatory reread/lock in the same transaction. Missing platform seed fails. Mapped selects disable cache and flush the local session cache; no selected marketing-row lock, REQUIRES_NEW or early release is used. Any lock/schema/binding error maps to existing CHECKOUT_DEPENDENCY_UNAVAILABLE; the bound ConnectionHolder is marked rollback-only so a caught timeout/deadlock cannot commit the outer operation. There is no retry inside retained roots.

No pricing consumer is wired yet; Task68B must call this boundary through the complete owner evaluation and retain its transaction through the final write. It must keep final owner reads explicit/current and uncached, and integrate the selected-rule seal/compatibility/admission work from the reviewed addendum.

## Actual allowed commands and results

The only build command, run three times offline with no test lifecycle execution:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -o -f /private/tmp/task66-complete-reactor/pom.xml -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 -Pbeauty-embedded -pl :shop-app -am test-compile -DskipTests
```

| Run | Result | Coverage/evidence |
|---|---|---|
| 1 | exit 0; BUILD SUCCESS; 24 modules; 37.140s | javac shop-core 1067 main + 86 test sources, shop-app 413 main + 750 test sources |
| 2, after final Java source review | exit 0; BUILD SUCCESS; 24 modules; 37.500s | same actual counts; includes the current fence/mapper and CounterCampaignFenceIT |
| 3, after indexed trigger predicate generation | exit 0; BUILD SUCCESS; 24 modules; 2.787s | unchanged current Java up to date; current descriptor/V543 resources recopied |

`node scripts/gen-counter-campaign-owner-fence-schema.mjs` exited 0 (deterministic text generation only). Repeated `node scripts/gen-counter-campaign-owner-fence-schema.mjs --check` and `git diff --check` exited 0. `--check` only reads/compares files. Static file reads confirmed current descriptor and V543 bytes equal their target/classes resources. New Java maximum width is 120 columns, four-space indentation and explicit imports; public members have Javadoc.

Bounded logs and static source pin evidence: `/private/tmp/task-68a-evidence/compile-{1,2,3}-bounded.log`, `sourcepins.json`, `static-evidence.json`; full original logs `/private/tmp/task68a-compile-{1,2,3}.log`. No binaries were placed in the report/evidence. No Surefire/tests, serializers, probes, native database, Docker, app startup, migrations, network, deployment, hooks or ablations were executed. Default retail and final affected beauty/food compilations remain root's final68B/C pass as released.

## Authored tests — all UNEXECUTED

Native test class directly extends CheckoutFlowFixture, which has no inherited @Test methods; it does not inherit historical purchase tests. Twelve finite @Test methods compile but **are UNEXECUTED**:

- `rawInsertUpdateSoftAndPhysicalDeleteCoverEveryPredicateTable`
- `immutableIdentityAndParentLinkageRejectEvenCaseOnlyChanges`
- `missingAndCrossTenantParentsNeverGuessAnUnprotectedOwner`
- `platformHeadPublishedVersionAndEnrollmentUseTheirActualScopes`
- `lazyMissingEntityInsertionContendsUntilTheFirstTransactionEnds`
- `sharedReadersProceedAndRawWriterWaitsUntilBothTransactionsEnd`
- `emptyReaderFenceBlocksRawInsertIntoEveryPredicateTable`
- `committedWriterIsVisibleToStatementsAfterFenceAcquisition`
- `lockTimeoutMarksWholeTransactionRollbackOnlyEvenIfCallerCatchesIt`
- `rawOppositeOrderDeadlockAbortsOneWholeOwnerTransaction`
- `absentOrLiteralAlteredTriggerClosesCapabilityAndRestorationReopensIt`
- `missingPlatformSeedAndWrongTransactionCannotSucceed`

Four Node schema cases are **UNEXECUTED**:

- `render is deterministic and both committed artifacts are exact`
- `every predicate table has exactly the three before mutation triggers`
- `identity checks are binary, parents are tenant-bound current reads, and platform is seeded`
- `descriptor rejects duplicate predicate groups and unknown ownership resolution`

Two new schema-lineage Vitest cases (actual parsed table has no false scope_no FK; unknown scope_no member is rejected) and existing entity-alignment guards are also **UNEXECUTED**. The exact NO_ENTITY_YET entry documents explicit SQL/Long projection and shared ownership. `scope_no` retains conditional tenant+scope_kind semantics; exact audited collision memberships replace any new-source exemption. The ER framework/parser and generator remain unchanged.

Phase-B runner/setup is deliberately not edited here. It must create actual `mkt_campaign`, `pmt_activity` including V337 owner fields and V484 published-head fields, `pmt_activity_version`, `pmt_activity_goods`, `pmt_activity_rule`, `pmt_enrollment`, `pmt_enrollment_goods` with actual columns/indexes/engine, then V543 table/seed/triggers, alongside the existing native CheckoutFlowFixture member/catalog/checkout tables. Use separate actual connections for contention, SQL DDL restoration in finally for trigger drift tests, and owned business-row child-first cleanup; durable fence rows survive. Runtime/native authorization and m2c runner/gate/pins remain Task68C/root responsibilities. Tests assert real SQL effects/rollback rather than successful stock/quote/order/Pay effects.

## Frozen migrations and self-review

Before creation, platform max was V542 and V543 did not exist; no collision was found. Actual V543 is the only new migration. All 485 tracked migration/resource files in the existing platform, beauty and inventory migration trees were byte-compared with the released base and matched. Aggregate SHA256 of sorted `path SHA256` lines: `bd9d520472316f11698c78120a1f759bea410babd9515566dbe0c2b64dd92a45`. No applied migration or known-* ratchet changed. All three released modify-file baseline SHA256 pins matched the coding base.

Static self-review checked exact11 scope, seven-table/21-trigger coverage, immutable identity/linkage, parent failure paths, binary literal preservation, indexed parent resolution, platform-before-entity reader order, primary resource binding, rollback-only failure behavior, lazy insertion reread and no ordinary/M1 startup exception. This is source/compile evidence only. Independent root review and all runtime acceptance, deadlock/timeout/concurrency proof, trigger installation, ablation and final reactor matrix remain outstanding. No concrete missing context or known implementation concern is being deferred by this author.


## Fix1/5 — I1 owner/fence identity equivalence

Status: **DONE_PENDING_ROOT_REVIEW**. This appended section supersedes R0's binary fence tenant/scope assumption while preserving that report as history. Binding ruling: `task-68a-fix1-brief.md`; issue read verbatim: `review-task-68a-r0.md` I1. Root approved the bounded comparison-policy correction. No new runtime acceptance or pricing activation is claimed.

**Implemented correction.** V543 now omits both table DEFAULT CHARSET and COLLATE; tenant_no/scope_no inherit the actual database charset/collation. scope_kind alone explicitly declares CHARACTER SET utf8mb4 COLLATE utf8mb4_bin. Lengths, InnoDB, exact PK, CHECKs, revision, seed and all 21 trigger definitions are unchanged. Trigger extraction/caller strings stay byte-preserving; immutable UPDATE, parent and linkage checks remain binary. No upper/lower/accent canonicalization, existing-alias omission, broad global serialization, old migration or owner-writer rewrite was introduced.

The truth and packaged descriptor explicitly carry DATABASE_DEFAULT/OWNER_IDENTITY policies and the exact ten required bindings: tenant_no on mkt_campaign, pmt_activity, pmt_activity_version, pmt_activity_goods, pmt_activity_rule, pmt_enrollment, pmt_enrollment_goods; entity_no on mkt_campaign, pmt_activity, pmt_enrollment. New noncached mapper `ownerIdentityBindings()` inspects those exact effective COLUMN rows. Strict validation requires exactly ten unique expected rows, all actual charset utf8mb4 and the same nonnull effective collation C. It checks actual fence tenant_no/scope_no charset+collation C, table default C and database default C; scope_kind remains utf8mb4_bin. Database/table defaults alone cannot pass an overridden/missing owner column. Existing type/default/engine/PK/CHECK/trigger checks, primary transaction binding, local readiness initialization and ordinary/M1 startup independence remain. Incompatibility closes this capability and a caught lock failure marks the transaction rollback-only; no binary/no-fence fallback exists.

**Bounded delta.** Eight released files changed from frozen R0: fence, mapper, V543, schema truth, renderer, packaged descriptor, CounterCampaignFenceIT, Node schema test. The other three released lineage/entity files are identical to R0. Root-owned paired metadata/TDD/ER/ledger were untouched. Static comparison proves all 21 trigger definitions exactly match R0. No extra product path, stage/index, commit, reset, subagent, existing migration or known-* ratchet edit.

**Actual covering commands.** Deterministic `node scripts/gen-counter-campaign-owner-fence-schema.mjs` exited 0; `node scripts/gen-counter-campaign-owner-fence-schema.mjs --check` and `git diff --check` exited 0. --check only compares files. The one covering build after final source changes was:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -o -f /private/tmp/task66-complete-reactor/pom.xml -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 -Pbeauty-embedded -pl :shop-app -am test-compile -DskipTests
```

Exit 0, BUILD SUCCESS, 24 modules, 40.347s. Actual javac counts: shop-core 1067 main / 86 test; shop-app 413 main / 750 test. Current descriptor and V543 bytes match copied target/classes resources. Java maximum width remains 120. Full log `/private/tmp/task68a-fix1-compile.log`; bounded log/current pins/static evidence `/private/tmp/task-68a-evidence/fix1/{compile-bounded.log,sourcepins.json,static-evidence.json}`.

**UNEXECUTED source cases.** Existing twelve native, four Node and lineage/guard cases remain UNEXECUTED. Six new finite native @Test methods compile (18 native total):

- `canonicalReaderBlocksCaseVariantHeadInsertsAndExistingUpdatesAndDeletes`
- `accentAndTrailingSpaceAliasesUseActualFixtureCollationEquivalence`
- `tenantAndEnrollmentAliasesShareFenceWithoutRelaxingParentIdentity`
- `everyEffectiveOwnerColumnOverrideClosesCapabilityDespiteUnchangedTableDefault`
- `missingRequiredOwnerBindingClosesCapabilityUntilTheActualTableReturns`
- `fenceIdentityColumnsAndDatabaseDefaultsMustMatchEffectiveOwnerCollation`

Positive alias source cases use actual raw campaign/activity/enrollment/goods rows, canonical ordinary owner queries and separate real reader/writer transactions. They derive/check the native fixture's actual COLUMN C before case/accent/PAD SPACE alias assertions; no guessed product normalization is used. They cover INSERT and pre-existing variant UPDATE/DELETE, tenant aliases, real enrollment-parent spellings and retained rejection of byte-mismatched parent linkage. Negatives individually override each of the ten effective owner-column collations while table defaults remain unchanged, temporarily remove an actual required binding, and drift fence columns/table/database defaults. Each negative asserts capability failure and rollback of actual outer-transaction SQL even when lock failure is caught, with DDL restoration in finally. All such SQL/DDL exists only in authored test source and was NOT executed. Existing four Node cases now assert inherited DDL, exact10 bindings, unchanged binary linkage, no normalization and rejected binding/policy omissions. Strict lineage guards are unchanged.

**Current exact11 SHA256 pins (fix1 source freeze):**

| Path | SHA256 |
|---|---|
| `packages/shared/tests/entity-alignment.test.ts` | `51309bb26f30398667775bf7c725a5a8d3cf2f52d3b3d4be6c7568ff3e0a9120` |
| `scripts/lib/schema-lineage.mjs` | `3520fce93c23528278630929e9843fb60f34b5afbf3ceb47cbd1642f057980e1` |
| `packages/shared/tests/schema-lineage.test.ts` | `cf9dbe849632b1ae53c7b8d19d21eed612588c5d6ae386584605110c62955b4f` |
| `backend/shop-core/src/main/java/ai/neargo/shop/marketing/campaign/CounterCampaignFence.java` | `387c97d77fc705a29326529e300306d36b1929d19301ea24b4c5e911af54232a` |
| `backend/shop-core/src/main/java/ai/neargo/shop/marketing/campaign/mapper/CounterCampaignFenceMapper.java` | `70ba17bf45f83aaee890954a090cdcc45ffcb5c55e2bfd25b66708adf8c974c4` |
| `backend/shop-app/src/main/resources/db/migration/V543__counter_campaign_owner_fence.sql` | `7151507414413188096b2bed9cebbea21bfb46e5ab5eab9636b5458b432f39bf` |
| `docs/technical/design/schema/counter-campaign-owner-fence-schema.json` | `0041175c3e0d17b9c40eed67724178f34e3bc59218e7ea0fcc74cc5f95625eb8` |
| `scripts/gen-counter-campaign-owner-fence-schema.mjs` | `4eecbe54725b48cafb5b0180baff9c343df65119d6a3105b951f953acc0519c2` |
| `backend/shop-core/src/main/resources/schema/counter-campaign-owner-fence-descriptor.json` | `194d705c2b44d3ff0dde762df970d0a1216a8a9b00de263091fd07490901228d` |
| `backend/shop-app/src/test/java/ai/neargo/shop/scenario/CounterCampaignFenceIT.java` | `28878c2110da3dc25c3167e6b4e1ac100935a42cac9248f4ffec9935b1eb6732` |
| `scripts/tests/counter-campaign-owner-fence-schema.test.mjs` | `7d85a09ae0ee6039ce740d951bd1b01cc747de82d80ce8782384c2a5a74a4fab` |

All tests, native/SQL/DDL/migration execution, serializers/probes, app startup, HTTP/network, ablations and deployment remain **UNEXECUTED**. Effective source-column incompatibility may legitimately keep the new capability unavailable; unified phase B/C must inspect the actual environment and resolve any deployment/data decision separately. Root review, Task68B pricing consumption and Task68C runner/runtime evidence remain pending. No concrete missing context is deferred by this author. Sources frozen after the successful covering compile.
