# Task69A1 — implementation freeze

Status **DONE_PENDING_ROOT_REVIEW**. Coding BASE `35784cde22785c9e3b6fb7dd23f6fc0bf6c03d82`, branch `codex/beauty-foundation`, sole worktree `/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex`. Approved design SHA256 `2e33ad2ff0072f322064062e1592b8cbf2e7e11623c9d21fd636c6704fe387e5`; R2/design approval is the incoming design gate, not implementation or runtime acceptance. This report freezes exactly the inventory's 18 creates + 5 modifies, and is the separately authorized append-only report.

A1 is unconsumed prerequisite source: no business provider/service/controller/job/config/POM, manual writer, beauty V10, role/i18n/Pay integration or canStart activation was added. M4 resource/start/release and M6 completion/fulfillment types and writers remain staged comments, without unused success methods. No subagents, staging/index writes, commits, reset, installation, application start, network, SQL or migration execution occurred. Root's concurrent global documentation changes are not A1-authored paths.

## Implemented shape and hash contract

`ExecutionScope` requires MAIN and canonical entity/store references <=32. Binding retains BEAUTY, operation/order/suborder <=64, sourceLine <=40, all beauty/new execution references <=32, ordinal1..999 and positive attempt; original scope/binding/fence/source revisions are positive and initial task revision is nonnegative. Freeze request keys use the current shared128 bound. Hashes are lower-case SHA256 hex. Existing personnel facts enforce UTC millisecond instants and owner revision/interval shape. Shape never grants permissions or substitutes for actual locked owner facts.

Quantity is the fixed1 COUNT in the canonical payload and permanent-unit/attempt rows. QuantityFacts validates ordered1..999, nonnegative disjoint buckets and a long-arithmetic bucket sum<=ordered. Decimal1.000000 is accepted exactly; other quantities/uom are rejected without rounding. Immutable binding.sourceEpoch/revision/hash is separate from current SourceContinuity. READY_UNCHANGED requires complete current observed revision/hash; PENDING is not definitive withdrawal. SourceSuspension observed revision/hash are independently nullable, each present value is checked, and the core cause tuple is required. SOURCE_UNKNOWN, RESOURCE_EXECUTION_UNAVAILABLE and EXECUTION_SCHEMA_UNAVAILABLE cannot become a definitive SUSPENDED continuity or SourceSuspension proof.

Root explicitly approved these finite helper/domain choices during implementation. Every SHA256 input is UTF-8 `domain + "\n" + canonical(payload)`. Canonical JSON sorts object keys, includes nulls, uses exact integers, preserves list order and old source/plan hash values, and rejects fractional numbers/unsupported objects. Hash bodies do not include mutable grant state/observation epochs or their own hash value.

| Helper | Domain and exact coverage |
|---|---|
| bindingHash(binding) | MANUAL_EXECUTION_V1; complete binding with payloadHash omitted |
| payloadHash(binding, personnel, operatorNamespaceHash, resourceCommandNo, resourceCommandHash) | MANUAL_EXECUTION_V1; a distinct wrapper of binding-without-payloadHash, original personnel, operator namespace, resource coordinates, schema, quantity1 and COUNT |
| intentHash(intent) | BEAUTY_EXECUTION_INTENT_V1; complete immutable StartIntent fields including full binding/admission/party/person/namespace/resource/frozen plan; excludes state, intentRevision, continuity and suspension |
| actionHash(binding, kind, actionNo, expectedGrantRevision, actorNamespaceHash, proof, suspension) | MANUAL_EXECUTION_ACTION_V1; action kind/no, expected revision, bindingHash, actor namespace (null for OBSERVE), complete original StartIntentLookup and explicit nullable suspension; only M3 kinds |
| suspensionHash(binding, suspension) | BEAUTY_EXECUTION_SUSPEND_V1; complete original binding and all suspension fields except proofHash |
| bindingJson(binding) | Canonical object with schema=MANUAL_EXECUTION_V1 and complete binding |
| suspensionJson(binding, suspension) | Canonical schema=BEAUTY_EXECUTION_SUSPEND_V1/binding/suspension object; verifies supplied proofHash against the self-excluded digest |

The constructor frozenPlanHash must equal binding.planHash. Existing source/booking hash bytes are embedded and not recalculated in another domain. Caller services must still prove actual source/person/stock/plan facts and authorization; these pure functions have no IO, clock, session or private owner imports.

## ORIGINAL and HUMAN wire

ExecutionOriginalProtocol.Binding fixes protocol1 and exactly SERVICE_EXECUTION/QUERY_ORIGINAL or SERVICE_EXECUTION_SOURCE/INSPECT_ORIGINAL. Call accepts only the route's complete typed original lookup and checks scope, execution target and complete bindingHash. Neither record includes a human identity or a self-referential request digest. Reply requires a typed non-null record and a valid digest. Only these new Call/Reply records receive new codec annotations.

