# Task69A1 finite contract/hash and duplicate-wire rulings

Root engineering clarification of accepted M3 design before source freeze; no new business/public routes/schema tables/dependencies. Source review remains required. Existing exact23 source scope unchanged.

## Pure hash ownership
Accept ServiceExecutionContract.bindingJson(ExecutionBinding):String as exact MANUAL_EXECUTION_V1 schema/binding storage wrapper and suspensionJson(ExecutionBinding,SourceSuspension):String as exact BEAUTY_EXECUTION_SUSPEND_V1 schema/binding/suspension wrapper; verify proof hash and self exclusion, align required JSON paths with DDL.
Accept intentHash(StartIntent):String, domain BEAUTY_EXECUTION_INTENT_V1 followed by newline, covering all immutable original fields (complete binding, admission kind/customer party digest, original personnel/namespace/resource coordinates/frozen plan/hash), excluding mutable state/intentRevision/continuity/suspension. source_intent_hash is a different proof from payloadHash and must not be equated.
Accept bindingHash domain MANUAL_EXECUTION_V1 newline over exact full binding except payloadHash; payloadHash domain MANUAL_EXECUTION_V1 newline over its explicitly different typed canonical context wrapper (schema/quantity1/uomCOUNT/binding body/person/namespace/resource). Typed body shapes must stay distinct and documented. actionHash uses MANUAL_EXECUTION_ACTION_V1 newline and complete action/lookup context. suspensionHash uses BEAUTY_EXECUTION_SUSPEND_V1 newline with proofHash omitted plus original binding. No helper hashes its own output. Canonical explicit nulls/sorted keys/integer numbers unchanged; old source/plan hash values only embedded.
Author pure immutable-vs-mutable context assertions without executing them; report all actual signatures/domains.

## Actual HUMAN duplicate gap and bounded correction
Root source check found both InternalReplyDeserializer human call/reply use context.readTree(parser), with no applicable global strict duplicate feature. NewM3 human routes require duplicate rejection; postponing leaves a load-bearing wire gap.
Ruling supersedes A1brief visibility-only restriction for this exact purpose: centralize recursive strict-tree reading in already-owned generic InternalReplyDeserializer, reuse for both human read sites and new ExecutionOriginalCodec. Preserve existing shape validator/type/null rules and all valid HUMAN serialization/HMAC/canonical bytes. Tighten only malformed duplicate field acceptance, including nested records/maps; no global ObjectMapper flag or unrelated decoder/framework refactor.
Do not make generic helper depend on execution-specific codec; newcodec may depend on generichelper as already does for requireShape. Keep HUMAN existing parser-configured nesting constraints, not silently impose ORIGINAL64 cap; ORIGINAL retains its own finite bound via helperargument/wrapper if needed. Use generic safe errors.
Within already-owned ServiceExecutionContractTest author actual typed validlegacy HUMAN roundtrips, top/nested HUMAN duplicates and HUMAN/ORIGINAL substitution/domain/error checks. All remain UNEXECUTED; final covering current-source test-compile after changes. No extra source paths.

## Definitive proof versus uncertainty
SourceSuspension cannot be minted from SOURCE_UNKNOWN, RESOURCE_EXECUTION_UNAVAILABLE or EXECUTION_SCHEMA_UNAVAILABLE. Present observed revision/hash individually validate; required cause tuple persists actual contradiction/withdrawal, not missing provider/query. IndependentSourceContinuity reports PENDING and retains original quantity. This is shape policy only; A2actualowner produces durableproof and A3validatesoriginalassociation.

## Root-owned generated artifacts
After exact23freeze root regenerates global schema-test.sql and ER from actualcurrentSQL, records derived file inventory, and includes relevant root-derived source in review/closure. Static generated H2 schema is not actual Flyway install/capability evidence. Do not fabricate marker or pretend tables-without-marker are ABSENT; C18 must reconcile actual combinedfixture/marker semantics. No SQL/database/test runtime now.
