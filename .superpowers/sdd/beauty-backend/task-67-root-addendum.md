# Task67 source-backed implementation addendum

Status: STAGED_IMPLEMENTATION_RELEASED; exact brief/inventory control test-first phases and separate one-use native gates. BASE 2c0708f752d747d88a9eda7478326a964d1984fa. This addendum belongs to Task67 code review; it does not alter frozen Task65 design or claim earlier review coverage.

## Reception without C login

Actual OpenWorkOrderReq currently only accepts customerRef/guestNo. ReceptionIdentity.customer requires cr1_ minted from mc1_; guestNo can resolve a real CLAIMED guest to USER but covers only prior-claimed guests. Ordinary active memberNo needs a no-C reception route for the authorized cash core.

Append optional String memberNo to OpenWorkOrderReq, retaining both previous eight-argument and four-argument constructors. New memberNo is bounded to64, nonblank/unpadded and only supported for unassigned opening; exactly one customerRef/guestNo/memberNo. Reject memberNo in appointment opening; existing appointment identity semantics remain. Null memberNo must be excluded only on this appended property so legacy serialized request/fingerprint bytes remain identical.

Add ReceptionIdentity.customerMember(entity,store,memberNo):String. Reuse actual CustomerIdentityPort.resolveCounterReference/CounterReferenceQuery and current merchant CUSTOMER authorization, before and after owner IO; compare full current actor namespace after IO. This is selection, not purchase acceptance or consent. Never invent a short ticket, consumer session, userNo or member. WorkOrderCoordinator freezes the actual USER once in its existing durable operation/context; retries retain original identity/hash and recheck current authorization. Existing guestNo path stays unchanged. No raw credentials are persisted in payload. Prove real no-C member/GUEST/claimed-guest opening, wrong/inactive/foreign selector rejection and unchanged legacy fingerprints/constructors.

## Actual schema startup owners

V9 needs BeautySchemaValidator, BeautySchemaReady, BeautyPersistenceConfiguration and BeautyWriteGuardConfiguration to advance exact version8 to9 and validate actual new structure/domain trigger. Retain all frozen V1–V8 hashes and guards; do not relax unrelated structure validation. Old V8 runtime is the immutable actual Task66 snapshot captured before code edits: task-67-evidence/pre-v9-runtime/manifest.json,755files/4354095B/SHA21052bb9d4083030ea457ad6f9ad419931a8b314d9abed4ff6e22e7829e68e23.

Production embedded beauty uses the platform primary dataSource/factory/transaction manager. Fixture topology must respect that: current embedded primary contains platform+bty; inventory is schema2; isolated upgrade primary contains its own bounded platform+bty V8→V9 as schema3. The third schema is test isolation, not a production beauty-specific DataSource. Separate explicit old/current JVMs use the same upgrade primary sequentially; no snapshot classes copied into current target, no stale .m2 provider or install.

## Actual projection owners

StoreOrderAssembler produces StoreOrderView/StoreOrderSummary, never OrderVO. If OrderServiceImpl can attach the actual safe local receipt on all existing OrderVO projections and with/copies, reuse StoreOrderAssembler unchanged and reconcile the planned-unused path. Do not add another merchant view contract merely to change a planned file.

## Existing query and source dependencies

Explicitly own ServiceSubmissionQuery V2-only reader, BeautyMappers V2-only recovery predicates, actual ManualServiceAdmissionPort source projection producer and bound remote consumer. Extend only validated V3 manual namespace, preserve old command bytes/hash and old financial lane. MP155 removes the local receipt-only GUEST OR and proves current canonical member claim outside beauty transaction before child reads; re-read unchanged root/context/current actor before detail. Never widen null buyer access.

## Phase gates

User requires code/compile first and unified formal deployment later. No Pay integration, new money/quantity ledger, resource authority or canStart enablement in Task67. Actual native SQL/old+current JVM fixture runs require root finite phase/method/UUID/source+class+SQL+runner pin gate after preparation inspection. One meaningful ablation after GREEN only. Root owns documents, ledger, native gate and commits.

## Follow-up pre-native source checks

MemberNo lane must freeze original full merchant actor namespace before any identity/catalog/definition IO and compare it again after preparation, before ReceptionPreparation.begin chooses the durable namespace. Rechecking any permitted current actor is insufficient when original request fingerprint came from another full namespace. Old reception lanes are unchanged.