InternalBindings ORIGINAL overloads use `SHOP_EXECUTION_ORIGINAL_V1\n` HmacSHA256 with the existing key/tree/canonical primitives and the complete binding+request. require/reply/route/coordinate overloads bind exact originals. Existing HUMAN digest method, canonical/tree implementation and InternalCall/Reply serializers are unchanged.

**Root hardening ruling, explicit exception to the initial visibility-only decoder change:** source review found that both baseline HUMAN readTree sites could silently discard duplicate properties. Root required one shared package-private recursive strict-tree reader in InternalReplyDeserializer for HUMAN and ORIGINAL, with generic errors and no dependency from that generic decoder to the execution codec. Both HUMAN call/reply sites now reject top-level/nested duplicates before shape checks; ORIGINAL reuses that reader. HUMAN retains its existing parser-configured nesting constraints (maxDepth=-1); ORIGINAL retains its64 bound. requireShape's algorithm is unchanged and its visibility is package-private. No global mapper flags or valid HUMAN schema/HMAC/serialization/nullability changes were introduced. Authored valid-legacy HUMAN, exact-domain and duplicate cases are listed below and remain UNEXECUTED.

Consumed API interfaces contain only Core prepare/query/queryOriginal and reverse source inspect/inspectOriginal (3+2 private methods). No unused public HTTP shell or implementation was introduced. Token/status/digest-bound errors are covered as pure existing helper cases; actual unknown-owner handling and transport routes remain A3/A4 obligations.

## Persistence and lazy installed-schema verification

The schema truth is `docs/technical/design/schema/manual-service-execution-schema.json`. The pure renderer uses the existing frozen-migration helper, emitting only V544 and `backend/shop-core/src/main/resources/META-INF/shop/manual-service-execution-schema.json`. V543 was the actual platform maximum before creation. V544 is empty authority DDL with three InnoDB tables, no backfill, IF NOT EXISTS, old migration edits or source writer changes.

| Table | Columns (business/BaseEntity) | Immutable fields | Unique keys / secondary indexes / checks |
|---|---:|---:|---:|
| ord_service_execution_unit | 29 (21/8) | 22 | 3 / 1 / 8 |
| ord_service_execution | 39 (31/8) | 29 | 4 / 2 / 11 |
| ord_service_execution_action | 22 (14/8) | 16 | 2 / 1 / 8 |

All three entities extend actual BaseEntity and declare all business columns. Identity fields use FieldStrategy.NEVER and explicit SQL, while generated BEFORE UPDATE guards enforce immutable identities even for raw writers. BEFORE DELETE guards reject history deletion, deleted=0 is checked, and the action result tuple can only be finalized once. Source-line+ordinal and tenant/entity/caller/task unique keys omit deleted, request keys, binding revision and attempt generation, permanently preventing recycling. Attempts have execution/grant/unit+attempt/key uniqueness; actions have action and execution+kind+key uniqueness. Unit quantity aggregation counts only unit states once, not attempts.

Owner identity inspection uses actual primary ord_checkout_line/ord_checkout_operation/ord_manual_service_admission tenant columns with inherited utf8mb4 owner equivalence. New tenant/audit VARCHARs inherit that same table collation; no guessed binary tenant namespace or lowercasing was introduced. The four actual operation/admission entity/store columns are VARCHAR32 utf8mb4_bin and sourceLine is actual VARCHAR40 utf8mb4_bin. New business references remain binary; hashes are CHAR64 ascii_bin. The verifier checks all eight actual owner identity columns and their types, plus every new physical column/type/default/nullability/collation, exact primary/unique/secondary keys, named semantic CHECK expressions/enforcement when exposed, and exact immutable/delete trigger set/body. Check-expression parsing preserves AND/OR/BETWEEN grouping and literal bytes instead of deleting parentheses. JSON fields are bounded LONGTEXT object payloads with explicit JSON_VALID, discriminator/property-presence/type checks, so absent/null required discriminator/binding properties do not pass via SQL UNKNOWN. Outcome and action-result/proof tuples use explicit all-none/all-present predicates.

ServiceExecutionSchemaVerifier constructor captures the explicitly named primary DataSource and existing Flyway table/default-schema settings only. It performs no DB or resource IO. Each installation() call inspects the current primary schema and configured owner history without searching another DB/history or caching a startup Boolean. ABSENT requires both no V544 marker and no new table; failed/untracked/partial/drifted installations return CORRUPT. INSTALLED requires one exact successful V544 SQL marker, matching Flyway checksum, matching bundled migration SHA and complete physical descriptor. requireAvailable rejects both ABSENT/CORRUPT with the existing dependency error plus EXECUTION_SCHEMA_UNAVAILABLE. Existing reduced fixtures with no marker/all new tables absent preserve legal absence; new tables without a marker are CORRUPT, never a fake availability Boolean. A3 must invoke this verifier on actual affected local writer paths even when a remote provider is off.