Main TDD global§1.5 requires generated schema from reviewed source. V8 model/renderer owns exactly its frozen CREATE table and cannot be repurposed. Add exactly three V9 files: beauty-manual-source-observation-schema.json, gen-beauty-manual-source-observation-schema.mjs, and scripts/tests/beauty-manual-source-observation-schema.test.mjs. New source models bounded ALTER of the existing observation/binding/allocation-guard tables and immutable-domain trigger. Reuse writeFrozenMigration unchanged; validate identifiers/ownership/column types and constraint references, deterministic --check, no arbitrary cross-domain SQL. Root authorizes this narrow schema-source work by the implementer only after accepted source RED, with root inspection of model/rendered SQL before native; no changes to existing V8 generator/model/output or shared helper. Exact inventory is46 paths after this source-backed addition.


## Source-backed V9 CHECK normalization ruling (scope46→48)

Actual firstV9 execution852e3b75 applied all9migrations but failed strictcheck startup before business; diagnostic a955b277 emitted complete expected/actualcanonicalmaps. All5 existing bindingconstraints exactlyequal. New domain CHECK differs only sameoperator AND grouping, e.g. `A AND (B AND (USER OR GUEST))` vs `(A AND B) AND (USER OR GUEST)`. ActualMySQL flattens this grouping. Bothruns1error/0skip,548pinsunchanged/ownedcleanup independentlyverified; neither businessRED norGREEN.

Release existing BeautyCheckExpression.java + BeautyCheckExpressionTest.java, total48paths. Normalize only associativity of samebooleanAND/OR in existingclosedparser, retainoperandorder and mixedoperator/comparison/arithmeticgrouping, exactliterals/functions/columns/thresholds. Keep existingbinaryformat foralready leftassociatednodes; no sorting, distributivity, uncheckedstripping or secondname-specificcanonicalizer. FirstpureassertionRED foractualpair thenminimalfix/allfocusedparsercontrolsGREEN, compile/freshpins/same-business-methodnativegate. SQL/model/frozenhelperremainunchanged. Cost is2scopedfiles/review beyondoriginal46; semanticallyrealchangedconstraints muststillfail.


## Manual projection regression ruling (scope48→49)

Actual observer.manualProjection uses legacy observation_epoch/observed_at although §3.2 requires manual epoch/time/failure formanual projection. Release one focused core unit ServiceSourceObservationTest.java (beauty/service/impl testpackage) fordecisiveRED→minimalownedobserverfix→GREEN. Adoptedmanualrow cancarry distinct priorfinancialepoch/time whilemanualcurrentvalues differ; assert onlymanualepoch/time projected andstoredfinancialhistory unchanged, including unavailable behavior. Pureprojection test uses constructor mocks only, no fabricatednativeowner facts. Do not mixJUnit regression into historicaldriver main. No extra productpath/migration/dependency/nativeauthority.


## Currency semantic test home (scope49→50)

Release focused core beauty/service/impl/WorkOrderBillServiceImplTest.java: pureDTO CNY/USD grouping and unknownsubtotal-null semantic controls via existing private currencies function, no new production API/dependency. This is purearithmetic/projectionevidence only; nativeCNYfunded+manual actualowner proof remains separately88455e4a. Driver remains solely childruntime protocol, no unrelatedJUnit tests.

## Funded-domain rejection reason ruling

Accepted §3.2 requires CONFLICT with safe SOURCE_DOMAIN_CONFLICT while leaving ineligible funded rows unchanged before any ticket claim. Existing ErrorCode.CONFLICT maps to err.conflict with no placeholder; unused interpolation arguments would not expose the reason. Keep the existing HTTP code/message/JSON contract. Use a minimal named nested BizException subtype in the owned ServiceSourceObservation carrying reasonCode()=SOURCE_DOMAIN_CONFLICT as an internal source diagnostic. Only ineligible funded-domain rejection uses it; identity/version/adoption races remain ordinary CONFLICT. No new ErrorCode/i18n/DTO, no persisted row failure rewrite on rejection, and no generic string-message parsing. Focused public claim helper test with local mapper/transaction doubles must first fail on absent safe reason, then pass; the actual funded READY/UNKNOWN/allocation cases assert the same diagnostic. Cost if wrong: diagnostic location may need revisiting for a later public error-contract change; this task does not silently invent a new HTTP field.

## Actual manual allocation mapper signature reconciliation

Source inspection confirms the existing COUNT allocation algorithm needs incrementQty, netQuantity and actorNo/updatedAt atomically with the observation epoch/pair CAS. Actual allocateManualObservedQuantity therefore has11arguments, mirroring the existing allocation owner rather than the proposed shortened8argument list. Keep that implementation; paired API/Java method metadata now records the exact11argument signature, refreshManualSnapshot, hasAllocations, claimManualObservation and the two proof-before-detail claim selectors. No new ledger, scope widening or source mutation is authorized by this documentation reconciliation.