The mapper has complete scoped reads, bound/prepared/pending action inserts, expected-one-row reservation/CAS/suspension/claimed-epoch/observation/result-finalization writes and a due-time/id recovery cursor capped by SQL predicate1..100. Parent/child/source roots and actual authorization remain the A3 caller's responsibility. Unit locks and reads deliberately include terminal rows. reserve only accepts generation0->1; insertPrepared requires PREPARED generation1 and an already RESERVED exact unit; no release/fulfillment writer is active. Nullable retry/result fields use explicit SQL rather than updateById null skipping. Unit quantity absence returns no grouped row, not available capacity.

Exact new-table lineage uses current TABLE_KEY_OWNERS patterns and exact collision-member lists for unit/action/source-line/source-operation/declaration/execution/grant roles. Global notify task/retail/resource/entitlement owners and all generic resolver/parser/guard algorithms are unchanged. Root approved a table-qualified unit.task_no self-owner solely as a no-edge override for its permanent local task association: the separate private beauty task owner is not fabricated as a platform FK. No absent bty table is registered as a physical target. General schema-test.sql/ER/registries are root-owned and remain pending root's integration regeneration; the full table-equality guard was not weakened to hide that pending generated fixture.

## Actual methods

- ServiceExecutionPort: prepare(PrepareExecution), query(ExecutionLookup), queryOriginal(ExecutionLookup); compact validated constructors for ExecutionScope, ExecutionBinding, ExecutionLookup, PrepareExecution, QuantityFacts and Grant; redacted ExecutionBinding.toString. GrantState and Reason enums are the exact bounded shared state/cause vocabulary.
- ServiceExecutionSourcePort: freeze(FreezeStart), inspect(StartIntentLookup), inspectOriginal(StartIntentLookup); compact validated constructors for FreezeStart, StartIntentLookup, StartIntent, SourceContinuity and SourceSuspension; redacted FreezeStart/StartIntent.toString; IntentState enum.
- ServiceExecutionContract public static: bindingHash, payloadHash, intentHash, bindingJson, suspensionJson, actionHash, suspensionHash, requireSuspensionBinding, requireTaskQuantity, canonical. Package-private shape helpers: reference, exact, hash, positive, nonnegative, reason, suspensionReason, observed, observedNullable, objectJson. Private helpers: bindingPayload, recordFields, sha256, invalid; private utility constructor.
- ServiceExecutionInternalApi: prepare, query, queryOriginal. ServiceExecutionSourceInternalApi: inspect, inspectOriginal.
- ExecutionOriginalProtocol: private utility constructor; Binding/Call/Reply compact constructors; Call/Reply redacted toString.
- ExecutionOriginalCodec: private utility constructor; nested CallSerializer/ReplySerializer constructors and serialize; CallDeserializer/ReplyDeserializer default/contextual constructors, createContextual, deserialize; private payloadType and requireEnvelope. No separate duplicate-parser algorithm remains in this codec.
- InternalBindings newly overloaded ORIGINAL methods: digest(Call), require(Call,Reply), requireRoute(Call,endpoint,action,executionNo), requireCoordinates(Call,tenant,entity,store), reply(Call,result). Existing HUMAN methods/helpers are unchanged.
- InternalReplyDeserializer: existing constructors/createContextual/deserialize and nested Call counterparts; newly shared package-private readStrictTree(parser,context,depth,maxDepth); existing requireShape algorithm with package-private visibility.
- ServiceExecutionMapper: lockLine, find, lockExecution, lockSourceExecutions, findKey, insertPrepared, requestSuspend, claimObservation, applyObservation, pending. UnitMapper: lockLineUnits, findTask, insertBound, reserve, quantity. ActionMapper: findKey, insertPending, finalizeResult. All18 methods have actual SQL; inherited BaseMapper methods remain subject to immutable/no-delete DDL guards.
- OrdServiceExecutionUnit / OrdServiceExecution / OrdServiceExecutionAction: actual BaseEntity subclasses with Lombok getters/setters; no business success methods.
- ServiceExecutionSchemaVerifier: constructor, installation, requireAvailable; private descriptor, inspect, checkColumn, checkKeys, checkConstraints, columns, tableExists, query, text, nullable, defaultValue, success, strings, identifier, quoted, require, tokens, expression. Nested Expression constructor/or/and/comparison/atom/arguments/take/expect parse finite physical check grammar. Installation enum has only ABSENT/INSTALLED/CORRUPT.
- Static renderer: identifier, same, validate, triggers, flywayChecksum, exported render; CLI accepts only --check and owns the two output paths. Flyway checksum uses CRC32 over UTF-8 logical lines excluding terminators/initial BOM.

## Executed commands and outputs

Only current-source offline JDK21 test-compile, own pure renderer rebuild/check, exact two new Node file --check parses and read-only source/diff/hash inspection were used. **All JUnit, Node tests, Vitest, architecture/serializer/golden/probe/ablation/native/Docker/SQL/app/network/install/runtime/deploy acceptance remains UNEXECUTED.** No surefire test goal ran.

Exact compile command on every attempt:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 -f /private/tmp/task69-m3-current-source-reactor/pom.xml -Pbeauty-embedded -pl :shop-app -am test-compile -DskipTests
```

Attempt1 failed before reactor construction with zero sources compiled: Maven prefixed the supplied absolute module strings with the temporary aggregate directory. Root corrected only the three temporary aggregate module paths, verified their canonical current source roots and issued task-69-compile-reactor-ruling.md. Original aggregate SHA7e446f60f990de89ed2f135d8d9d9c6d1dbf003e756d85abf71acdba80b54911 is preserved as pom-r0-absolute-modules.xml; current temporary aggregate SHA256 is a7a6c19e312a7d817cf7a26f1ddc98f3019fce44a27f5a23f12f76f881f2531f. No product POM, cache, dependency or old-source build substitution was used.

| Attempt | Actual result | Why a subsequent compile was needed | Log SHA256 |
|---|---|---|---|
| 1 | Exit1, zero sources | Infrastructure failed before source compilation | a3dfcac5a22d3585e097f8e990e0dbf3251470526921cc1034127ed1a88ce485 |
| 2 | Exit0, BUILD SUCCESS, 39.891 s | New immutable-intent/envelope helpers and exact verifier metadata refinements | 898e8c29608d2292b7bd2308e0e67a85fe10e6a9968cb523fc35e4d2b59fac78 |
| 3 | Exit0, BUILD SUCCESS, 37.877 s | New intent-hash case and lazy configured-history shape validation | 3ab5317bf746a309a8a211f0ebdac554979dbc3042e17619f0c3ad831b496911 |
| 4 | Exit0, BUILD SUCCESS, 24.283 s | Source suspension uncertainty reason correction | 5daaf48810ec93ee44d4cc4cd8054397268665d706f9c1af92397abeba50e4b8 |
| 5 | Exit0, BUILD SUCCESS, 39.694 s | Root-required shared HUMAN/ORIGINAL duplicate hardening | 6022e8ae174d42a6e4ef36279e9b3c493ae9eb9706f4cdef81be174840f0e719 |
| 6 | Exit0, BUILD SUCCESS, 38.246 s | Final source; no subsequent compile required | a54e081dbc663558f827fe4bdf4499909e783cc34c4a642ae64bf608c537afd0 |

Logs are `/tmp/task69a1-test-compile-1.log` through `...-6.log`. Attempt6 exit0 was observed directly; the24-module reactor was source-selected with beauty-embedded. Final actual owner compilation: shop-base320 main/22 test source files, shop-core1075 main/88 test, beauty-core87 main/36 test, shop-app413 main/752 test. Other rebuilt current-source modules and counts are in the raw log (not standalone/food/runtime acceptance). Existing javac annotation-processing/deprecation/unchecked notices were reported; there were no compiler errors. Attempt4 was correctly incremental (base test/core/app recompiled; unchanged others up to date), not falsely reported as a complete recompile.

Static output commands, final exit0 with no stdout:

```sh
node scripts/gen-manual-service-execution-schema.mjs
node --check scripts/gen-manual-service-execution-schema.mjs
node --check scripts/tests/manual-service-execution-schema.test.mjs
node scripts/gen-manual-service-execution-schema.mjs --check
```

The renderer was rebuilt while authoring only its new unapplied V544; no historical file was regenerated. Final --check is byte equality on SQL+descriptor, not SQL execution or runtime schema acceptance. `git diff --check` and a <=120 line check on new Java/test source produced no findings.

Generated V544 SHA256 `daac4b0956de310676f8795d41ba34e412c66179549445dd76f1cdf740643fc1`, Flyway CRC32 `-1519445049`. Bundled descriptor SHA256 `eeb176b6a742553f9b0a7671864aa4604a4ac82cbae6579238fe0a81543ff850`.

All486 historical migration SQL files (platform<=543, inventory<=14, beauty<=9) were matched byte-for-byte to codingBASE git blob IDs. No new beauty table/legacy private count changed: actual beauty remains28 until A2. Historical pin manifest is `/tmp/task69a1-historical-sql-pins.json`, SHA256955f3f257108df335c220c8347d18506502ab2058db30b831691c5a76a26c90e. All five modified-file codingBASE SHA256 values were independently rechecked against the release inventory; all18 create paths were absent at codingBASE.

## Authored cases — all UNEXECUTED

The following19 JUnit methods exercise actual pure contracts/codecs/HMAC/status helpers, not fake business-owner success:

- `rejectsNoncanonicalCoordinatesAndOwnerBounds` — UNEXECUTED.
- `decimalOneIsExactWithoutRoundingOrOrdinalAllocation` — UNEXECUTED.
- `quantityBucketsAreDisjointAndCannotOverflowOrFabricateCapacity` — UNEXECUTED.
- `completeBindingHashChangesOnTargetWithoutSelfHashCycle` — UNEXECUTED.
- `canonicalHashSortsKeysPreservesNullAndRejectsFractionalNumbers` — UNEXECUTED.
- `payloadRetainsPersonnelAndResourceCoordinates` — UNEXECUTED.
- `pendingContinuityIsSeparateFromDurableSuspensionAndNullableObservedProof` — UNEXECUTED.
- `actionHashBindsRevisionNamespaceAndCompleteOriginalLookup` — UNEXECUTED.
- `originalEnvelopeRoundTripsOnlyWithExactTypedExplicitSchema` — UNEXECUTED.
- `originalWireRejectsFractionalIntegralFieldsAndDuplicateKeys` — UNEXECUTED.
- `originalHmacRejectsTokenTargetReplayAndCopiedHumanDigest` — UNEXECUTED.
- `nullableReplyPropertiesStayExplicitAndHumanOriginalWireSubstitutionFails` — UNEXECUTED.
- `independentlyNullableObservedSuspensionFactsCannotTurnUnknownIntoWithdrawal` — UNEXECUTED.
- `tokenCheckPrecedesDigestAndUnknownOriginalErrorsRemainDigestBound` — UNEXECUTED.
- `originalDigestUsesExactPurposeDomainAndCompleteCanonicalWire` — UNEXECUTED.
- `persistedBindingAndSuspensionEnvelopesContainExactDiscriminatorsAndOriginals` — UNEXECUTED.
- `frozenIntentHashChangesWithOriginalContextAndIgnoresCurrentObservationState` — UNEXECUTED.
- `validLegacyHumanWireAndHmacRemainUnchanged` — UNEXECUTED.
- `humanCallAndReplyRejectTopLevelAndNestedDuplicateKeys` — UNEXECUTED.

Six Node source cases cover deterministic SQL+descriptor bytes, BaseEntity/immutable/delete identity guards, permanent unit/task uniqueness without deleted/retry identity changes, actual tenant/binary/hash collation policy, the existing DDL parser's exact new columns, and renderer rejection of extra/duplicate/invalid key identities. They are UNEXECUTED; node --check parsed syntax only.

Selected Vitest source adds exact new entity alignment for all three tables and scoped lineage/no-notify/no-cross-entitlement assertions, preserves the old notify task mapping, extends the exact confirmation edge expectation and negative unknown-member collision cases. These cases are UNEXECUTED. Root's general generated fixture reconciliation and actual owner/DB/transport/capability tests remain later; no blanket exemption or known-* file was added.

## Exact23 final source SHA256 freeze

`/tmp/task69a1-final-source-shas.json` SHA256 `aa3ef8cc9e7330f1b5129a495bf2b684a90374b2f9a569eeb59222092a5315d6`. The source inventory has6768 lines including existing modified files. This report is excluded from its own hash inventory.

| Kind | Path | Lines | SHA256 |
|---|---|---:|---|
| create | `backend/shop-base/src/main/java/ai/neargo/shop/spi/trade/ServiceExecutionPort.java` | 126 | `d5139a440ebd5fc2f4d36b620a2452e49b805e102cbf40c8ce1758937e709794` |
| create | `backend/shop-base/src/main/java/ai/neargo/shop/spi/trade/ServiceExecutionSourcePort.java` | 126 | `b5616bb44cd7026efbd0f41483398154ffdcc307b63631756e6dcd1f0dd6e070` |
| create | `backend/shop-base/src/main/java/ai/neargo/shop/spi/trade/ServiceExecutionContract.java` | 320 | `30c696797e64d45fd7f3078a19cea0349cf65bfda627366fc2ddd1a8330eb39f` |
| create | `backend/shop-base/src/main/java/ai/neargo/shop/spi/trade/ServiceExecutionInternalApi.java` | 30 | `fbfa2de4f2e009a962821486b4674c77feca84dffc161557ab9f158c81c1dff5` |
| create | `backend/shop-base/src/main/java/ai/neargo/shop/spi/trade/ServiceExecutionSourceInternalApi.java` | 24 | `aeb61d56eae353dd234957678a8d55542933a7ae79d2c363287b3465a078fa2a` |
| create | `backend/shop-base/src/main/java/ai/neargo/shop/spi/trade/ExecutionOriginalProtocol.java` | 73 | `9d57f297951ea3da637c2ab4da4d1558eaf865240ee294852d82b5c1fee3fa55` |
| create | `backend/shop-base/src/main/java/ai/neargo/shop/svc/ExecutionOriginalCodec.java` | 133 | `2a9813c2d6e637aeed9c7ab63f86ded8f56b5ee45cb15e695d1a90af48c7daaa` |
| create | `backend/shop-core/src/main/java/ai/neargo/shop/trade/entity/OrdServiceExecutionUnit.java` | 75 | `bf4ac74c1a990bda585cc68d9448435eecd35cb317da8cc11d7ca1c0dc040759` |
| create | `backend/shop-core/src/main/java/ai/neargo/shop/trade/entity/OrdServiceExecution.java` | 103 | `cf0b14eaefbac6778005996a18a2b52dd1ab3caf7196e78c225866ba9c30cbc4` |
| create | `backend/shop-core/src/main/java/ai/neargo/shop/trade/entity/OrdServiceExecutionAction.java` | 55 | `6d12d6b9d1b1bb27ad376f956024c4a6da3fa87035f8d0c0a3b5d1b64064700c` |
| create | `backend/shop-core/src/main/java/ai/neargo/shop/trade/mapper/ServiceExecutionMapper.java` | 295 | `895135d36f58a8df7734f577ecdf919b2615e9aaf5f183d57e7762c9d950d24f` |
| create | `backend/shop-core/src/main/java/ai/neargo/shop/trade/policy/ServiceExecutionSchemaVerifier.java` | 467 | `487bb9abdbdb3bbb184b6c14a924cc28163342fc464714358d018e9c9816e4ec` |
| create | `backend/shop-app/src/main/resources/db/migration/V544__manual_service_execution_authority.sql` | 257 | `daac4b0956de310676f8795d41ba34e412c66179549445dd76f1cdf740643fc1` |
| create | `docs/technical/design/schema/manual-service-execution-schema.json` | 1090 | `1c457505b172ac0c720f39861b27fc87e511bd75c9519aed966390c0d108747d` |
| create | `scripts/gen-manual-service-execution-schema.mjs` | 159 | `e237758449530fe8688f38a8392dfaae7505f41985e6d66e3b642b5fb56c8497` |
| create | `backend/shop-core/src/main/resources/META-INF/shop/manual-service-execution-schema.json` | 1135 | `eeb176b6a742553f9b0a7671864aa4604a4ac82cbae6579238fe0a81543ff850` |
| create | `backend/shop-base/src/test/java/ai/neargo/shop/spi/trade/ServiceExecutionContractTest.java` | 366 | `1d3cb598352fb7f660d201a4ebbab73e431692a10798cca431cc48dc8fe0ed42` |
| create | `scripts/tests/manual-service-execution-schema.test.mjs` | 88 | `f48f5bceac38f2fc03f5e8edcbc357a0e1849004d3707748acff985e0d1dc0fd` |
| modify | `backend/shop-base/src/main/java/ai/neargo/shop/svc/InternalBindings.java` | 251 | `af10d4f19082048af15e17f898dc243c5b74ffab45e05155ad386cc619c2d5aa` |
| modify | `backend/shop-base/src/main/java/ai/neargo/shop/svc/InternalReplyDeserializer.java` | 233 | `988cf3011965643676ba10318a3e7bf0cc5684bd22acacf9e01f9057dd9ad776` |
| modify | `packages/shared/tests/entity-alignment.test.ts` | 344 | `2fc0537a3f4e2946eca242dd8dc088027df7b1b0f4caf8230a8e4999d514c62f` |
| modify | `packages/shared/tests/schema-lineage.test.ts` | 453 | `7a938a8734213e9ef05ab94a57b134285abaec3b5dc7f15c6269c8e059854009` |
| modify | `scripts/lib/schema-lineage.mjs` | 565 | `939b8e5b2ba22c2f664262abd97e4944f02d16ab9389ca574dc1247fccf6c245` |

## Self-review and remaining acceptance

Self-review read every owned source/diff against the brief, exact inventory and design narrowing. It corrected inherited audit-column collation expectations; scoped M3 insert states/generation/expected-one-row SQL; cap/cursor semantics; independently nullable observed suspension facts; no unknown/provider/schema failure as a durable contradiction; exact trigger-set checks; generic lineage preservation; and the root-required shared duplicate-key parser without HUMAN depth tightening. No incomplete business provider, empty owner stub, permission-granting constructor or runtime success fallback was added.

Source/compilation/static-generation evidence supports **DONE_PENDING_ROOT_REVIEW only**. Root must independently review these exact23 bytes and publish its global docs/ledger/registries/general generated artifacts. Actual installed-schema classification, SQL/mapper execution/concurrency, transport authorization/purpose/error handling, real source/stock/person/plan proof, resource effects and old/new fixture composition remain user-authorized unified phaseB, deployment phaseC and later released A2–A5/M4–M6 slices. No runtime acceptance, canStart or Pay completion is claimed.


## Fix round1/5 — R0 I1 inherited LONGTEXT metadata correction

Status **DONE_PENDING_ROOT_REVIEW** after the original author's scoped round1 correction. This appendix supersedes the earlier final23 hash freeze and earlier six-Node-case count; it preserves that initial freeze as review history. Root's task-69a1-fix-round-1.md and independent review-task-69a1-r0.md identified one Important: binding_json/proof_json LONGTEXT inherited actual table charset/collation, but their descriptor expected null/null, causing exact metadata inspection to classify a valid installation CORRUPT.

The receiving-code-review workflow verified the mismatch directly against checkColumn and the renderer before editing. Both schema-truth LONGTEXT entries now use charset:null/collation:OWNER_TENANT, exactly the existing inherited-column policy. The unchanged verifier resolves that policy to the inspected owner's utf8mb4 charset and tenant collation; no exact physical checks were weakened. The unchanged pure renderer regenerated the bundled descriptor. V544 SQL, its SHA256 and Flyway checksum are byte-identical: inherited storage DDL already represented the desired physical schema.

Exactly three of the existing23 source paths differ from root's preserved task-69a1-r0-base: schema truth, bundled descriptor and existing scripts/tests/manual-service-execution-schema.test.mjs. A semantic JSON comparison confirmed that the only schema/descriptor changes are the two LONGTEXT collation policy values. All other20 source files, including the verifier, renderer, Java contracts/tests, mappers/entities, V544 and selected shared tests, are byte-identical to R0. No historical SQL, root ER/general H2 utility/docs/registries, new path, method signature or broader architecture changed.

Authored seventh Node case `every textual column describes inherited or explicit physical charset and collation` covers every VARCHAR/CHAR/TEXT-family column in actual render output. It checks truth-to-descriptor metadata fidelity, requires an explicit effective-collation policy, checks inherited declarations omit charset/collation overrides, checks explicit binary declarations and verifies both LONGTEXT payloads use OWNER_TENANT. The prior null/null entries would violate these assertions. This case, the other six Node cases, all19 JUnit methods and selected Vitest cases are **UNEXECUTED**. Real installed-schema/changed-collation scenarios remain unified phaseB; no runtime acceptance is claimed.

Executed permitted commands:

```sh
node scripts/gen-manual-service-execution-schema.mjs
node scripts/gen-manual-service-execution-schema.mjs --check
node --check scripts/tests/manual-service-execution-schema.test.mjs
node --check scripts/gen-manual-service-execution-schema.mjs
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 -f /private/tmp/task69-m3-current-source-reactor/pom.xml -Pbeauty-embedded -pl :shop-app -am test-compile -DskipTests
```

The pure renderer rebuild completed without modifying V544. Fresh separate --check/Node syntax calls each returned exit0 and no stdout. The current-source Maven command returned exit0, BUILD SUCCESS,24 reactor modules,2.022s. Java/test sources were unchanged/up to date, so this run copied the corrected resource instead of claiming new Java compilation counts. Read-only byte comparison confirmed the current reactor target/classes descriptor equals the corrected source resource. Previous final-source Java compilation remains the recorded compile6 on identical Java bytes. No JUnit/Node/Vitest test, serializer/golden/probe, SQL/migration/application, network, deployment, commit/index/reset or subagent operation was performed.

Compile log `/tmp/task69a1-fix-round-1-test-compile.log` SHA256 `42a6854d4fa7f0231330128cc230cc32b38416cf89914761533d8e79ca7e567d`. V544 remains SHA256 `daac4b0956de310676f8795d41ba34e412c66179549445dd76f1cdf740643fc1`, checksum `-1519445049`. Verifier remains SHA256 `487bb9abdbdb3bbb184b6c14a924cc28163342fc464714358d018e9c9816e4ec`.

Final23 manifest `/tmp/task69a1-final-source-shas.json` is now SHA256 `a08807155e77dcecf470972c13e097748d5b9b6a062673cf4605c6a19bf12c8b`; R0 manifest is preserved at `/tmp/task69a1-final-source-shas-r0.json`. Current source inventory has 6803 lines including unchanged modified-file content. Report is outside its own23-source hash inventory.

| Kind | Path | Lines | Round1 final SHA256 |
|---|---|---:|---|
| create | `backend/shop-base/src/main/java/ai/neargo/shop/spi/trade/ServiceExecutionPort.java` | 126 | `d5139a440ebd5fc2f4d36b620a2452e49b805e102cbf40c8ce1758937e709794` |
| create | `backend/shop-base/src/main/java/ai/neargo/shop/spi/trade/ServiceExecutionSourcePort.java` | 126 | `b5616bb44cd7026efbd0f41483398154ffdcc307b63631756e6dcd1f0dd6e070` |
| create | `backend/shop-base/src/main/java/ai/neargo/shop/spi/trade/ServiceExecutionContract.java` | 320 | `30c696797e64d45fd7f3078a19cea0349cf65bfda627366fc2ddd1a8330eb39f` |
| create | `backend/shop-base/src/main/java/ai/neargo/shop/spi/trade/ServiceExecutionInternalApi.java` | 30 | `fbfa2de4f2e009a962821486b4674c77feca84dffc161557ab9f158c81c1dff5` |
| create | `backend/shop-base/src/main/java/ai/neargo/shop/spi/trade/ServiceExecutionSourceInternalApi.java` | 24 | `aeb61d56eae353dd234957678a8d55542933a7ae79d2c363287b3465a078fa2a` |
| create | `backend/shop-base/src/main/java/ai/neargo/shop/spi/trade/ExecutionOriginalProtocol.java` | 73 | `9d57f297951ea3da637c2ab4da4d1558eaf865240ee294852d82b5c1fee3fa55` |
| create | `backend/shop-base/src/main/java/ai/neargo/shop/svc/ExecutionOriginalCodec.java` | 133 | `2a9813c2d6e637aeed9c7ab63f86ded8f56b5ee45cb15e695d1a90af48c7daaa` |
| create | `backend/shop-core/src/main/java/ai/neargo/shop/trade/entity/OrdServiceExecutionUnit.java` | 75 | `bf4ac74c1a990bda585cc68d9448435eecd35cb317da8cc11d7ca1c0dc040759` |
| create | `backend/shop-core/src/main/java/ai/neargo/shop/trade/entity/OrdServiceExecution.java` | 103 | `cf0b14eaefbac6778005996a18a2b52dd1ab3caf7196e78c225866ba9c30cbc4` |
| create | `backend/shop-core/src/main/java/ai/neargo/shop/trade/entity/OrdServiceExecutionAction.java` | 55 | `6d12d6b9d1b1bb27ad376f956024c4a6da3fa87035f8d0c0a3b5d1b64064700c` |
| create | `backend/shop-core/src/main/java/ai/neargo/shop/trade/mapper/ServiceExecutionMapper.java` | 295 | `895135d36f58a8df7734f577ecdf919b2615e9aaf5f183d57e7762c9d950d24f` |
| create | `backend/shop-core/src/main/java/ai/neargo/shop/trade/policy/ServiceExecutionSchemaVerifier.java` | 467 | `487bb9abdbdb3bbb184b6c14a924cc28163342fc464714358d018e9c9816e4ec` |
| create | `backend/shop-app/src/main/resources/db/migration/V544__manual_service_execution_authority.sql` | 257 | `daac4b0956de310676f8795d41ba34e412c66179549445dd76f1cdf740643fc1` |
| create | `docs/technical/design/schema/manual-service-execution-schema.json` | 1090 | `3febfef28f90cd32fe5ecd7dff604dc620565109178e31e8688d3d7320cc6547` |
| create | `scripts/gen-manual-service-execution-schema.mjs` | 159 | `e237758449530fe8688f38a8392dfaae7505f41985e6d66e3b642b5fb56c8497` |
| create | `backend/shop-core/src/main/resources/META-INF/shop/manual-service-execution-schema.json` | 1135 | `42ad3a0b965391cd06afd434a6f6e90d359aef2812b6776ccc0ef7293f58443e` |
| create | `backend/shop-base/src/test/java/ai/neargo/shop/spi/trade/ServiceExecutionContractTest.java` | 366 | `1d3cb598352fb7f660d201a4ebbab73e431692a10798cca431cc48dc8fe0ed42` |
| create | `scripts/tests/manual-service-execution-schema.test.mjs` | 123 | `563fa80c2ea0d0215034b4df081d442abd2cd810d329c0c8bc6af6c8f2e35600` |
| modify | `backend/shop-base/src/main/java/ai/neargo/shop/svc/InternalBindings.java` | 251 | `af10d4f19082048af15e17f898dc243c5b74ffab45e05155ad386cc619c2d5aa` |
| modify | `backend/shop-base/src/main/java/ai/neargo/shop/svc/InternalReplyDeserializer.java` | 233 | `988cf3011965643676ba10318a3e7bf0cc5684bd22acacf9e01f9057dd9ad776` |
| modify | `packages/shared/tests/entity-alignment.test.ts` | 344 | `2fc0537a3f4e2946eca242dd8dc088027df7b1b0f4caf8230a8e4999d514c62f` |
| modify | `packages/shared/tests/schema-lineage.test.ts` | 453 | `7a938a8734213e9ef05ab94a57b134285abaec3b5dc7f15c6269c8e059854009` |
| modify | `scripts/lib/schema-lineage.mjs` | 565 | `939b8e5b2ba22c2f664262abd97e4944f02d16ab9389ca574dc1247fccf6c245` |

I1 source correction is ready for independent root re-review. Installation/runtime capability and phaseB/C acceptance remain pending; this author does not self-approve Spec/Quality or canStart/Pay.
