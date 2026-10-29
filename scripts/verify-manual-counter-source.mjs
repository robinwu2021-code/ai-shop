import assert from 'node:assert/strict';
import {execFileSync, spawn} from 'node:child_process';
import {createHash, randomBytes} from 'node:crypto';
import {readFileSync, writeFileSync, mkdirSync, rmSync, existsSync, realpathSync, rmdirSync,
  readdirSync, statSync, lstatSync, chmodSync} from 'node:fs';
import {join, relative, resolve, dirname, sep} from 'node:path';
import {fileURLToPath, pathToFileURL} from 'node:url';

const root = realpathSync(fileURLToPath(new URL('../', import.meta.url)));
const workspace = '.superpowers/sdd/beauty-backend';
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
const nativeGate = slice => workspace + (slice === 'm2b'
  ? '/task-67-native-root-gate.json' : '/task-66-native-root-gate.json');
const beautyRoot = 'backend-plugins/shop-industry-beauty/';
const beautyMigrations = [
  'V1__beauty_p1_tables.sql', 'V2__beauty_appointment_resource_fence.sql',
  'V3__beauty_appointment_business.sql', 'V4__beauty_appointment_resource_decision.sql',
  'V5__beauty_reception_work_order.sql', 'V6__beauty_reception_identity_guard.sql',
  'V7__beauty_appointment_assignment_units.sql', 'V8__beauty_source_observation.sql'
].map(file => beautyRoot + 'beauty-core/src/main/resources/db/industry/beauty/' + file);
const legacyCheckoutFixture = workspace + '/task-66-evidence/pre-native/pre-v542-legacy-checkout.sql';
const counterMigration = 'backend/shop-app/src/main/resources/db/migration/'
  + 'V602__shared_manual_order_customer_party.sql';
const migrations = [
  'backend/shop-app/src/main/resources/db/migration/V599__checkout_source_foundation.sql',
  'backend/shop-app/src/main/resources/db/migration/V600__checkout_payment_operation.sql',
  'backend/shop-app/src/main/resources/db/migration/V601__manual_service_admission.sql',
  'backend/shop-inventory/src/main/resources/db/inventory/V16__original_stock_disposition.sql'
];

const m2cMethods = Object.freeze({
  rules: ['equalAliasesRemainFrozenAndResolveOnce', 'canonicalGoodsAggregateExactlyAndSnapshotCollections',
    'rawOwnerAliasesBindToCanonicalAppliedAndSharesWithoutLiveLookup', 'digestAndFrozenChoiceShapeRejectTampering',
    'digestUsesExactPositionalTupleIncludingNullableFields', 'originalPartyGroupChoiceAndAllocationMustMatchExactly',
    'strictGoodsParsingCannotTurnMalformedScopeIntoWholeGroup', 'ownerPredicatesUseFrozenObjectiveFactsOnly',
    'mandatoryPriceAndGiftPredicatesIgnoreDiscretionaryChoiceButRequireRelevantValidFacts',
    'sealAndCandidateCollectionsCannotBeMutatedAfterEvaluation'],
  compatibility: ['exactOldConstructorAndGenuineGoldenBytesRoundtrip',
    'schemaOneEnvelopeAndBarePlanKeepDisplayOutsideAuthority',
    'schemaOnePlansPreserveExplicitPartyAndRejectContradictoryCommand',
    'newZeroAndNoneCarrySealsAndOnlyTheirFrozenNoneOption',
    'oldScopeConstructorsKeepTheirLaneAndTrustedPoliciesRejectMixedContext',
    'strictRawShapeRejectsUnknownMissingNullDuplicateAndCoercedFields',
    'optionsAndSelectedEconomicsCannotContradictFrozenSeal',
    'frozenOwnerAliasesRoundtripAndLegacyCannotCarryPositiveMoney'],
  fence: ['rawInsertUpdateSoftAndPhysicalDeleteCoverEveryPredicateTable',
    'immutableIdentityAndParentLinkageRejectEvenCaseOnlyChanges',
    'missingAndCrossTenantParentsNeverGuessAnUnprotectedOwner',
    'platformHeadPublishedVersionAndEnrollmentUseTheirActualScopes',
    'lazyMissingEntityInsertionContendsUntilTheFirstTransactionEnds',
    'sharedReadersProceedAndRawWriterWaitsUntilBothTransactionsEnd',
    'emptyReaderFenceBlocksRawInsertIntoEveryPredicateTable',
    'committedWriterIsVisibleToStatementsAfterFenceAcquisition',
    'lockTimeoutMarksWholeTransactionRollbackOnlyEvenIfCallerCatchesIt',
    'rawOppositeOrderDeadlockAbortsOneWholeOwnerTransaction',
    'absentOrLiteralAlteredTriggerClosesCapabilityAndRestorationReopensIt',
    'missingPlatformSeedAndWrongTransactionCannotSucceed',
    'canonicalReaderBlocksCaseVariantHeadInsertsAndExistingUpdatesAndDeletes',
    'accentAndTrailingSpaceAliasesUseActualFixtureCollationEquivalence',
    'tenantAndEnrollmentAliasesShareFenceWithoutRelaxingParentIdentity',
    'everyEffectiveOwnerColumnOverrideClosesCapabilityDespiteUnchangedTableDefault',
    'missingRequiredOwnerBindingClosesCapabilityUntilTheActualTableReturns',
    'fenceIdentityColumnsAndDatabaseDefaultsMustMatchEffectiveOwnerCollation'],
  discounts: ['merchantFullCutSealsSameSharesForUserAndGuest',
    'explicitNonBestNoneOmissionAndChoiceKeys', 'counterOptionsRemainFrozenAcrossReadAndOriginalReplay',
    'unsupportedOffersNeverEnterCounterOptions', 'accountCouponPointsAndPromotionChoicesAreRejected',
    'unsupportedAutomaticOfferIsNotSilentlyDropped', 'nonemptyGoodsScopeAndMalformedRuleFailClosed',
    'changedMerchantDiscountRequiresNewAcceptance', 'sameAmountRuleVersionThresholdAndWindowChangesAreStale',
    'discountCannotMakeManualOrderFree', 'noneAndEmptyAutoAreVerifiedUnderOwnerFence',
    'platformEnrollmentAndPublishedVersionRaceIsFenced',
    'oldPendingCounterKeepsZeroEconomicsAfterPromotionRollout',
    'consumedCounterRejectsAllocationPartyAndAuthorityTampering',
    'discountExceptionRequiresSealedCounterAuthority',
    'counterStillRequiresOriginalInPersonAcceptance', 'sqlOwnerAliasesKeepCanonicalMoneyAndRawRuleFacts']
});
const oldPendingMethod = 'oldPendingCounterKeepsZeroEconomicsAfterPromotionRollout';
const m2cClasses = Object.freeze({
  rules: ['backend/shop-core', 'ai.neargo.shop.marketing.campaign.CounterCampaignRulesTest'],
  compatibility: ['backend/shop-core', 'ai.neargo.shop.trade.order.service.create.CounterEconomicCompatibilityTest'],
  fence: ['backend/shop-app', 'ai.neargo.shop.scenario.CounterCampaignFenceIT'],
  discounts: ['backend/shop-app', 'ai.neargo.shop.scenario.ManualCounterDiscountIT']
});
const goldenNames = Object.freeze(['ordinary-v1.json', 'm1-v1.json', 'm2a-user-v1.json',
  'm2a-guest-v1.json', 'm2a-pending-plan-v1.json', 'hashes.json']);
const pendingKinds = Object.freeze([['USER', 'USER'], ['GUEST', 'GUEST'],
  ['BASE_CHANGE', 'USER'], ['CATALOG_CHANGE', 'GUEST']]);
const m2cStages = Object.freeze({
  'materialize-runtime': ['prepare', 'MATERIALIZE_RUNTIME'],
  'materialize-goldens': ['prepare', 'MATERIALIZE_GOLDENS'],
  'counter-protocol': ['green', 'TEST'],
  'counter-old-pending': ['green', 'PRODUCE_OLD_AND_TEST'],
  'counter-owner-fence': ['green', 'TEST'],
  'counter-discounts': ['green', 'TEST']
});

/** Root-controlled finite stages prevent a TDD owner run from silently expanding into all suites. */
export function phaseSelection(slice, phase, stage = 'm2a') {
  if (slice === 'm2c') {
    assert.equal(phase, 'green', 'Task68 finite test selection requires green');
    const entry = key => [...m2cClasses[key], [...m2cMethods[key]]];
    if (stage === 'counter-protocol') return [entry('rules'), entry('compatibility')];
    if (stage === 'counter-owner-fence') return [entry('fence')];
    if (stage === 'counter-old-pending') return [[...m2cClasses.discounts, [oldPendingMethod]]];
    if (stage === 'counter-discounts') return [[...m2cClasses.discounts,
      m2cMethods.discounts.filter(method => method !== oldPendingMethod)]];
    throw new Error('unreleased Task68 finite stage');
  }
  if (slice === 'm2b' && stage === 'source-review-recovery' && ['red', 'green'].includes(phase)) {
    return [['backend/shop-app', 'ai.neargo.shop.scenario.ManualSourceBindingIT', [
      'createdManualPrepareRecoveryRequiresCurrentSourceFacts',
      ...(phase === 'green' ? ['startupRejectsLiveTriggerLiteralDrift'] : [])]]];
  }
  if (slice === 'm2b' && stage === 'source-ticket-ablation' && ['ablation', 'restored'].includes(phase)) {
    return [['backend/shop-app', 'ai.neargo.shop.scenario.ManualSourceBindingIT',
      ['lateAdmittedReplyCannotCompleteNewerUnavailableTicket']]];
  }
  if (slice === 'm2b' && stage === 'source-final-baseline' && phase === 'green') {
    return [['backend/shop-app', 'ai.neargo.shop.scenario.ManualSourceBindingIT', [
      'fundedEvidenceRejectsManualAdoptionBeforeAndAfterAllocation',
      'lateAdmittedReplyCannotCompleteNewerUnavailableTicket']]];
  }
  if (slice === 'm2b' && stage === 'source-upgrade' && phase === 'green') {
    return [['backend/shop-app', 'ai.neargo.shop.scenario.ManualSourceBindingIT',
      ['preM2UnknownManualPlaceholderAdoptsSameIdentityAfterV9']]];
  }
  if (slice === 'm2b' && stage === 'source-matrix-fixture-fixes' && phase === 'green') {
    return [
      ['backend/shop-app', 'ai.neargo.shop.scenario.ManualSourceBindingIT', [
        'manualPrepareContinuesOriginalWithoutRawGuestCredential', 'claimedGuestContextM1UserKeepsVerifiedRelation']],
      ['backend/shop-app', 'ai.neargo.shop.scenario.ManualSourceBillIT', ['mixedSourceCurrencyTotalsRemainSeparate']]
    ];
  }
  if (slice === 'm2b' && stage === 'source-matrix' && phase === 'green') {
    return [
      ['backend/shop-app', 'ai.neargo.shop.scenario.ManualSourceBindingIT', [
        'twoWorkOrdersCannotAllocateLastUnit', 'm1ConsumerManualExistingUsesSourceFactsWithoutCounterQuote',
        'counterUserAndGuestExistingSourcesKeepHistoricalParty', 'manualExistingRecoveryNeedsNoRawGuestCredential',
        'manualAndFundedHashesNeverAlias', 'lateObservationCannotReviveSuspendedSource',
        'itemAndMappingRevisionAdvanceManualPairOnly', 'memberReceptionRejectsBadSelectorsAndReplaysFrozenIdentity',
        'memberReceptionRechecksAuthorizationAfterCatalogIO', 'manualPrepareContinuesOriginalWithoutRawGuestCredential',
        'memberReceptionRejectsAllowedNamespaceShiftAfterCatalogIO', 'claimedGuestContextM1UserKeepsVerifiedRelation',
        'manualTypedDomainRejectsFinancialWritesAndStructuralDrift']],
      ['backend/shop-app', 'ai.neargo.shop.scenario.ManualSourceBillIT', [
        'manualDeclarationIsNotPayVerification', 'mixedSourceCurrencyTotalsRemainSeparate',
        'claimedMp155RequiresCurrentMemberOwner']]
    ];
  }
  if (slice === 'm2b' && stage === 'reception-member' && ['red', 'green'].includes(phase)) {
    return [['backend/shop-app', 'ai.neargo.shop.scenario.ManualSourceBindingIT',
      ['noConsumerReceptionUsesCanonicalMemberSelector']]];
  }
  if (slice === 'm2b' && stage === 'source-binding-existing' && ['red', 'green'].includes(phase)) {
    return [['backend/shop-app', 'ai.neargo.shop.scenario.ManualSourceBindingIT',
      ['manualUserAndGuestBindExistingOrdinaryLines']]];
  }
  assert(slice === 'm2a' && ['red', 'green', 'ablation', 'restored'].includes(phase),
    'invalid Task66 selection');
  if (stage === 'current-claims' && ['red', 'green'].includes(phase)) {
    return [['backend/shop-app', 'ai.neargo.shop.scenario.ManualCounterPrivacyIT',
      ['memberOwnerUnavailableFailsClosed']]];
  }
  if (stage === 'counter-purchase' && ['red', 'green'].includes(phase)) {
    return [['backend/shop-app', 'ai.neargo.shop.scenario.ManualCounterPurchaseIT',
      ['actualUserAndGuestBuyWithoutConsumerSession']]];
  }
  if (stage === 'counter-bridge' && ['red', 'green'].includes(phase)) {
    return [['backend/shop-app', 'ai.neargo.shop.scenario.ManualCounterIdentityIT',
      ['boundUserAndClaimedGuestUseActualMemberOwners']]];
  }
  if (stage === 'counter-read-authorization' && ['red', 'green'].includes(phase)) {
    return [['backend/shop-app', 'ai.neargo.shop.scenario.ManualCounterIdentityIT',
      ['counterReadRequiresExactProofOrCurrentSession']]];
  }
  if (stage === 'counter-cashier' && ['red', 'green'].includes(phase)) {
    return [['backend/shop-app', 'ai.neargo.shop.scenario.ManualCounterPurchaseIT',
      ['differentAuthorizedCashierResumesOriginalOnce']]];
  }
  if (stage === 'counter-recovery-diagnostic' && phase === 'green') {
    return [['backend/shop-app', 'ai.neargo.shop.scenario.ManualCounterRecoveryIT',
      ['lostReserveReplyObservesOriginalRows', 'frozenInventoryOwnerReleasesAfterCurrentSkuMappingChanges']]];
  }
  if (stage === 'counter-recovery-fixes' && phase === 'green') {
    return [
      ['backend/shop-app', 'ai.neargo.shop.scenario.ManualCounterRecoveryIT', [
        'lostReserveReplyObservesOriginalRows', 'staleOriginalReleaseLostReplyRemainsCompensatingAndNeverReservesAgain',
        'finalizeAndCompensateHaveOneWinner', 'concurrentFinalizeBlocksExpiredCompensation',
        'createdRetryDoesNotLockParentAgainstReceiptProgress', 'legacyScannerCannotReleaseCounterIntent',
        'claimDoesNotChangePendingOriginal', 'frozenInventoryOwnerReleasesAfterCurrentSkuMappingChanges',
        'heldCancellationAndExpiryRecordCompleteProgress', 'heldCancellationWriteFailureRollsBackOwnerRows']],
      ['backend/shop-app', 'ai.neargo.shop.scenario.ManualCounterPrivacyIT', [
        'claimedOwnerReadsOriginalGuestOrder', 'unclaimedAndForeignUsersCannotRead', 'nullBuyerNeverWidensList']],
      ['backend/shop-app', 'ai.neargo.shop.scenario.ManualServiceFinancialIsolationIT', [
        'counterUserAndGuestRemainFinanciallyIneligible']]
    ];
  }
  if (stage === 'counter-fixture-fixes' && phase === 'green') {
    return [
      ['backend/shop-app', 'ai.neargo.shop.scenario.ManualCounterRecoveryIT', [
        'heldCancellationWriteFailureRollsBackOwnerRows', 'concurrentFinalizeBlocksExpiredCompensation']],
      ['backend/shop-app', 'ai.neargo.shop.scenario.ManualCounterPrivacyIT', ['nullBuyerNeverWidensList']]
    ];
  }
  if (stage === 'counter-authorization-ablation' && ['ablation', 'restored'].includes(phase)) {
    return [['backend/shop-app', 'ai.neargo.shop.scenario.ManualCounterPurchaseIT',
      ['currentAccountRevocationStopsCounterAfterMemberIO']]];
  }
  if (stage === 'counter-acceptance' && phase === 'green') {
    return [
      ['backend/shop-app', 'ai.neargo.shop.scenario.ManualCounterPurchaseIT', [
        'currentAccountRevocationStopsCounterAfterMemberIO', 'counterAcceptanceRequiresOriginalCashierNamespace',
        'identityIsNotPurchaseAcceptance', 'counterCannotUseConsumerConfirmation', 'changedQuoteCannotCreate',
        'sameCounterKeyCreatesOneOriginal', 'legacyNamespaceBackfillPreservesHistoricalBytesAndOldUniqueness',
        'guestConstraintsAndCounterKeysRejectMalformedRows']],
      ['backend/shop-app', 'ai.neargo.shop.scenario.ManualCounterIdentityIT', [
        'newPrepareAfterClaimRequiresCanonicalUser', 'claimBetweenQuoteAndAcceptanceKeepsGuestSnapshot',
        'claimAndCounterCreationPreserveOneOriginal', 'claimedGuestWithoutMembershipCanPrepareUserCounter',
        'claimedSelectorRejectsWrongTokenRegisteredAndInactiveAccount']],
      ['backend/shop-app', 'ai.neargo.shop.scenario.ManualCounterRecoveryIT', [
        'lostReserveReplyObservesOriginalRows', 'staleOriginalReleaseLostReplyRemainsCompensatingAndNeverReservesAgain',
        'finalizeAndCompensateHaveOneWinner', 'concurrentFinalizeBlocksExpiredCompensation',
        'createdRetryDoesNotLockParentAgainstReceiptProgress', 'legacyScannerCannotReleaseCounterIntent',
        'unknownReserveNeverBecomesCreated', 'claimDoesNotChangePendingOriginal',
        'frozenInventoryOwnerReleasesAfterCurrentSkuMappingChanges']],
      ['backend/shop-app', 'ai.neargo.shop.scenario.ManualCounterPrivacyIT', [
        'claimedOwnerReadsOriginalGuestOrder', 'unclaimedAndForeignUsersCannotRead', 'nullBuyerNeverWidensList']],
      ['backend/shop-app', 'ai.neargo.shop.scenario.ManualServiceFinancialIsolationIT', [
        'counterUserAndGuestRemainFinanciallyIneligible', 'fullPointsRefundTerminalStillRecoversPreparedOriginalStock',
        'fundedRefundRecoveryStillDispatchesAndReleasesOriginalOutsideCoreLocks']],
      ['backend/shop-app', 'ai.neargo.shop.scenario.ManualServiceAdmissionIT', [
        'currentBuyerPurchaseAndCashierReceiptCreateOneManualSource']]
    ];
  }
  throw new Error('invalid Task66 selection: unreleased stage');
}

/** Only intentional RED and the reviewed authorization ablation require own assertion failure. */
export function assertionRedPhase(phase) {
  return phase === 'red' || phase === 'ablation';
}

/** A setup error, extra testcase or skipped method is never an accepted behavioral result. */
export function inspectReport(xml, methods, red = false) {
  const tag = xml.match(/<testsuite\b[^>]*>/)?.[0];
  assert(tag, 'missing Task66 XML');
  const counts = Object.fromEntries([...tag.matchAll(/(tests|failures|errors|skipped)="(\d+)"/g)]
    .map(([, key, value]) => [key, Number(value)]));
  assert.equal(counts.tests, methods.length, 'exact Task66 method count required');
  assert(counts.tests > 0 && counts.errors === 0 && counts.skipped === 0, 'Task66 needs unskipped assertion evidence');
  const cases = [...xml.matchAll(/<testcase\b([^>]*?)(?:\/>|>([\s\S]*?)<\/testcase>)/g)];
  assert.equal(cases.length, methods.length, 'unexpected Task66 testcase');
  for (const method of methods) {
    const found = cases.filter(entry => entry[1].match(/\bname="([^"]+)"/)?.[1] === method);
    assert.equal(found.length, 1, 'missing exact Task66 method');
    if (red) assert(/<failure\b/.test(found[0][2] ?? ''), 'Task66 RED needs own assertion failure');
  }
  assert(red ? counts.failures === methods.length : counts.failures === 0, 'wrong Task66 outcome');
  return counts;
}

/** Relative aggregate paths resolve only the current canonical backend and beauty sources. */
export function aggregatePom(directory) {
  const modules = ['backend', 'backend-plugins/shop-industry-beauty'].map(path =>
    '<module>' + relative(realpathSync(directory), realpathSync(join(root, path))) + '</module>');
  return '<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>'
    + '<groupId>local.task66</groupId><artifactId>counter-reactor</artifactId><version>1</version>'
    + '<packaging>pom</packaging><modules>' + modules.join('') + '</modules></project>';
}

/** Review recovery observes the current V9 owner even for its decisive business RED. */
export function stageSchemaVersion(stage, phase) {
  if (stage === 'source-review-recovery') return 9;
  return ['source-binding-existing', 'source-matrix', 'source-matrix-fixture-fixes', 'source-upgrade',
    'source-final-baseline', 'source-ticket-ablation'].includes(stage)
    && ['green', 'ablation', 'restored'].includes(phase) ? 9 : 8;
}

/** Selected source and compiled test classes must both be reviewed, not only SQL and runner text. */
export function requiredPinPaths(selection, fixtures, stage = 'current-claims', schemaVersion = 8) {
  const embeddedV8 = ['reception-member', 'source-binding-existing', 'source-matrix', 'source-matrix-fixture-fixes', 'source-upgrade', 'source-final-baseline', 'source-ticket-ablation', 'source-review-recovery'].includes(stage);
  const owners = embeddedV8 ? [
    ['backend/shop-app', 'test', 'ai/neargo/shop/scenario/ManualSourceHostFixture'],
    ['backend/shop-app', 'test', 'ai/neargo/shop/scenario/CheckoutFlowFixture'],
    ['backend/shop-app', 'test', 'ai/neargo/shop/scenario/ManualServiceAdmissionIT'],
    ['backend/shop-app', 'test', 'ai/neargo/shop/scenario/BeautySourceCheckoutIT'],
    [beautyRoot + 'beauty-api', 'main', 'ai/neargo/shop/beauty/dto/OpenWorkOrderReq'],
    [beautyRoot + 'beauty-core', 'main', 'ai/neargo/shop/beauty/policy/ReceptionIdentity'],
    [beautyRoot + 'beauty-core', 'main', 'ai/neargo/shop/beauty/service/impl/WorkOrderCoordinator'],
    [beautyRoot + 'beauty-core', 'main', 'ai/neargo/shop/beauty/service/impl/WorkOrderServiceImpl'],
    [beautyRoot + 'beauty-core', 'main', 'ai/neargo/shop/beauty/service/impl/ServiceDefinitionServiceImpl'],
    [beautyRoot + 'beauty-core', 'main', 'ai/neargo/shop/beauty/config/BeautySchemaValidator'],
    ['backend/shop-core', 'main', 'ai/neargo/shop/member/port/MemberCustomerIdentityPortImpl'],
    ['backend/shop-core', 'main', 'ai/neargo/shop/member/port/MemberGuestRegistrationPortImpl'],
    ['backend/shop-core', 'main', 'ai/neargo/shop/member/service/impl/MemberCodeResolveServiceImpl'],
    ['backend/shop-merchant', 'main', 'ai/neargo/shop/merchant/port/MerchantOperatorAccessPortImpl'],
    ['backend/shop-core', 'main', 'ai/neargo/shop/product/port/ServiceCatalogFactsPortImpl'],
    ...(['source-binding-existing', 'source-matrix', 'source-matrix-fixture-fixes', 'source-upgrade', 'source-final-baseline', 'source-ticket-ablation', 'source-review-recovery'].includes(stage) ? [
      ['backend/shop-base', 'main', 'ai/neargo/shop/spi/trade/ManualServiceAdmissionPort'],
      ['backend/shop-core', 'main', 'ai/neargo/shop/trade/port/ManualServiceAdmissionPortImpl'],
      ['backend/shop-core', 'main', 'ai/neargo/shop/trade/service/impl/ManualServiceAdmissionServiceImpl'],
      ['backend/shop-core', 'main', 'ai/neargo/shop/trade/service/impl/CheckoutServiceImpl'],
      ['backend/shop-core', 'main', 'ai/neargo/shop/trade/service/impl/OrderServiceImpl'],
      ['backend/shop-core', 'main', 'ai/neargo/shop/trade/order/service/create/impl/CounterOrderPlacementServiceImpl'],
      ['backend/shop-core', 'main', 'ai/neargo/shop/product/service/impl/StockDispositionServiceImpl'],
      ['backend/shop-core', 'main', 'ai/neargo/shop/product/port/StockPortImpl'],
      ['backend/shop-app', 'main', 'ai/neargo/shop/invbridge/port/OriginalStockDispositionPort'],
      [beautyRoot + 'beauty-api', 'main', 'ai/neargo/shop/beauty/dto/SubmitServicesReq'],
      [beautyRoot + 'beauty-core', 'main', 'ai/neargo/shop/beauty/service/impl/ServiceSubmissionServiceImpl'],
      [beautyRoot + 'beauty-core', 'main', 'ai/neargo/shop/beauty/service/impl/ServiceSubmissionQuery'],
      [beautyRoot + 'beauty-core', 'main', 'ai/neargo/shop/beauty/service/impl/ServiceSourceObservation'],
      [beautyRoot + 'beauty-core', 'main', 'ai/neargo/shop/beauty/service/impl/ServiceSourceBinding'],
      [beautyRoot + 'beauty-core', 'main', 'ai/neargo/shop/beauty/mapper/BeautyMappers']
    ] : [])
    , ...(['source-matrix', 'source-matrix-fixture-fixes', 'source-upgrade', 'source-final-baseline', 'source-ticket-ablation', 'source-review-recovery'].includes(stage) ? [
      ['backend/shop-app', 'main', 'ai/neargo/shop/invbridge/port/InventoryStockPort'],
      ['backend/shop-inventory', 'main', 'ai/neargo/shop/inventory/service/impl/InventoryAclServiceImpl'],
      ['backend/shop-inventory', 'main', 'ai/neargo/shop/inventory/service/impl/StockCountServiceImpl'],
      ['backend/shop-inventory', 'main', 'ai/neargo/shop/inventory/service/impl/LocationServiceImpl'],
      ['backend/shop-inventory', 'main', 'ai/neargo/shop/inventory/service/impl/ReservationServiceImpl'],
      ['backend/shop-inventory', 'main', 'ai/neargo/shop/inventory/service/impl/ReservationDispositionServiceImpl']
    ] : [])
  ].flatMap(([module, kind, name]) => [module + '/src/' + kind + '/java/' + name + '.java',
    module + '/target/' + (kind === 'test' ? 'test-classes/' : 'classes/') + name + '.class']) : [];
  const v9 = schemaVersion === 9 ? [
    beautyRoot + 'beauty-core/src/main/resources/db/industry/beauty/V9__beauty_manual_source_observation.sql',
    'docs/technical/design/schema/beauty-manual-source-observation-schema.json',
    'scripts/gen-beauty-manual-source-observation-schema.mjs',
    'scripts/tests/beauty-manual-source-observation-schema.test.mjs',
    ...JSON.parse(readFileSync(join(root, workspace + '/task-67-release-inventory.json'), 'utf8')).modify
      .concat(beautyRoot + 'beauty-api/src/main/java/ai/neargo/shop/beauty/dto/ManualReceiptSummaryVO.java')
      .flatMap(path => [path, path.replace('/src/main/java/', '/target/classes/').replace(/\.java$/, '.class')])
  ] : [];
  return [fileURLToPath(import.meta.url).slice(root.length + 1), ...fixtures, ...migrations, ...v9,
    ...(embeddedV8 ? [counterMigration, legacyCheckoutFixture, ...beautyMigrations, ...owners,
      'scripts/tests/manual-counter-source-runner.test.mjs'] : []),
    ...(stage === 'source-review-recovery' ? [
      beautyRoot + 'beauty-core/src/test/java/ai/neargo/shop/beauty/config/BeautySchemaValidatorTest.java',
      beautyRoot + 'beauty-core/target/test-classes/ai/neargo/shop/beauty/config/BeautySchemaValidatorTest.class'
    ] : []),
    ...(stage === 'source-upgrade' ? [
      workspace + '/task-67-evidence/pre-v9-runtime/manifest.json',
      workspace + '/task-67-evidence/pre-native/source-upgrade-runtime.json',
      'backend/shop-app/src/test/java/ai/neargo/shop/scenario/ManualSourceV8Driver.java',
      'backend/shop-app/target/test-classes/ai/neargo/shop/scenario/ManualSourceV8ProtocolTest.class',
      ...['', '$Host', '$DriverConfiguration', '$DriverConfiguration$1'].map(suffix =>
        'backend/shop-app/target/test-classes/ai/neargo/shop/scenario/ManualSourceV8Driver' + suffix + '.class')
    ] : []),
    ...(['source-binding-existing', 'source-matrix', 'source-matrix-fixture-fixes', 'source-upgrade', 'source-final-baseline', 'source-ticket-ablation', 'source-review-recovery'].includes(stage) ? [
      'backend/shop-app/target/test-classes/ai/neargo/shop/scenario/ManualSourceHostFixture$ManualOriginal.class',
      ...['ManualSourceFacts', 'ManualSourceLine', 'CustomerParty', 'CounterCheckoutQuote', 'CounterCustomerRef']
        .map(name => 'backend/shop-base/target/classes/ai/neargo/shop/spi/trade/ManualServiceAdmissionPort$'
          + name + '.class')
    ] : []),
    ...(['counter-purchase', 'counter-bridge', 'counter-cashier', 'counter-acceptance', 'counter-recovery-diagnostic', 'counter-recovery-fixes', 'counter-fixture-fixes', 'counter-authorization-ablation', 'counter-read-authorization'].includes(stage) ? [counterMigration, legacyCheckoutFixture] : []),
    ...selection.flatMap(([module, cls]) => [
      module + '/src/test/java/' + cls.replaceAll('.', '/') + '.java',
      module + '/target/test-classes/' + cls.replaceAll('.', '/') + '.class'
    ])];
}

/** Only the actual embedded api/core/web roots may be exchanged for immutable historical roots. */
export function historicalClasspath(current) {
  const snapshot = join(root, workspace, 'task-67-evidence/pre-v9-runtime');
  const expected = new Set(['api', 'core', 'web'].map(name =>
    join(root, beautyRoot, 'beauty-' + name, 'target/classes')));
  const seen = new Set();
  const result = current.map(raw => {
    assert(typeof raw === 'string' && raw.length > 0, 'implicit classpath working directory is not reviewed');
    const path = resolve(raw);
    assert.notEqual(path, root, 'workspace root is not an explicit child classpath artifact');
    if (expected.has(path)) {
      assert(!seen.has(path), 'duplicate beauty runtime root');
      seen.add(path);
      return join(snapshot, relative(root, path));
    }
    assert(!path.includes('/backend-plugins/shop-industry-beauty/')
      && !/(?:^|\/)beauty-[^/]+\.jar$/.test(path)
      && !/(?:^|\/)beauty-(api|core|web|channel|server)\/target\//.test(path),
    'unreviewed beauty runtime root or jar');
    return path;
  });
  assert.equal(seen.size, 3, 'exactly three embedded beauty runtime roots required');
  return result;
}

/** Pin the complete finite runtime closure, including dependency jars and every directory resource. */
export function runtimeClasspathFiles(classpath) {
  const files = new Set();
  const visit = path => {
    if (statSync(path).isFile()) { files.add(realpathSync(path)); return; }
    for (const entry of readdirSync(path, {withFileTypes: true})) {
      assert(!entry.isSymbolicLink(), 'classpath directory symlink is not reviewed');
      visit(join(path, entry.name));
    }
  };
  for (const path of classpath) visit(path);
  return [...files].sort();
}

function verifyUpgradeRuntime(gate) {
  const path = workspace + '/task-67-evidence/pre-native/source-upgrade-runtime.json';
  assert.equal(gate.runtimeManifestPath, path);
  const bytes = readFileSync(join(root, path));
  assert.equal(hash(bytes), gate.runtimeManifestSha, 'upgrade runtime manifest changed');
  const runtime = JSON.parse(bytes);
  const snapshot = join(root, workspace, 'task-67-evidence/pre-v9-runtime');
  const frozen = readFileSync(join(snapshot, 'manifest.json'));
  assert.equal(hash(frozen), '21052bb9d4083030ea457ad6f9ad419931a8b314d9abed4ff6e22e7829e68e23');
  for (const pin of JSON.parse(frozen).files) {
    const value = readFileSync(join(snapshot, pin.path));
    assert.equal(value.length, pin.bytes); assert.equal(hash(value), pin.sha256, 'historical snapshot changed');
  }
  assert.deepEqual(runtime.historicalClasspath, historicalClasspath(runtime.currentClasspath));
  const closure = runtimeClasspathFiles([...runtime.currentClasspath, ...runtime.historicalClasspath, runtime.javaExecutable]);
  assert.deepEqual(runtime.files.map(pin => pin.path), closure, 'runtime file closure changed');
  for (const pin of runtime.files) {
    const value = readFileSync(pin.path);
    assert.equal(value.length, pin.bytes, 'runtime bytes changed: ' + pin.path);
    assert.equal(hash(value), pin.sha256, 'runtime hash changed: ' + pin.path);
  }
  assert.deepEqual(runtime.deadlinesSeconds, {oldReady: 90, currentChild: 210, resumedOld: 30, terminate: 5, kill: 5});
  return path;
}

/** Verify exact files before any lock, daemon, SQL or VM activity. */
function approved(slice, phase) {
  const gate = JSON.parse(readFileSync(join(root, nativeGate(slice)), 'utf8'));
  assert.equal(gate.approved, true, 'Task66 requires root native GO');
  assert((gate.allowedPhases ?? []).includes(phase), 'Task66 phase is not root approved');
  assert.equal(gate.slice, slice, 'Task66 slice differs from gate');
  assert.match(gate.uuid ?? '', /^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/);
  const selection = phaseSelection(slice, phase, gate.stage);
  if (slice === 'm2b') {
    assert(['reception-member', 'source-binding-existing', 'source-matrix', 'source-matrix-fixture-fixes', 'source-upgrade', 'source-final-baseline', 'source-ticket-ablation', 'source-review-recovery'].includes(gate.stage), 'Task67 stage is not released');
    const expectedVersion = stageSchemaVersion(gate.stage, phase);
    assert.equal(gate.beautySchemaVersion, expectedVersion, 'Task67 exact staged schema version differs');
    assert.equal(gate.executesV9 ?? false, expectedVersion === 9, 'Task67 V9 execution must be explicit');
    assert.deepEqual(gate.schemaTopology, gate.stage === 'source-upgrade'
      ? ['embedded-primary', 'inventory', 'isolated-upgrade-primary'] : ['embedded-primary', 'inventory']);
    if (gate.stage === 'source-upgrade') verifyUpgradeRuntime(gate);
    if (expectedVersion === 8) assert(!existsSync(join(root, beautyRoot + 'beauty-core/src/main/resources/db/industry/beauty/'
      + 'V9__beauty_manual_source_observation.sql')), 'Task67 pre-manual V8 gate cannot execute V9');
  }
  assert.deepEqual(gate.selection, selection, 'Task66 selected methods differ from gate');
  assert(Array.isArray(gate.files) && gate.files.length > 0, 'Task66 missing root pins');
  for (const pin of gate.files) {
    const bytes = readFileSync(join(root, pin.path));
    assert.equal(bytes.length, pin.bytes, 'Task66 bytes changed: ' + pin.path);
    assert.equal(hash(bytes), pin.sha256, 'Task66 hash changed: ' + pin.path);
  }
  const fixtures = ['platform-v538.sql', 'inventory-v13.sql'].map(file =>
    workspace + '/task-66-evidence/pre-native/' + file);
  for (const path of requiredPinPaths(selection, fixtures, gate.stage, gate.beautySchemaVersion)) {
    assert(gate.files.some(pin => pin.path === path), 'Task66 missing actual SQL/runner pin: ' + path);
  }
  assert.equal(gate.executesV542, ['reception-member', 'source-binding-existing', 'source-matrix', 'source-matrix-fixture-fixes', 'source-upgrade', 'source-final-baseline', 'source-ticket-ablation', 'source-review-recovery', 'counter-purchase', 'counter-bridge', 'counter-cashier', 'counter-acceptance', 'counter-recovery-diagnostic', 'counter-recovery-fixes', 'counter-fixture-fixes', 'counter-authorization-ablation', 'counter-read-authorization'].includes(gate.stage), 'Task66 actual DDL set differs from stage');
  return {gate, selection, fixtures};
}

/** Secondary cleanup survives a parent Surefire failure; signals only UUID-matching driver JVMs. */
async function stopUpgradeChildren(protocol, evidence, safe) {
  const pidFile = join(protocol, 'child-pids.json');
  const pids = existsSync(pidFile) ? JSON.parse(readFileSync(pidFile, 'utf8')) : {};
  const absent = {}, failures = [];
  const alive = pid => {
    try { process.kill(pid, 0); return true; }
    catch (failure) { if (failure.code === 'ESRCH') return false; throw failure; }
  };
  const owned = pid => {
    assert(Number.isSafeInteger(pid) && pid > 1, 'invalid owned child PID');
    if (!alive(pid)) return false;
    try {
      const command = execFileSync('ps', ['-p', String(pid), '-o', 'command='],
        {encoding: 'utf8', timeout: 3000, stdio: ['ignore', 'pipe', 'ignore']});
      return command.includes('ai.neargo.shop.scenario.ManualSourceV8Driver ')
        && command.includes(protocol);
    } catch (failure) {
      if (!alive(pid)) return false;
      throw new Error('Cannot inspect still-live owned child PID ' + pid, {cause: failure});
    }
  };
  for (const [name, pid] of Object.entries(pids)) {
    assert(['old', 'current'].includes(name));
    try {
      if (owned(pid)) {
        try { process.kill(pid, 'SIGTERM'); } catch (failure) { if (failure.code !== 'ESRCH') throw failure; }
        let until = Date.now() + 5000;
        while (owned(pid) && Date.now() < until) await pause(50);
        if (owned(pid)) {
          try { process.kill(pid, 'SIGKILL'); } catch (failure) { if (failure.code !== 'ESRCH') throw failure; }
          until = Date.now() + 5000;
          while (owned(pid) && Date.now() < until) await pause(50);
        }
      }
      absent[name + 'ChildAbsent'] = !owned(pid);
    } catch (failure) {
      absent[name + 'ChildAbsent'] = false;
      failures.push(failure);
    }
  }
  if (existsSync(protocol)) for (const entry of readdirSync(protocol, {withFileTypes: true})) {
    if (entry.isFile()) writeFileSync(join(evidence, entry.name), safe(readFileSync(join(protocol, entry.name), 'utf8')));
  }
  writeFileSync(join(evidence, 'runner-child-cleanup.json'), JSON.stringify({ ...absent,
    inspectionErrors: failures.map(failure => safe(failure.message))}, null, 2));
  assert(failures.length === 0 && Object.values(absent).every(Boolean), 'owned upgrade child cleanup incomplete');
}

async function run(slice, phase) {
  const {gate, selection, fixtures} = approved(slice, phase);
  const uuid = gate.uuid;
  const evidence = join(root, workspace, slice === 'm2b' ? 'task-67-evidence' : 'task-66-evidence',
    phase + '-' + uuid);
  const embeddedV8 = slice === 'm2b' && ['reception-member', 'source-binding-existing', 'source-matrix', 'source-matrix-fixture-fixes', 'source-upgrade', 'source-final-baseline', 'source-ticket-ablation', 'source-review-recovery'].includes(gate.stage);
  assert(!existsSync(evidence), 'Task66 UUID/phase evidence cannot be overwritten');
  mkdirSync(evidence, {recursive: true});
  writeFileSync(join(evidence, 'root-gate.json'), JSON.stringify(gate, null, 2));
  const password = randomBytes(24).toString('base64url');
  const environment = {...process.env, JAVA_HOME: '/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home',
    MYSQL_ROOT_PASSWORD: password, MYSQL_PWD: password};
  const safe = value => String(value).replaceAll(password, '[REDACTED]');
  const name = (embeddedV8 ? 'codex-manual-source-' : 'codex-counter-') + uuid;
  const temporary = (embeddedV8 ? '/tmp/codex-manual-source-' : '/tmp/codex-counter-') + uuid;
  const lock = '/tmp/codex-beauty-bm04-native.lock';
  let locked = false, owned = false, activeMaven, interrupted = false;
  const onSignal = () => { interrupted = true; activeMaven?.kill('SIGTERM'); };
  process.on('SIGINT', onSignal); process.on('SIGTERM', onSignal);
  const docker = (args, timeout = 45000) => {
    try {
      return execFileSync('docker', ['--context', 'colima-hxkey-tests', ...args],
        {encoding: 'utf8', env: environment, timeout, stdio: ['ignore', 'pipe', 'pipe']}).trim();
    } catch (failure) { throw new Error(safe(failure.stderr ?? failure.message)); }
  };
  try {
    mkdirSync(lock); locked = true;
    const limits = args => execFileSync('limactl', ['shell', '--instance', 'colima-hxkey-tests', ...args],
      {encoding: 'utf8', env: {...environment, LIMA_HOME: '/Users/robin/.colima/_lima'}, timeout: 15000});
    const memory = Number(limits(['cat', '/proc/meminfo']).match(/^MemAvailable:\s+(\d+)\s+kB$/m)?.[1]);
    const disk = Number(limits(['df', '-Pk', '/']).trim().split('\n').at(-1).trim().split(/\s+/)[3]);
    assert(memory >= 524288 && disk >= 1048576, 'Task66 requires 512MiB memory and 1GiB free disk');
    docker(['image', 'inspect', 'mysql:9.7.2']);
    mkdirSync(join(temporary, 'reactor'), {recursive: true});
    const pom = aggregatePom(join(temporary, 'reactor'));
    writeFileSync(join(temporary, 'reactor/pom.xml'), pom);
    writeFileSync(join(evidence, 'aggregate-pom.xml'), pom);
    writeFileSync(join(evidence, 'resources.json'), JSON.stringify({uuid, name, memoryKiB: memory,
      diskKiB: disk, schemas: gate.stage === 'source-upgrade' ? 3 : 2, schemaTopology: embeddedV8 ? gate.schemaTopology : ['platform', 'inventory'],
      beautySchemaVersion: embeddedV8 ? gate.beautySchemaVersion : null, beautyInitialization: embeddedV8 ? 'actual-current-validator-migrate' : null,
      reducedStructuralFixture: true, executesV542: gate.executesV542}, null, 2));
    for (const [module, cls] of selection) {
      rmSync(join(root, module, 'target/surefire-reports/TEST-' + cls + '.xml'), {force: true});
    }
    owned = true;
    docker(['run', '--rm', '--pull', 'never', '--detach', '--name', name,
      '--label', 'codex.task66.uuid=' + uuid, '--publish', '127.0.0.1::3306', '--memory', '768m',
      '--pids-limit', '256', '--cpus', '1', '--env', 'MYSQL_ROOT_PASSWORD', '--env', 'MYSQL_ROOT_HOST=%',
      'mysql:9.7.2', '--datadir=/var/lib/counter-test', '--innodb-buffer-pool-size=64M', '--performance-schema=OFF']);
    let ready = false;
    const readinessDeadline = Date.now() + 90000;
    while (Date.now() < readinessDeadline) {
      assert(!interrupted, 'Task66 interrupted');
      try {
        docker(['exec', '--env', 'MYSQL_PWD', name, 'mysql', '--protocol=TCP', '-h127.0.0.1', '-uroot', '-e', 'SELECT 1'], Math.min(3000, Math.max(1, readinessDeadline - Date.now())));
        ready = true; break;
      } catch { await pause(Math.min(300, Math.max(0, readinessDeadline - Date.now()))); }
    }
    assert(ready, 'Task66 readiness timed out');
    const address = docker(['port', name, '3306/tcp']);
    assert.match(address, /^127\.0\.0\.1:[0-9]+$/);
    const platform = 'counter_' + uuid.replaceAll('-', ''), inventory = 'counter_inv_' + uuid.replaceAll('-', '');
    const upgrade = 'manual_upgrade_' + uuid.replaceAll('-', '');
    const mysql = (input, schema) => {
      try {
        return execFileSync('docker', ['--context', 'colima-hxkey-tests', 'exec', '-i', '--env', 'MYSQL_PWD', name,
          'mysql', '--protocol=TCP', '-h127.0.0.1', '-uroot', ...(schema ? [schema] : [])],
        {encoding: 'utf8', input, env: environment, timeout: 45000, stdio: ['pipe', 'pipe', 'pipe']});
      } catch (failure) { throw new Error('Task66 SQL setup: ' + safe(failure.stderr ?? failure.message)); }
    };
    mysql('CREATE DATABASE ' + platform + ' CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;'
      + 'CREATE DATABASE ' + inventory + ' CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;');
    mysql(readFileSync(join(root, fixtures[0]), 'utf8'), platform);
    mysql(readFileSync(join(root, fixtures[1]), 'utf8'), inventory);
    for (const path of migrations.slice(0, 3)) mysql(readFileSync(join(root, path), 'utf8'), platform);
    mysql(readFileSync(join(root, migrations[3]), 'utf8'), inventory);
    if (gate.executesV542) {
      mysql(readFileSync(join(root, legacyCheckoutFixture), 'utf8'), platform);
      mysql(readFileSync(join(root, counterMigration), 'utf8'), platform);
    }
    if (gate.stage === 'source-upgrade') {
      mysql('CREATE DATABASE ' + upgrade + ' CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;');
      mysql(readFileSync(join(root, fixtures[0]), 'utf8'), upgrade);
      for (const path of migrations.slice(0, 3)) mysql(readFileSync(join(root, path), 'utf8'), upgrade);
      mysql(readFileSync(join(root, legacyCheckoutFixture), 'utf8'), upgrade);
      mysql(readFileSync(join(root, counterMigration), 'utf8'), upgrade);
    }
    const jdbc = schema => 'jdbc:mysql://' + address + '/' + schema
      + '?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC';
    Object.assign(environment, {CHECKOUT_NATIVE_MYSQL_URL: jdbc(platform), CHECKOUT_NATIVE_MYSQL_USER: 'root',
      CHECKOUT_NATIVE_MYSQL_PASSWORD: password, MANUAL_INVENTORY_MYSQL_URL: jdbc(inventory),
      MANUAL_SERVICE_TEST_PASSWORD: password, SPRING_DATASOURCE_URL: jdbc(platform),
      SPRING_DATASOURCE_USERNAME: 'root', SPRING_DATASOURCE_PASSWORD: password,
      SPRING_DATASOURCE_DRIVER_CLASS_NAME: 'com.mysql.cj.jdbc.Driver', SPRING_SQL_INIT_MODE: 'never'});
    if (gate.stage === 'source-upgrade') {
      const protocol = join(temporary, 'upgrade-protocol');
      mkdirSync(protocol);
      Object.assign(environment, {MANUAL_UPGRADE_MYSQL_URL: jdbc(upgrade),
        MANUAL_UPGRADE_PROTOCOL: realpathSync(protocol), MANUAL_UPGRADE_EVIDENCE: evidence,
        MANUAL_UPGRADE_CURRENT_ROOT: root,
        MANUAL_UPGRADE_SNAPSHOT: join(root, workspace, 'task-67-evidence/pre-v9-runtime'),
        MANUAL_UPGRADE_RUNTIME_MANIFEST: join(root, gate.runtimeManifestPath)});
    }
    const selected = selection.map(([, cls, methods]) => cls.split('.').at(-1) + '#' + methods.join('+')).join(',');
    const command = ['-o', '-Dmaven.repo.local=/tmp/codex-beauty-bm01-m2', '-f', join(temporary, 'reactor/pom.xml'),
      ...(embeddedV8 ? ['-Pbeauty-embedded'] : []),
      ...(gate.stage === 'source-upgrade' ? ['-Dspring-boot.build-info.skip=true'] : []),
      '-pl', embeddedV8 ? ':shop-app,:shop-inventory,:beauty-channel,:beauty-server'
        : ':shop-app,:shop-inventory,:beauty-channel', '-am', 'test', '-Dtest=' + selected,
      '-Dsurefire.failIfNoSpecifiedTests=false', '-Dcheckout.test.native=true',
      ...(embeddedV8 ? ['-Dbeauty.manual.source.test.native=true'] : [])];
    activeMaven = spawn('mvn', command, {cwd: root, env: environment, stdio: ['ignore', 'pipe', 'pipe']});
    const chunks = [];
    for (const stream of [activeMaven.stdout, activeMaven.stderr]) stream.on('data', bytes => chunks.push(bytes));
    let hard;
    const timer = setTimeout(() => { activeMaven?.kill('SIGTERM');
      hard = setTimeout(() => activeMaven?.kill('SIGKILL'), 5000); }, 600000);
    let status;
    try { status = await new Promise((resolve, reject) => {
      activeMaven.once('error', reject); activeMaven.once('exit', resolve);
    }); } finally {
      clearTimeout(timer); clearTimeout(hard); activeMaven = undefined;
      writeFileSync(join(evidence, phase + '.log'), safe(Buffer.concat(chunks)));
    }
    writeFileSync(join(evidence, 'command.json'), JSON.stringify({command: 'mvn', args: command, status}, null, 2));
    assert(!interrupted, 'Task66 interrupted');
    // Preserve every selected fresh XML before an earlier class's verdict can stop inspection.
    const reports = selection.map(([module, cls, methods]) => {
      const path = join(root, module, 'target/surefire-reports/TEST-' + cls + '.xml');
      assert(existsSync(path), 'Task66 missing fresh XML: ' + cls);
      const xml = safe(readFileSync(path, 'utf8'));
      writeFileSync(join(evidence, cls + '.xml'), xml);
      return {cls, methods, xml};
    });
    const summary = reports.map(({cls, methods, xml}) =>
      ({class: cls, ...inspectReport(xml, methods, assertionRedPhase(phase))}));
    assert(assertionRedPhase(phase) ? status !== 0 : status === 0, 'Task66 wrong Maven outcome');
    writeFileSync(join(evidence, 'summary.json'), JSON.stringify({phase, summary, status}, null, 2));
    console.log('Task66 ' + phase + ' evidence: ' + evidence);
  } catch (failure) {
    writeFileSync(join(evidence, 'attempt-failure.json'), JSON.stringify({phase, message: safe(failure.message)}, null, 2));
    throw failure;
  } finally {
    try {
      if (gate.stage === 'source-upgrade') {
        await stopUpgradeChildren(join(temporary, 'upgrade-protocol'), evidence, safe);
      }
    } finally {
    try {
      if (owned) {
        const present = docker(['ps', '-aq', '--filter', 'name=^/' + name + '$']);
        if (present) {
          assert.equal(docker(['inspect', '--format', '{{index .Config.Labels "codex.task66.uuid"}}', name]), uuid);
          docker(['rm', '--force', name]);
        }
        assert.equal(docker(['ps', '-aq', '--filter', 'name=^/' + name + '$']), '',
          'Task66 owned container must be absent after cleanup');
      }
    } finally {
      let temporaryAbsent = !existsSync(temporary);
      if (!temporaryAbsent) {
        assert.equal(realpathSync(temporary),
          (embeddedV8 ? '/private/tmp/codex-manual-source-' : '/private/tmp/codex-counter-') + uuid,
          'Task66 cleanup must target only this UUID temporary reactor');
        rmSync(temporary, {recursive: true, force: false});
        temporaryAbsent = !existsSync(temporary);
      }
      if (locked) rmdirSync(lock);
      writeFileSync(join(evidence, 'cleanup.json'), JSON.stringify({uuid, name,
        ownedContainerAbsent: !owned || docker(['ps', '-aq', '--filter', 'name=^/' + name + '$']) === '',
        ownedTemporaryAbsent: temporaryAbsent, ownedLockAbsent: !locked || !existsSync(lock)}));
      assert(temporaryAbsent && (!locked || !existsSync(lock)), 'Task66 owned cleanup incomplete');
      process.off('SIGINT', onSignal); process.off('SIGTERM', onSignal);
    }
    }
  }
}

// Task68 has separate, finite planning boundaries. Materialization is never a zero-test green.
const m2cArchiveManifest = workspace + '/task-68-prechange-serialization/manifest.json';
const oldBaseline = '2c0708f752d747d88a9eda7478326a964d1984fa';
const m2cSql = Object.freeze({
  platform: workspace + '/task-66-evidence/pre-native/platform-v538.sql',
  inventory: workspace + '/task-66-evidence/pre-native/inventory-v13.sql',
  v539: migrations[0], v540: migrations[1], v541: migrations[2],
  preV542: legacyCheckoutFixture, v542: counterMigration,
  v543: 'backend/shop-app/src/main/resources/db/migration/V603__counter_campaign_owner_fence.sql',
  invV14: migrations[3]
});
const m2cTables = Object.freeze(['mkt_campaign', 'pmt_activity', 'pmt_activity_version',
  'pmt_activity_goods', 'pmt_activity_rule', 'pmt_enrollment', 'pmt_enrollment_goods']);
const m2cBindings = Object.freeze([...m2cTables.map(table => [table, 'tenant_no']),
  ...['mkt_campaign', 'pmt_activity', 'pmt_enrollment'].map(table => [table, 'entity_no'])]);
const toolCache = '/private/tmp/codex-beauty-bm01-m2';
const toolCoordinates = Object.freeze([
  ['org.junit.platform:junit-platform-launcher:6.0.3', 'org/junit/platform/junit-platform-launcher/6.0.3/junit-platform-launcher-6.0.3.jar'],
  ['org.junit.platform:junit-platform-engine:6.0.3', 'org/junit/platform/junit-platform-engine/6.0.3/junit-platform-engine-6.0.3.jar'],
  ['org.junit.platform:junit-platform-commons:6.0.3', 'org/junit/platform/junit-platform-commons/6.0.3/junit-platform-commons-6.0.3.jar'],
  ['org.opentest4j:opentest4j:1.3.0', 'org/opentest4j/opentest4j/1.3.0/opentest4j-1.3.0.jar'],
  ['org.apiguardian:apiguardian-api:1.1.2', 'org/apiguardian/apiguardian-api/1.1.2/apiguardian-api-1.1.2.jar'],
  ['org.jspecify:jspecify:1.0.0', 'org/jspecify/jspecify/1.0.0/jspecify-1.0.0.jar']
]);
const m2cJvmProperties = Object.freeze([
  '-Dshop.inventory.enabled=false', '-Dshop.inventory.stock-authority=PLATFORM',
  '-Dspring.sql.init.mode=never', '-Dspring.flyway.enabled=false',
  '-Dcheckout.test.native=true', '-Dspring.profiles.active=test',
  '-Dserver.address=127.0.0.1', '-Dserver.port=0',
  '-Dbeauty.enabled=false', '-Dfood.enabled=false'
]);
const uuid4 = value => /^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/.test(value ?? '');
const ownedRelative = value => typeof value === 'string' && value.length > 0 && !value.startsWith('/')
  && !value.split('/').includes('..') && !value.split('/').includes('.')
  && !value.includes('\\') && !value.includes('\0');
const beneath = (path, parent) => path === parent || path.startsWith(parent + sep);
const corePin = ({path, bytes, sha256}) => ({path, bytes, sha256});
const pinnedFile = path => {
  const bytes = readFileSync(path);
  return {path: realpathSync(path), bytes: bytes.length, sha256: hash(bytes)};
};
const verifyFilePin = (pin, roots) => {
  assert(pin && typeof pin.path === 'string' && pin.path.startsWith('/'), 'absolute input pin required');
  assert(Number.isSafeInteger(pin.bytes) && pin.bytes >= 0 && /^[a-f0-9]{64}$/.test(pin.sha256 ?? ''),
    'input byte/hash pin required');
  const path = realpathSync(pin.path);
  assert.equal(path, pin.path, 'symlink input is not accepted');
  assert(roots.some(parent => beneath(path, parent)), 'input escaped declared roots: ' + path);
  assert(statSync(path).isFile(), 'input pin must be a file');
  const actual = pinnedFile(path);
  assert.equal(actual.bytes, pin.bytes, 'input byte drift: ' + path);
  assert.equal(actual.sha256, pin.sha256, 'input hash drift: ' + path);
  return actual;
};
const sourceAndClass = (module, kind, name) => [
  module + '/src/' + kind + '/java/' + name.replaceAll('.', '/') + '.java',
  module + '/target/' + (kind === 'test' ? 'test-classes' : 'classes')
    + '/' + name.replaceAll('.', '/') + '.class'
];
const m2cOwners = Object.freeze([
  ['backend/shop-app', 'main', 'ai.neargo.shop.ShopApplication'],
  ['backend/shop-app', 'test', 'ai.neargo.shop.scenario.CheckoutFlowFixture'],
  ['backend/shop-app', 'test', 'ai.neargo.shop.scenario.ManualServiceAdmissionIT'],
  ['backend/shop-app', 'test', 'ai.neargo.shop.scenario.ManualCounterDiscountFixture'],
  ['backend/shop-core', 'main', 'ai.neargo.shop.marketing.campaign.CounterCampaignFence'],
  ['backend/shop-core', 'main', 'ai.neargo.shop.marketing.campaign.CounterCampaignRules'],
  ['backend/shop-core', 'main', 'ai.neargo.shop.marketing.campaign.mapper.CounterCampaignFenceMapper'],
  ['backend/shop-core', 'main', 'ai.neargo.shop.trade.service.impl.OrderServiceImpl'],
  ['backend/shop-core', 'main', 'ai.neargo.shop.trade.service.impl.CheckoutServiceImpl'],
  ['backend/shop-core', 'main', 'ai.neargo.shop.trade.service.impl.ManualServiceAdmissionServiceImpl'],
  ['backend/shop-core', 'main', 'ai.neargo.shop.trade.port.ManualServiceAdmissionPortImpl'],
  ['backend/shop-core', 'main', 'ai.neargo.shop.trade.order.service.create.impl.CounterOrderPlacementServiceImpl'],
  ['backend/shop-core', 'main', 'ai.neargo.shop.product.port.StockPortImpl'],
  ['backend/shop-app', 'main', 'ai.neargo.shop.invbridge.port.OriginalStockDispositionPort'],
  ['backend/shop-base', 'main', 'ai.neargo.shop.spi.marketing.CampaignPort'],
  ['backend/shop-base', 'main', 'ai.neargo.shop.spi.marketing.CounterCampaignContract']
]);
const m2cB2Sources = Object.freeze([
  'backend/shop-app/src/main/java/ai/neargo/shop/portal/port/CampaignPortRouter.java',
  'backend/shop-core/src/main/java/ai/neargo/shop/marketing/port/CampaignPortImpl.java',
  'backend/shop-core/src/main/java/ai/neargo/shop/promotion/service/ActivityPricingService.java',
  'backend/shop-core/src/main/java/ai/neargo/shop/promotion/service/impl/ActivityPricingServiceImpl.java',
  'backend/shop-core/src/main/java/ai/neargo/shop/product/port/GoodsQueryPortImpl.java',
  'backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/CheckoutServiceImpl.java',
  'backend/shop-core/src/main/java/ai/neargo/shop/trade/service/CheckoutOrderWriter.java',
  'backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/ManualServiceAdmissionServiceImpl.java',
  'backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/OrderServiceImpl.java',
  'backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/create/CheckoutQuoteGuard.java',
  'backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/create/impl/CounterOrderPlacementServiceImpl.java',
  'backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/create/impl/OrderCreationRecoveryServiceImpl.java',
  'backend/shop-app/src/test/java/ai/neargo/shop/scenario/ManualCounterDiscountIT.java',
  'backend/shop-app/src/test/java/ai/neargo/shop/scenario/ManualCounterDiscountFixture.java'
]);

export function materializationPlan(slice, phase, stage) {
  assert.equal(slice, 'm2c'); assert.equal(phase, 'prepare');
  assert(['materialize-runtime', 'materialize-goldens'].includes(stage), 'unreleased Task68 preparation stage');
  return {stage, action: m2cStages[stage][1], selection: null,
    derivedOutputs: outputSlots(stage).map(slot => slot.path)};
}

export function requiredM2cPinPaths(selection, stage) {
  assert(m2cStages[stage], 'unreleased Task68 stage');
  assert.deepEqual(selection, stage.startsWith('materialize-') ? null : phaseSelection('m2c', 'green', stage));
  const selected = selection ?? [];
  const paths = [
    'scripts/verify-manual-counter-source.mjs', 'scripts/tests/manual-counter-source-runner.test.mjs',
    workspace + '/task-68c-classpath-ruling.md',
    ...Object.values(m2cSql),
    ...sourceAndClass('backend/shop-app', 'test', 'ai.neargo.shop.scenario.CounterCampaignFenceIT'),
    'backend/shop-app/target/classes/db/migration/V603__counter_campaign_owner_fence.sql',
    'backend/shop-core/src/main/resources/schema/counter-campaign-owner-fence-descriptor.json',
    'backend/shop-core/target/classes/schema/counter-campaign-owner-fence-descriptor.json',
    'docs/technical/design/schema/counter-campaign-owner-fence-schema.json',
    'scripts/gen-counter-campaign-owner-fence-schema.mjs',
    'backend/shop-app/src/main/resources/application.yml',
    'backend/shop-app/target/classes/application.yml',
    ...['application-test.yml', 'application-h2db.yml', 'application-testcfg.yml'].flatMap(name => [
      'backend/shop-app/src/test/resources/' + name, 'backend/shop-app/target/test-classes/' + name]),
    ...m2cB2Sources.flatMap(path => [path, path.replace('/src/main/java/', '/target/classes/')
      .replace('/src/test/java/', '/target/test-classes/').replace(/\.java$/, '.class')]),
    ...[
      'backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/create/CheckoutEconomicSnapshot.java',
      'backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/create/CheckoutPricingScope.java',
      'backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/create/CounterEconomicCodec.java',
      'backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/create/CounterQuoteSnapshot.java',
      'backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/create/CounterOrderPlan.java',
      'backend/shop-core/src/test/java/ai/neargo/shop/trade/order/service/create/CounterEconomicCompatibilityTest.java',
      'backend/shop-core/src/test/java/ai/neargo/shop/marketing/campaign/CounterCampaignRulesTest.java',
      'backend/shop-core/src/main/java/ai/neargo/shop/member/port/MemberCustomerIdentityPortImpl.java',
      'backend/shop-core/src/main/java/ai/neargo/shop/member/port/MemberGuestRegistrationPortImpl.java',
      'backend/shop-core/src/main/java/ai/neargo/shop/member/service/impl/MemberGuestRegistrationServiceImpl.java',
      'backend/shop-core/src/main/java/ai/neargo/shop/product/service/impl/StockDispositionServiceImpl.java',
      'backend/shop-core/src/main/java/ai/neargo/shop/product/service/impl/StoreSalesCatalogServiceImpl.java',
      'backend/shop-core/src/main/java/ai/neargo/shop/product/port/StoreSalesCatalogPortImpl.java',
      'backend/shop-core/src/main/java/ai/neargo/shop/product/port/ServiceProductFactsPortImpl.java',
      'backend/shop-core/src/main/java/ai/neargo/shop/member/service/impl/MemberCodeResolveServiceImpl.java',
      'backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/port/MerchantOperatorAccessPortImpl.java',
      'backend/shop-core/src/main/java/ai/neargo/shop/product/port/ServiceCatalogFactsPortImpl.java'
    ].flatMap(path => [path, path.replace('/src/main/java/', '/target/classes/')
      .replace('/src/test/java/', '/target/test-classes/').replace(/\.java$/, '.class')]),
    ...m2cOwners.flatMap(([module, kind, name]) => sourceAndClass(module, kind, name)),
    ...selected.flatMap(([module, name]) => sourceAndClass(module, 'test', name))
  ];
  if (['materialize-goldens', 'counter-protocol'].includes(stage)) paths.push(m2cArchiveManifest);
  return [...new Set(paths)].sort();
}

export function resourcePublicationPlan(goldenPins, runRoot) {
  assert(Array.isArray(goldenPins) && goldenPins.length === 6, 'six accepted golden pins required');
  assert(typeof runRoot === 'string' && runRoot.startsWith('/'), 'absolute owned run root required');
  assert.deepEqual(goldenPins.map(pin => pin.name).sort(), [...goldenNames].sort());
  for (const pin of goldenPins) assert(pin.path?.startsWith('/') && Number.isSafeInteger(pin.bytes) && pin.bytes >= 0 && /^[a-f0-9]{64}$/.test(pin.sha256 ?? ''));
  return {classpathRoot: join(runRoot, 'resources'), copies: goldenPins.map(pin => ({
    source: pin.path, destination: join(runRoot, 'resources/counter-economics', pin.name),
    bytes: pin.bytes, sha256: pin.sha256
  }))};
}

export function validateOldPendingBatch(entries, nowMs) {
  assert(Array.isArray(entries) && entries.length === 4, 'exact four old originals required');
  assert(Number.isFinite(nowMs), 'finite current time required');
  const databases = new Set(), runRoots = new Set();
  for (let i = 0; i < pendingKinds.length; i++) {
    const [suffix, kind] = pendingKinds[i], entry = entries[i];
    assert.equal(entry.suffix, suffix); assert.equal(entry.kind, kind);
    assert.equal(entry.baselineCommit, oldBaseline);
    assert(entry.inputPath?.startsWith('/') && entry.inputPath.endsWith('/pending/' + suffix + '/input.json'));
    const runRoot = dirname(dirname(dirname(entry.inputPath))); runRoots.add(runRoot);
    assert.equal(entry.pendingPath, join(runRoot, 'pending', suffix, 'old-pending.json'));
    assert.equal(entry.originPath, join(runRoot, 'origins', suffix + '.properties'));
    assert(typeof entry.database === 'string' && entry.database.length > 0);
    assert(entry.pendingPath?.endsWith('/' + suffix + '/old-pending.json'));
    assert(entry.originPath?.endsWith('/' + suffix + '.properties'));
    assert(entry.originMatched === true && entry.reservationObserved === true && entry.zeroEconomics === true,
      'old origin, reservation and zero economics required');
    assert(Number.isFinite(entry.quoteExpiryMs) && Number.isFinite(entry.confirmationExpiryMs));
    assert(Math.min(entry.quoteExpiryMs, entry.confirmationExpiryMs) - nowMs >= 225000,
      'old original has less than 225s remaining');
    databases.add(entry.database);
  }
  assert.equal(runRoots.size, 1, 'old originals must share one owned run root');
  assert.equal(databases.size, 1, 'all old originals must use the same owned database');
  return Math.min(...entries.flatMap(entry => [entry.quoteExpiryMs, entry.confirmationExpiryMs]));
}

export function m2cGatePath(phase) {
  assert(['prepare', 'green'].includes(phase), 'Task68 phase is not released');
  return workspace + (phase === 'prepare' ? '/task-68-materialization-root-gate.json'
    : '/task-68-native-root-gate.json');
}

export function m2cSchemaPlan() {
  const keys = ['platform', 'inventory', 'v539', 'v540', 'v541', 'preV542', 'v542', 'invV14',
    'require-seven-owner-tables', 'v543', 'capture-effective-schema'];
  return {topology: ['platform', 'inventory'], stockAuthority: 'PLATFORM',
    tables: [...m2cTables], bindings: m2cBindings.map(row => [...row]), properties: [...m2cJvmProperties],
    steps: keys.map(key => ({key, path: m2cSql[key] ?? null,
      schema: ['inventory', 'invV14'].includes(key) ? 'inventory' : 'platform'}))};
}

export function m2cOwnershipPlan(uuid) {
  assert(uuid4(uuid), 'owned UUIDv4 required');
  return {uuid, name: 'codex-counter-m2c-' + uuid, label: 'codex.task68.uuid=' + uuid,
    temporary: '/private/tmp/codex-counter-m2c-' + uuid,
    lock: '/tmp/codex-beauty-bm04-native.lock', terminateMs: 5000};
}

/** URI text is evidence; canonical local paths, bounded by the old tree, are origin identity. */
export function canonicalLocalFileIdentity(value, acceptedRoot) {
  assert(typeof value === 'string' && /^file:\//i.test(value), 'absolute local file URI required');
  const authority = value.match(/^file:\/\/([^/]*)/i)?.[1];
  assert(!authority, 'file URI authority is not accepted');
  const uri = new URL(value);
  assert(uri.protocol === 'file:' && uri.host === '' && !value.includes('?') && !value.includes('#'),
    'foreign origin scheme/authority/query/fragment');
  assert(typeof acceptedRoot === 'string' && acceptedRoot.startsWith('/'), 'accepted absolute origin root required');
  const parent = realpathSync(acceptedRoot), path = realpathSync(fileURLToPath(uri));
  assert(beneath(path, parent), 'origin escaped the independently accepted old root');
  return path;
}

/** A process group is signalable only while every observed member retains this UUID/start identity. */
export function ownedProcessSignalPlan(record, members, signal) {
  assert(['SIGTERM', 'SIGKILL'].includes(signal)); assert(uuid4(record.uuid));
  assert(Number.isSafeInteger(record.pid) && record.pid > 1);
  for (const member of members) {
    assert.equal(member.pgid, record.pid); assert.equal(member.uuid, record.uuid);
    if (member.pid === record.pid) assert.equal(member.startIdentity, record.startIdentity, 'owned PID was reused');
  }
  return members.length ? {pid: -record.pid, signal} : null;
}

function addClasspathDefinitions(definitions, entry) {
  assert(Array.isArray(entry.definitions), 'class/resource index required for each classpath entry');
  for (const name of entry.definitions) {
    assert(ownedRelative(name), 'invalid class/resource index');
    if ((name.endsWith('.class') && !name.endsWith('module-info.class'))
        || /^(application[^/]*\.yml|schema\/counter-|counter-economics\/)/.test(name)) {
      assert(!definitions.has(name), 'duplicate class/resource definition: ' + name);
      definitions.set(name, entry.path);
    }
  }
}

/** Manifests are inputs, never inferred from first-jar-wins or arbitrary cache contents. */
export function driverClasspathPlan(project, tools) {
  assert.equal(tools.schemaVersion, 1); assert.equal(tools.junitVersion, '6.0.3');
  assert(tools.poms?.length > 0, 'pinned BOM/dependency POMs required');
  assert.deepEqual(tools.artifacts.map(row => [row.coordinate, row.path]),
    toolCoordinates.map(([coordinate, path]) => [coordinate, toolCache + '/' + path]));
  const entries = project.entries;
  assert(Array.isArray(entries) && entries.length > 0, 'ordered project test classpath required');
  const paths = new Set(), definitions = new Map();
  const add = entry => {
    assert(typeof entry.path === 'string' && entry.path.startsWith('/') && !entry.path.includes('*')
      && entry.path !== root && entry.path !== project.workspaceRoot, 'explicit finite classpath root required');
    assert(!paths.has(entry.path), 'duplicate classpath root'); paths.add(entry.path);
    assert(!/\/(?:ai\/neargo\/shop|beauty|food)\/.*\.jar$/.test(entry.path)
      && !/\/(?:shop|beauty|food)-[^/]*\.jar$/.test(entry.path), 'installed owner/provider jar rejected');
    addClasspathDefinitions(definitions, entry);
    if (/\/(junit-(?:jupiter|platform)-[^/]+)\//.test(entry.path))
      assert(/\/6\.0\.3\/[^/]+-6\.0\.3\.jar$/.test(entry.path), 'JUnit versions must align at 6.0.3');
  };
  entries.forEach(add);
  for (const name of ['api', 'params', 'engine']) assert(entries.some(e =>
    e.path.endsWith('/junit-jupiter-' + name + '-6.0.3.jar')), 'actual Jupiter ' + name + ' missing');
  for (const [coordinate, expectedPath] of toolCoordinates) {
    const artifact = coordinate.split(':')[1];
    for (const entry of entries) if (entry.path.split('/').at(-1).startsWith(artifact + '-'))
      assert.equal(entry.path, toolCache + '/' + expectedPath, 'conflicting runner tool coordinate/version');
  }
  const suffix = [];
  for (const artifact of tools.artifacts) {
    assert(Number.isSafeInteger(artifact.bytes) && /^[a-f0-9]{64}$/.test(artifact.sha256 ?? ''));
    const same = entries.find(entry => entry.path === artifact.path);
    if (same) {
      assert.equal(same.bytes, artifact.bytes); assert.equal(same.sha256, artifact.sha256);
    } else { add(artifact); suffix.push(artifact.path); }
  }
  const classpath = [...entries.map(entry => entry.path), ...suffix];
  return {currentClasspath: entries.map(entry => entry.path), runnerToolSuffix: suffix,
    javacClasspath: classpath, javaClasspath: [...classpath], definitions: Object.fromEntries(definitions)};
}

function outputSlots(stage) {
  const slot = (path, producer, kind = 'file') => ({path, producer, kind, mustBeAbsent: true});
  if (stage === 'materialize-runtime') return [
    slot('materials/old-source', 'git-archive-and-old-maven', 'directory'),
    slot('materials/old-tree.tar', 'git-archive'),
    slot('materials/old-producer-sources', 'pinned-B2-and-shim', 'directory'),
    slot('materials/old-producer-classes', 'old-javac', 'directory'),
    slot('materials/current-driver-sources', 'literal-driver-templates', 'directory'),
    slot('materials/current-driver-classes', 'current-javac', 'directory'),
    slot('materials/expected-old-origins.properties', 'static-old-classpath-map'),
    slot('materials/runtime-manifest.json', 'runtime-materializer')];
  if (stage === 'materialize-goldens') return [
    slot('materials/goldens', 'Task68GoldenMain', 'directory'),
    ...goldenNames.map(name => slot('materials/goldens/' + name, 'Task68GoldenMain')),
    slot('materials/golden-manifest.json', 'golden-materializer')];
  const reports = [slot('reports', 'Task68SelectedMain', 'directory')];
  if (stage === 'counter-protocol') reports.push(slot('resources', 'accepted-byte-publication', 'directory'));
  if (stage === 'counter-old-pending') for (const [suffix] of pendingKinds) {
    reports.push(slot('origins/' + suffix + '.properties', 'Task68OldMain'),
      slot('pending/' + suffix, 'OldPendingProducer', 'directory'));
  }
  return reports;
}

/** Pure gate validation: filesystem observations are separately supplied, never assumed. */
export function validateM2cGate(gate, inventory) {
  assert.equal(gate.schemaVersion, 1); assert.equal(gate.approved, true);
  assert.equal(gate.slice, 'm2c'); assert(uuid4(gate.uuid), 'Task68 UUIDv4 required');
  const contract = m2cStages[gate.stage]; assert(contract, 'unreleased Task68 stage');
  assert.equal(gate.phase, contract[0]); assert.equal(gate.action, contract[1]);
  assert.equal(gate.workspaceRoot, inventory.workspaceRoot); assert.equal(gate.head, inventory.head);
  assert(/^[a-f0-9]{40}$/.test(gate.head ?? ''), 'exact source HEAD required');
  assert.equal(gate.stockAuthority, 'PLATFORM');
  assert.deepEqual(gate.schemaTopology, ['materialize-runtime', 'counter-protocol'].includes(gate.stage)
    ? [] : ['platform', 'inventory']);
  assert.deepEqual(gate.selection, gate.phase === 'prepare' ? null : phaseSelection('m2c', 'green', gate.stage));
  assert.deepEqual(gate.properties, [...m2cJvmProperties]);
  assert.deepEqual(gate.derivedOutputs, outputSlots(gate.stage), 'exact derived output allowlist required');
  assert.equal(inventory.usedUuid, false, 'failed/used UUID cannot be reused');
  assert(Array.isArray(gate.inputRoots) && gate.inputRoots.length > 0);
  assert(Array.isArray(gate.files) && gate.files.length > 0, 'complete input pins required');
  const seen = new Set();
  for (const pin of gate.files) {
    assert(pin.path?.startsWith('/') && Number.isSafeInteger(pin.bytes) && pin.bytes >= 0
      && /^[a-f0-9]{64}$/.test(pin.sha256 ?? ''), 'absolute input path/bytes/hash required');
    assert(!seen.has(pin.path), 'duplicate/conflicting input pin'); seen.add(pin.path);
    assert(gate.inputRoots.some(parent => beneath(pin.path, parent)), 'input outside roots');
    assert.deepEqual(inventory.files?.[pin.path], pin, 'missing/changed actual input: ' + pin.path);
  }
  for (const path of requiredM2cPinPaths(gate.selection, gate.stage))
    assert(seen.has(join(gate.workspaceRoot, path)), 'missing actual source/class/resource pin: ' + path);
  for (const path of inventory.requiredLeaves ?? []) assert(seen.has(path), 'missing closure leaf: ' + path);
  for (const name of ['java', 'javac', 'maven', 'git', 'docker', 'ps']) {
    assert(seen.has(gate.tools?.[name]), 'pinned executable required: ' + name);
  }
  assert.deepEqual(gate.timeouts, {oldBuild: 1200, javac: 120, readiness: 90, action: 600,
    golden: 120, terminate: 5});
  assert.deepEqual(gate.commands, ['git-archive', 'old-offline-test-compile-dependency-classpath',
    'current-driver-javac', 'old-overlay-javac', 'owned-docker-mysql', 'golden-java',
    'old-origin-then-producer-java', 'selected-junit-java'], 'finite command families required');
  for (const key of ['currentClasspathManifest', 'runnerToolManifest', 'buildToolsManifest']) assert(seen.has(gate[key]), key + ' pin absent');
  if (gate.stage === 'materialize-runtime') {
    assert.equal(gate.oldBaseline?.commit, oldBaseline);
    assert(/^[a-f0-9]{40}$/.test(gate.oldBaseline.tree ?? ''));
    assert(seen.has(gate.oldBaseline.treeManifest), 'accepted old tracked-tree manifest required');
    assert(seen.has(gate.oldBaseline.dependenciesManifest), 'old offline dependency closure required');
  } else {
    assert(seen.has(gate.runtimeManifest), 'root accepted runtime manifest required');
    assert.equal(inventory.runtimeAccepted, true, 'root must accept existing derived runtime plus leaves');
  }
  if (['materialize-goldens', 'counter-protocol'].includes(gate.stage)) {
    assert.equal(inventory.archiveEntryCount, 8, 'exact eight genuine archive inputs required');
    assert.equal(inventory.archiveSourceCommit, '531d2a87b98f1d15e5a002a5100c4545ab6280b9');
    assert.equal(inventory.archiveDocCommit, '28ebe9f111ede8363194cd0e42d50005de465a39');
  }
  if (gate.stage === 'counter-protocol') {
    assert(seen.has(gate.goldenManifest)); assert.equal(inventory.goldensAccepted, true);
    resourcePublicationPlan(gate.goldenPins, inventory.runRoot);
    for (const pin of gate.goldenPins) assert(seen.has(pin.path));
  }
  if (gate.stage === 'counter-old-pending') {
    assert(seen.has(gate.expectedOldOrigins), 'accepted expected-origin properties required');
    assert.equal(inventory.expectedOriginAccepted, true);
  }
  if (gate.schemaTopology.length) {
    assert.deepEqual(gate.native, {context: 'colima-hxkey-tests', image: 'mysql:9.7.2',
      imageId: inventory.imageId, imageDigest: inventory.imageDigest});
    assert(/^sha256:[a-f0-9]{64}$/.test(gate.native.imageId ?? ''));
    assert(/@sha256:[a-f0-9]{64}$/.test(gate.native.imageDigest ?? ''));
  }
  return {selection: gate.selection, slots: outputSlots(gate.stage)};
}

export function validateSelectedEvents(selection, result) {
  const expected = selection.flatMap(([, cls, methods]) => methods.map(method => cls + '#' + method));
  assert.deepEqual(result.events.map(e => e.className + '#' + e.methodName).sort(), [...expected].sort());
  assert(result.events.every(e => e.started && e.status === 'SUCCESSFUL'), 'missing/skipped/failed test event');
  assert(result.containers.every(e => e.status === 'SUCCESSFUL'), 'container/setup/teardown failure');
  assert(expected.length > 0, 'zero test green is forbidden');
  return expected.length;
}

/** Literal source templates are reviewed with this runner; generated files stay outside target/. */
export function task68DriverSources() {
  const Task68GoldenMain = String.raw`package ai.neargo.shop.scenario;
import ai.neargo.shop.ShopApplication;
import ai.neargo.shop.trade.service.OrderService;
import ai.neargo.shop.trade.service.impl.OrderServiceImpl;
import ai.neargo.shop.trade.order.service.create.CounterEconomicCompatibilityTest;
import ai.neargo.shop.marketing.campaign.CounterCampaignFence;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.boot.SpringApplication;
import org.springframework.test.util.AopTestUtils;
import tools.jackson.databind.ObjectMapper;
public final class Task68GoldenMain {
  static Map<String, Object> origin(Class<?> type) throws Exception {
    String resource = type.getName().replace('.', '/') + ".class";
    var found = Collections.list(type.getClassLoader().getResources(resource));
    if (found.size() != 1) throw new IllegalStateException("mapper/owner class resource ambiguity");
    byte[] bytes; try (var stream = found.getFirst().openStream()) { bytes = stream.readAllBytes(); }
    return Map.of("class", type.getName(),
      "origin", type.getProtectionDomain().getCodeSource().getLocation().toString(),
      "bytes", bytes.length, "sha256",
      HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
  }
  public static void main(String[] args) throws Exception {
    if (args.length != 2 || Files.exists(Path.of(args[1])))
      throw new IllegalArgumentException("fresh golden output required");
    var app = new SpringApplication(ShopApplication.class, CheckoutFlowFixture.ClockConfig.class);
    app.setAdditionalProfiles("test");
    app.setDefaultProperties(Map.of("shop.member-code.enabled", "true",
      "shop.catalog.publication.archive-only", "true",
      "shop.member.guest.credential-key", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="));
    try (var context = app.run()) {
      var applicationJson = context.getBean(ObjectMapper.class);
      Object owner = AopTestUtils.getUltimateTargetObject(context.getBean(OrderService.class));
      if (owner.getClass() != OrderServiceImpl.class)
        throw new IllegalStateException("actual OrderServiceImpl required");
      var field = OrderServiceImpl.class.getDeclaredField("legacyCheckoutJson");
      field.setAccessible(true);
      if (!(field.get(owner) instanceof ObjectMapper legacyJson))
        throw new IllegalStateException("actual legacy mapper required");
      try (var connection = context.getBean(DataSource.class).getConnection()) {
        if (!"MySQL".equals(connection.getMetaData().getDatabaseProductName())
            || !connection.getMetaData().getURL().equals(System.getenv("SPRING_DATASOURCE_URL")))
          throw new IllegalStateException("native primary datasource drift");
      }
      context.getBean(CounterCampaignFence.class).validateSchema();
      System.out.println("TASK68_MAPPERS=" + applicationJson.writeValueAsString(Map.of(
        "applicationClass", applicationJson.getClass().getName(), "legacyClass", legacyJson.getClass().getName(),
        "sameIdentity", applicationJson == legacyJson,
        "applicationOrigin", applicationJson.getClass().getProtectionDomain().getCodeSource().getLocation().toString(),
        "legacyOrigin", legacyJson.getClass().getProtectionDomain().getCodeSource().getLocation().toString(),
        "ownerOrigin", owner.getClass().getProtectionDomain().getCodeSource().getLocation().toString(),
        "applicationClassPin", origin(applicationJson.getClass()), "legacyClassPin", origin(legacyJson.getClass()),
        "ownerClassPin", origin(owner.getClass()))));
      CounterEconomicCompatibilityTest.generateBaseline(
        applicationJson, legacyJson, Path.of(args[0]), Path.of(args[1]));
      CounterEconomicCompatibilityTest.verifyBaseline(applicationJson, legacyJson, Path.of(args[1]));
    }
  }
}
`;
  const Task68OldMain = String.raw`package ai.neargo.shop.scenario;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
public final class Task68OldMain {
  public static String canonicalLocalOrigin(String value, String acceptedRoot) throws Exception {
    if (value == null || acceptedRoot == null) throw new IllegalArgumentException("missing accepted file origin");
    var uri = URI.create(value);
    if (!"file".equalsIgnoreCase(uri.getScheme()) || uri.isOpaque()
        || uri.getRawAuthority() != null && !uri.getRawAuthority().isEmpty()
        || uri.getRawQuery() != null || uri.getRawFragment() != null)
      throw new IllegalArgumentException("foreign origin scheme/authority/query/fragment");
    var root = Path.of(acceptedRoot);
    if (!root.isAbsolute()) throw new IllegalArgumentException("accepted origin root must be absolute");
    var path = Path.of(uri).toRealPath();
    if (!path.startsWith(root.toRealPath())) throw new IllegalArgumentException("origin escaped accepted old root");
    return path.toString();
  }
  public static boolean sameLocalOrigin(String expected, String observed, String acceptedRoot) throws Exception {
    return canonicalLocalOrigin(expected, acceptedRoot).equals(canonicalLocalOrigin(observed, acceptedRoot));
  }
  public static void main(String[] args) throws Exception {
    if (args.length != 4 || !List.of("USER", "GUEST").contains(args[2]) || Files.exists(Path.of(args[1]))
        || Files.exists(Path.of(args[3]))) throw new IllegalArgumentException("fresh origin/producer outputs required");
    var expected = new Properties();
    try (var reader = Files.newBufferedReader(Path.of(args[0]), StandardCharsets.UTF_8)) { expected.load(reader); }
    var actual = new Properties();
    actual.setProperty("baselineCommit", expected.getProperty("baselineCommit", ""));
    boolean matched = "2c0708f752d747d88a9eda7478326a964d1984fa".equals(expected.getProperty("baselineCommit"));
    var names = List.of("ai.neargo.shop.ShopApplication", "ai.neargo.shop.trade.service.impl.CheckoutServiceImpl",
      "ai.neargo.shop.trade.service.impl.OrderServiceImpl",
      "ai.neargo.shop.trade.order.service.create.impl.CounterOrderPlacementServiceImpl",
      "ai.neargo.shop.trade.port.ManualServiceAdmissionPortImpl", "ai.neargo.shop.product.port.StockPortImpl",
      "ai.neargo.shop.invbridge.port.OriginalStockDispositionPort", "ai.neargo.shop.scenario.CheckoutFlowFixture",
      "ai.neargo.shop.scenario.CheckoutFlowFixture$ClockConfig");
    try {
      for (String name : names) {
        var type = Class.forName(name, false, Task68OldMain.class.getClassLoader());
        String path = name.replace('.', '/') + ".class";
        var resources = Collections.list(type.getClassLoader().getResources(path));
        if (resources.size() != 1 || type.getProtectionDomain().getCodeSource() == null)
          throw new IllegalStateException("missing/duplicate/null old origin: " + name);
        byte[] bytes;
        try (var stream = resources.getFirst().openStream()) { bytes = stream.readAllBytes(); }
        String observedUri = type.getProtectionDomain().getCodeSource().getLocation().toURI().toString();
        actual.setProperty(name + ".codeSourceUri", observedUri);
        matched &= sameLocalOrigin(expected.getProperty(name + ".codeSourceUri"), observedUri,
          expected.getProperty("baselineRoot"));
        var facts = Map.of("codeSourcePath", canonicalLocalOrigin(observedUri, expected.getProperty("baselineRoot")),
          "classRelativePath", path, "bytes", String.valueOf(bytes.length),
          "sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        for (var entry : facts.entrySet()) {
          String key = name + "." + entry.getKey(); actual.setProperty(key, entry.getValue());
          matched &= entry.getValue().equals(expected.getProperty(key));
        }
      }
    } catch (Throwable failure) {
      matched = false; actual.setProperty("originError", failure.toString());
    }
    actual.setProperty("matched", String.valueOf(matched));
    try (var writer = Files.newBufferedWriter(Path.of(args[1]), StandardCharsets.UTF_8,
        StandardOpenOption.CREATE_NEW)) {
      actual.store(writer, "actual loaded origins before producer"); writer.flush();
    }
    if (!matched) throw new IllegalStateException("old origins differ; producer was not invoked");
    Class.forName("ai.neargo.shop.scenario.ManualCounterDiscountFixture$OldPendingProducer")
      .getMethod("main", String[].class).invoke(null, (Object) new String[] {args[2], args[3]});
  }
}
`;
  const Task68SelectedMain = String.raw`package ai.neargo.shop.scenario;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.io.StringWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.EngineFilter;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import tools.jackson.databind.ObjectMapper;
public final class Task68SelectedMain {
  static final Map<String, Map<String, Object>> events = new LinkedHashMap<>();
  static final List<Map<String, Object>> containers = new ArrayList<>();
  static String key(TestIdentifier id) {
    if (!(id.getSource().orElse(null) instanceof MethodSource source))
      throw new IllegalStateException("nonmethod/dynamic test: " + id);
    return source.getClassName() + "#" + source.getMethodName();
  }
  static String escaped(Object value) {
    return String.valueOf(value).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
      .replace("\"", "&quot;").replace("'", "&apos;");
  }
  static String stack(Throwable failure) {
    var text = new StringWriter(); failure.printStackTrace(new PrintWriter(text)); return text.toString();
  }
  public static void main(String[] args) throws Exception {
    if (args.length != 2 || Files.exists(Path.of(args[1])))
      throw new IllegalArgumentException("fresh reports required");
    var directory = Files.createDirectory(Path.of(args[1]));
    var json = new ObjectMapper();
    var selection = json.readTree(Files.readString(Path.of(args[0])));
    Set<String> expected = new LinkedHashSet<>();
    var builder = LauncherDiscoveryRequestBuilder.request().filters(EngineFilter.includeEngines("junit-jupiter"))
      .configurationParameter("junit.jupiter.execution.parallel.enabled", "false");
    for (var row : selection) for (var method : row.get(2)) {
      String cls = row.get(1).asString(), name = method.asString();
      if (!expected.add(cls + "#" + name)) throw new IllegalArgumentException("duplicate selection");
      builder.selectors(DiscoverySelectors.selectMethod(cls, name));
    }
    String resources = System.getProperty("task68.resources");
    if (resources != null) for (String name : List.of("ordinary-v1.json", "m1-v1.json", "m2a-user-v1.json",
        "m2a-guest-v1.json", "m2a-pending-plan-v1.json", "hashes.json")) {
      var found = Collections.list(Task68SelectedMain.class.getClassLoader().getResources("counter-economics/" + name));
      if (found.size() != 1 || !Path.of(found.getFirst().toURI()).toRealPath()
          .equals(Path.of(resources, "counter-economics", name).toRealPath()))
        throw new IllegalStateException("ambiguous or missing golden resource: " + name);
    }
    var provenance = new LinkedHashMap<String, Object>();
    var classes = new LinkedHashSet<String>(List.of("org.junit.platform.launcher.core.LauncherFactory",
      "org.junit.jupiter.engine.JupiterTestEngine"));
    for (String item : expected) classes.add(item.substring(0, item.indexOf('#')));
    for (String name : classes) {
      var type = Class.forName(name, false, Task68SelectedMain.class.getClassLoader());
      String resource = name.replace('.', '/') + ".class";
      var found = Collections.list(type.getClassLoader().getResources(resource));
      if (found.size() != 1) throw new IllegalStateException("ambiguous loaded class " + name);
      byte[] bytes; try (var stream = found.getFirst().openStream()) { bytes = stream.readAllBytes(); }
      provenance.put(name, Map.of("origin", type.getProtectionDomain().getCodeSource().getLocation().toString(),
        "bytes", bytes.length, "sha256",
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))));
    }
    Files.writeString(directory.resolve("origins.json"), json.writeValueAsString(provenance));
    var launcher = LauncherFactory.create();
    var plan = launcher.discover(builder.build());
    Set<String> found = new LinkedHashSet<>();
    for (var root : plan.getRoots()) for (var id : plan.getDescendants(root)) if (id.isTest()) {
      if (!plan.getChildren(id).isEmpty() || !found.add(key(id)))
        throw new IllegalStateException("duplicate/nonleaf test");
    }
    if (expected.isEmpty() || !found.equals(expected))
      throw new IllegalStateException("discovery differs from exact selection");
    var summary = new SummaryGeneratingListener();
    var listener = new TestExecutionListener() {
      public void dynamicTestRegistered(TestIdentifier id) {
        throw new IllegalStateException("dynamic test forbidden");
      }
      public void executionStarted(TestIdentifier id) {
        if (id.isTest()) {
          String name = key(id);
          if (!expected.contains(name) || events.containsKey(name))
            throw new IllegalStateException("extra/duplicate start");
          var event = new LinkedHashMap<String, Object>(); event.put("started", true); events.put(name, event);
        }
      }
      public void executionSkipped(TestIdentifier id, String reason) {
        if (id.isTest()) {
          var event = events.computeIfAbsent(key(id), ignored -> new LinkedHashMap<>());
          event.put("status", "SKIPPED"); event.put("started", false); event.put("message", reason);
        } else containers.add(Map.of("status", "SKIPPED", "name", id.getUniqueId(), "message", reason));
      }
      public void executionFinished(TestIdentifier id, TestExecutionResult result) {
        if (id.isTest()) {
          var event = events.computeIfAbsent(key(id), ignored -> new LinkedHashMap<>());
          event.put("status", result.getStatus().name());
          result.getThrowable().ifPresent(failure -> {
            event.put("assertion", failure instanceof AssertionError); event.put("message", stack(failure));
          });
        } else {
          var event = new LinkedHashMap<String, Object>(); event.put("status", result.getStatus().name());
          event.put("name", id.getUniqueId()); result.getThrowable().ifPresent(f -> event.put("message", stack(f)));
          containers.add(event);
        }
      }
    };
    try { launcher.execute(plan, listener, summary); }
    finally {
      var records = new ArrayList<Map<String, Object>>();
      for (var entry : events.entrySet()) {
        var event = new LinkedHashMap<>(entry.getValue());
        event.put("className", entry.getKey().split("#", 2)[0]);
        event.put("methodName", entry.getKey().split("#", 2)[1]); records.add(event);
      }
      Files.writeString(directory.resolve("events.json"), json.writeValueAsString(Map.of("events", records,
        "containers", containers, "summaryFound", summary.getSummary().getTestsFoundCount(),
        "summarySucceeded", summary.getSummary().getTestsSucceededCount(),
        "summaryContainerFailures", summary.getSummary().getContainersFailedCount())));
      // All reports are written before any result is inspected, including missing/aborted leaves.
      for (var row : selection) {
        String cls = row.get(1).asString(); int failures = 0, errors = 0, skipped = 0;
        var cases = new StringBuilder();
        for (var method : row.get(2)) {
          String name = method.asString(); var event = events.get(cls + "#" + name);
          cases.append("<testcase classname=\"").append(escaped(cls))
            .append("\" name=\"").append(escaped(name)).append("\">");
          if (event == null || !Boolean.TRUE.equals(event.get("started"))) {
            errors++; cases.append("<error message=\"missing actual completed test\"/>");
          } else if ("ABORTED".equals(event.get("status")) || "SKIPPED".equals(event.get("status"))) {
            skipped++; cases.append("<skipped/>");
          } else if (!"SUCCESSFUL".equals(event.get("status"))) {
            boolean assertion = Boolean.TRUE.equals(event.get("assertion"));
            if (assertion) failures++; else errors++;
            String tag = assertion ? "failure" : "error";
            cases.append("<").append(tag).append(">").append(escaped(event.get("message")))
              .append("</").append(tag).append(">");
          }
          cases.append("</testcase>");
        }
        Files.writeString(directory.resolve("TEST-" + cls + ".xml"), "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
          + "<testsuite name=\"" + escaped(cls) + "\" tests=\"" + row.get(2).size() + "\" failures=\"" + failures
          + "\" errors=\"" + errors + "\" skipped=\"" + skipped + "\">" + cases + "</testsuite>",
          StandardCharsets.UTF_8);
      }
    }
    boolean passed = events.keySet().equals(expected) && events.values().stream()
      .allMatch(e -> Boolean.TRUE.equals(e.get("started")) && "SUCCESSFUL".equals(e.get("status")))
      && containers.stream().allMatch(e -> "SUCCESSFUL".equals(e.get("status")))
      && summary.getSummary().getTestsFoundCount() == expected.size()
      && summary.getSummary().getTestsSucceededCount() == expected.size()
      && summary.getSummary().getContainersFailedCount() == 0;
    if (!passed) throw new IllegalStateException("actual JUnit events were not an exact green");
  }
}
`;
  return {Task68GoldenMain, Task68SelectedMain, Task68OldMain};
}

const jsonFile = path => JSON.parse(readFileSync(path, 'utf8'));
const privateWrite = (path, value) => {
  mkdirSync(dirname(path), {recursive: true, mode: 0o700});
  writeFileSync(path, typeof value === 'string' || Buffer.isBuffer(value) ? value : JSON.stringify(value, null, 2),
    {flag: 'wx', mode: 0o600});
};
function requirePinnedLeaves(files, pins) {
  const byPath = new Map(pins.map(pin => [pin.path, pin]));
  for (const file of files) { assert(byPath.has(file.path), 'missing referenced leaf: ' + file.path);
    assert.deepEqual(corePin(byPath.get(file.path)), corePin(file), 'unapproved referenced leaf: ' + file.path); }
}

// Reading ZIP central-directory names does not run jar, javap, a JVM or a class initializer.
function classpathDefinitions(path) {
  if (statSync(path).isDirectory()) return runtimeClasspathFiles([path]).map(file => relative(path, file).split(sep).join('/'));
  const data = readFileSync(path);
  let end = data.length - 22;
  while (end >= Math.max(0, data.length - 65557) && data.readUInt32LE(end) !== 0x06054b50) end--;
  assert(end >= Math.max(0, data.length - 65557), 'missing jar central directory');
  const count = data.readUInt16LE(end + 10), offset = data.readUInt32LE(end + 16);
  assert(count !== 65535 && offset !== 0xffffffff, 'ZIP64 classpath jar requires a reviewed index');
  const names = []; let cursor = offset;
  for (let i = 0; i < count; i++) {
    assert.equal(data.readUInt32LE(cursor), 0x02014b50, 'invalid jar directory');
    const length = data.readUInt16LE(cursor + 28), extra = data.readUInt16LE(cursor + 30), comment = data.readUInt16LE(cursor + 32);
    const name = data.subarray(cursor + 46, cursor + 46 + length).toString('utf8');
    if (!name.endsWith('/')) { assert(ownedRelative(name)); names.push(name); }
    cursor += 46 + length + extra + comment;
  }
  assert.equal(new Set(names).size, names.length, 'duplicate jar entry');
  return names.sort();
}

function strictClasspathLeaves(path) {
  assert(typeof path === 'string' && path.startsWith('/') && !path.includes('*'), 'finite absolute classpath required');
  assert.equal(realpathSync(path), path, 'classpath root alias/symlink is not accepted');
  const leaves = [];
  const visit = current => {
    const stat = lstatSync(current);
    assert(!stat.isSymbolicLink(), 'classpath symlink leaf/directory is not accepted');
    if (stat.isFile()) { leaves.push(current); return; }
    assert(stat.isDirectory(), 'classpath has a non-file resource');
    for (const name of readdirSync(current).sort()) visit(join(current, name));
  };
  visit(path);
  return leaves.sort();
}

export function verifyEffectiveClasspath(classpath, pins) {
  assert(Array.isArray(classpath) && classpath.length > 0);
  assert.equal(new Set(classpath).size, classpath.length, 'duplicate effective classpath root');
  const accepted = new Map();
  for (const pin of pins) {
    assert(!accepted.has(pin.path), 'duplicate/conflicting effective input pin'); accepted.set(pin.path, corePin(pin));
  }
  const definitions = new Map(), entries = [];
  for (const path of classpath) {
    assert.notEqual(path, root, 'workspace root cannot be a classpath entry');
    const leaves = strictClasspathLeaves(path), directory = lstatSync(path).isDirectory();
    assert(directory || path.endsWith('.jar'), 'classpath file must be a pinned jar');
    const expected = [...accepted.keys()].filter(file => directory ? beneath(file, path) : file === path).sort();
    assert.deepEqual(leaves, expected, 'effective classpath has extra/missing accepted leaves: ' + path);
    for (const file of leaves) assert.deepEqual(pinnedFile(file), accepted.get(file), 'effective classpath byte drift: ' + file);
    const entry = {path, definitions: directory ? leaves.map(file => relative(path, file).split(sep).join('/'))
      : classpathDefinitions(path)};
    addClasspathDefinitions(definitions, entry); entries.push(entry);
  }
  return entries;
}

function combinedPins(...groups) {
  const pins = new Map();
  for (const pin of groups.flat()) {
    if (pins.has(pin.path)) assert.deepEqual(pins.get(pin.path), corePin(pin), 'conflicting root/derived leaf pin');
    else pins.set(pin.path, corePin(pin));
  }
  return [...pins.values()];
}

function verifyRuntimeClasspaths(inputs, acceptedPins, derived = []) {
  verifyEffectiveClasspath(inputs.classpath.javaClasspath, acceptedPins);
  if (inputs.runtime) {
    const runtime = inputs.runtime;
    verifyEffectiveClasspath([runtime.currentDriverClasses, ...runtime.driverClasspath], acceptedPins);
    verifyEffectiveClasspath([runtime.oldProducerClasses, ...runtime.oldClasspath], acceptedPins);
  }
  const pins = combinedPins(acceptedPins, ...derived.map(closure => closure.pins));
  for (const closure of derived) verifyEffectiveClasspath(closure.classpath, pins);
}

function registerDerivedClasspath(state, name, classpath, producedPins) {
  assert(!state.derivedClasspathClosures.some(closure => closure.name === name), 'derived closure cannot be rebaselined');
  verifyRuntimeClasspaths(state.inputs, state.gate.files, state.derivedClasspathClosures);
  const slots = state.gate.derivedOutputs.filter(slot => slot.kind === 'directory')
    .map(slot => join(state.evidence, slot.path));
  for (const pin of producedPins) {
    assert(slots.some(path => beneath(pin.path, path)), 'derived classpath leaf is not an approved output slot');
    assert.equal(realpathSync(pin.path), pin.path, 'derived output symlink/escape');
  }
  const closure = {name, classpath: [...classpath], pins: producedPins.map(corePin)};
  const pins = combinedPins(state.gate.files, ...state.derivedClasspathClosures.map(item => item.pins), closure.pins);
  const entries = verifyEffectiveClasspath(classpath, pins);
  state.derivedClasspathClosures.push(closure);
  privateWrite(join(state.evidence, 'derived-classpath-' + name + '.json'), {...closure, entries,
    authority: 'ROOT_APPROVED_OUTPUT_SLOTS_ONLY', rootAcceptance: 'NOT_GRANTED_BY_RUNNER'});
}

function verifiedClasspathManifest(path, pins) {
  const manifest = jsonFile(path); assert.equal(manifest.schemaVersion, 1);
  assert(Array.isArray(manifest.entries) && manifest.entries.length > 0);
  for (const entry of manifest.entries) {
    assert.equal(realpathSync(entry.path), entry.path, 'noncanonical classpath root');
    assert(entry.path !== root && (entry.path.endsWith('.jar') || /\/target\/(?:test-)?classes$/.test(entry.path)),
      'unlisted directory or source directory is not a runtime classpath');
    assert.deepEqual(entry.definitions, classpathDefinitions(entry.path), 'classpath class/resource index drift');
    const files = runtimeClasspathFiles([entry.path]).map(pinnedFile);
    requirePinnedLeaves(files, pins);
    if (entry.path.endsWith('.jar')) assert.deepEqual(pinnedFile(entry.path),
      {path: entry.path, bytes: entry.bytes, sha256: entry.sha256});
  }
  requirePinnedLeaves(manifest.modelInputs ?? [], pins);
  assert(manifest.modelInputs?.some(pin => pin.path.endsWith('.pom') || pin.path.endsWith('pom.xml')),
    'resolved project model/BOM/plugin POM inputs required');
  return manifest;
}

function readAccepted(path, kind, gate) {
  const manifest = jsonFile(path);
  assert.equal(manifest.schemaVersion, 1); assert.equal(manifest.kind, kind);
  assert(uuid4(manifest.uuid) && manifest.uuid !== gate.uuid, 'accepted output needs a fresh UUID');
  assert.equal(manifest.outcome, 'MATERIALIZED_PENDING_ROOT_ACCEPTANCE');
  assert(Array.isArray(manifest.files) && manifest.files.length > 0);
  requirePinnedLeaves(manifest.files, gate.files);
  for (const file of manifest.files) verifyFilePin(file, gate.inputRoots);
  return manifest;
}

function verifyArchiveInputs(gate) {
  const path = join(root, m2cArchiveManifest), manifest = jsonFile(path), directory = dirname(path);
  const archiveClassRoot = 'backend/shop-core/target/classes/ai/neargo/shop/trade/order/service/create/';
  const archiveSourceRoot = 'backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/create/';
  assert.deepEqual(manifest.files.map(pin => pin.path).sort(), [
    archiveSourceRoot + 'CheckoutEconomicSnapshot.java', archiveSourceRoot + 'CounterOrderPlan.java',
    ...['CheckoutEconomicSnapshot', 'CheckoutEconomicSnapshot$Evidence', 'CounterOrderPlan',
      'CounterOrderPlan$CompensationEvidence', 'CounterOrderPlan$PreparedCommand', 'CounterOrderPlan$SelectorProof']
      .map(name => archiveClassRoot + name + '.class')].sort());
  assert.equal(manifest.files.length, 8);
  assert.equal(manifest.files.filter(pin => pin.path.endsWith('.java')).length, 2);
  assert.equal(manifest.files.filter(pin => pin.path.endsWith('.class')).length, 6);
  const leaves = manifest.files.map(pin => ({...pin, path: join(directory, pin.path)}));
  for (const pin of leaves) verifyFilePin(pin, gate.inputRoots);
  requirePinnedLeaves(leaves, gate.files);
  return manifest;
}

function m2cInputs(gate, head) {
  const observed = {};
  assert.equal(realpathSync(gate.workspaceRoot), root);
  assert(gate.inputRoots.every(path => path.startsWith('/') && realpathSync(path) === path));
  for (const pin of gate.files) observed[pin.path] = {...pin, ...verifyFilePin(pin, gate.inputRoots)};
  const project = verifiedClasspathManifest(gate.currentClasspathManifest, gate.files);
  assert.equal(project.workspaceRoot, root);
  assert(project.exportLog && project.exportCommand?.includes('org.apache.maven.plugins:maven-dependency-plugin:3.7.0:build-classpath'));
  requirePinnedLeaves([project.exportLog], gate.files);
  assert.deepEqual(project.entries.map(entry => entry.path),
    exportedDependencyClasspath(readFileSync(project.exportLog.path, 'utf8'), root, true).map(path => realpathSync(path)),
    'current output-prefix/export order differs from accepted model');
  const tools = jsonFile(gate.runnerToolManifest);
  requirePinnedLeaves([...(tools.poms ?? []), ...(tools.artifacts ?? []).map(({coordinate, definitions, ...pin}) => pin)], gate.files);
  for (const artifact of tools.artifacts ?? []) {
    assert.deepEqual(artifact.definitions, classpathDefinitions(artifact.path), 'runner tool index drift');
  }
  const classpath = driverClasspathPlan(project, tools);
  const requiredLeaves = runtimeClasspathFiles(classpath.javaClasspath);
  const buildTools = jsonFile(gate.buildToolsManifest);
  assert.equal(buildTools.schemaVersion, 1); assert.equal(buildTools.javaRelease, 21);
  requirePinnedLeaves(buildTools.files, gate.files);
  for (const path of Object.values(gate.tools)) assert(buildTools.files.some(pin => pin.path === path));
  assert(buildTools.files.some(pin => pin.path.includes('maven-dependency-plugin/3.7.0/') && pin.path.endsWith('.jar')),
    'pinned dependency exporter plugin jar required');
  assert(buildTools.files.some(pin => pin.path.includes('maven-dependency-plugin/3.7.0/') && pin.path.endsWith('.pom')),
    'pinned dependency exporter plugin POM required');
  const evidenceRoot = join(root, workspace, 'task-68-evidence');
  const inventory = {workspaceRoot: root, head, files: observed, requiredLeaves,
    runRoot: join(evidenceRoot, gate.phase + '-' + gate.uuid),
    usedUuid: ['prepare', 'green'].some(phase => existsSync(join(evidenceRoot, phase + '-' + gate.uuid))),
    imageId: gate.native?.imageId, imageDigest: gate.native?.imageDigest};
  let runtime, goldens;
  if (gate.stage !== 'materialize-runtime') {
    runtime = readAccepted(gate.runtimeManifest, 'runtime', gate);
    assert.equal(runtime.sourceContracts.driverTemplatesSha256, hash(JSON.stringify(task68DriverSources())));
    assert.equal(runtime.head, gate.head); assert.deepEqual(runtime.driverClasspath, classpath.javaClasspath);
    assert.equal(runtime.oldBaseline.commit, oldBaseline);
    assert.equal(realpathSync(runtime.oldRoot), runtime.oldRoot);
    assert(runtime.oldRoot.includes('/task-68-evidence/prepare-') && runtime.oldRoot.endsWith('/materials/old-source'));
    oldClasspathPlan(runtime.oldClasspath, runtime.oldRoot);
    assert.deepEqual(runtime.expectedOriginMap, expectedOldOrigins(runtime.oldClasspath, runtime.oldRoot));
    assert.equal(readFileSync(runtime.expectedOldOrigins, 'utf8'), originProperties(runtime.expectedOriginMap),
      'an observed attestation cannot replace the statically accepted expected-origin map');
    for (const path of runtime.oldClasspath) {
      assert.equal(realpathSync(path), path);
      if (statSync(path).isDirectory()) assert(beneath(path, runtime.oldRoot) && /\/target\/(?:test-)?classes$/.test(path),
        'accepted old runtime directory cannot be current/Task67');
      else assert(beneath(path, toolCache) && path.endsWith('.jar') && !/\/(?:shop|beauty|food)-[^/]+\.jar$/.test(path));
    }
    const oldDefinitions = new Map();
    for (const path of runtime.oldClasspath) for (const name of classpathDefinitions(path)) {
      if (!name.startsWith('ai/neargo/shop/') || !name.endsWith('.class')) continue;
      assert(!oldDefinitions.has(name), 'duplicate old common owner/provider class'); oldDefinitions.set(name, path);
    }
    for (const name of oldOriginClasses) assert(beneath(oldDefinitions.get(name.replaceAll('.', '/') + '.class') ?? '', runtime.oldRoot));
    inventory.runtimeAccepted = true;
    if (gate.stage === 'counter-old-pending') {
      assert.equal(gate.expectedOldOrigins, runtime.expectedOldOrigins);
      assert.deepEqual(pinnedFile(gate.expectedOldOrigins), runtime.files.find(pin => pin.path === gate.expectedOldOrigins));
      inventory.expectedOriginAccepted = true;
    }
  }
  if (['materialize-goldens', 'counter-protocol'].includes(gate.stage)) {
    const archive = verifyArchiveInputs(gate);
    Object.assign(inventory, {archiveEntryCount: archive.files.length,
      archiveSourceCommit: archive.baselineSourceCommit, archiveDocCommit: archive.baselineDocCommit});
  }
  if (gate.stage === 'counter-protocol') {
    goldens = readAccepted(gate.goldenManifest, 'goldens', gate);
    assert.equal(goldens.runtimeManifestSha256, pinnedFile(gate.runtimeManifest).sha256);
    assert.deepEqual(gate.goldenPins, goldens.goldens);
    inventory.goldensAccepted = true;
    for (const name of goldenNames) assert(!Object.hasOwn(classpath.definitions, 'counter-economics/' + name),
      'preexisting fixture would conflict with private publication');
  }
  validateM2cGate(gate, inventory);
  const inputs = {runtime, goldens, classpath, inventory};
  verifyRuntimeClasspaths(inputs, gate.files);
  return inputs;
}

const oldOriginClasses = Object.freeze([
  'ai.neargo.shop.ShopApplication', 'ai.neargo.shop.trade.service.impl.CheckoutServiceImpl',
  'ai.neargo.shop.trade.service.impl.OrderServiceImpl',
  'ai.neargo.shop.trade.order.service.create.impl.CounterOrderPlacementServiceImpl',
  'ai.neargo.shop.trade.port.ManualServiceAdmissionPortImpl', 'ai.neargo.shop.product.port.StockPortImpl',
  'ai.neargo.shop.invbridge.port.OriginalStockDispositionPort', 'ai.neargo.shop.scenario.CheckoutFlowFixture',
  'ai.neargo.shop.scenario.CheckoutFlowFixture$ClockConfig'
]);

/** Strict git tar extraction checks the accepted complete tree before writing a single member. */
function extractOldArchive(archive, destination, manifest) {
  assert.equal(manifest.commit, oldBaseline);
  const entries = [], data = readFileSync(archive);
  let extendedPath;
  for (let offset = 0; offset + 512 <= data.length;) {
    const header = data.subarray(offset, offset + 512);
    if (header.every(byte => byte === 0)) break;
    const field = (start, count) => header.subarray(start, start + count).toString('utf8').replace(/\0.*$/s, '');
    const length = parseInt(field(124, 12).trim() || '0', 8), type = field(156, 1) || '0';
    let name = (field(345, 155) ? field(345, 155) + '/' : '') + field(0, 100);
    assert(Number.isSafeInteger(length) && length >= 0 && offset + 512 + length <= data.length);
    const bytes = data.subarray(offset + 512, offset + 512 + length);
    offset += 512 + Math.ceil(length / 512) * 512;
    if (type === 'g') { assert(bytes.toString('utf8').includes('comment=' + oldBaseline)); continue; }
    if (type === 'x') {
      let index = 0; const attributes = {};
      while (index < bytes.length) {
        const space = bytes.indexOf(32, index); assert(space > index);
        const size = Number(bytes.subarray(index, space).toString('ascii'));
        assert(Number.isSafeInteger(size) && size > 0 && index + size <= bytes.length);
        const record = bytes.subarray(space + 1, index + size - 1).toString('utf8');
        const equals = record.indexOf('='); assert(equals > 0);
        const key = record.slice(0, equals); assert(!Object.hasOwn(attributes, key));
        assert(['path', 'mtime', 'atime', 'ctime'].includes(key), 'unreviewed archive PAX attribute');
        attributes[key] = record.slice(equals + 1); index += size;
      }
      extendedPath = attributes.path; continue;
    }
    if (extendedPath) { name = extendedPath; extendedPath = undefined; }
    assert(['0', '5'].includes(type), 'symlink/hardlink/PAX member is not an approved tracked file');
    assert(ownedRelative(name.replace(/\/$/, '')) && !name.split('/').includes('target')
      && !name.split('/').includes('.git'), 'unsafe/prebuilt archive member');
    if (type === '5') continue;
    const blob = createHash('sha1').update('blob ' + bytes.length + '\0').update(bytes).digest('hex');
    entries.push({path: name, bytes: bytes.length, sha256: hash(bytes), gitBlob: blob,
      mode: parseInt(field(100, 8).trim(), 8) & 0o777, content: bytes});
  }
  const actual = entries.map(({content, ...entry}) => entry).sort((a, b) => a.path < b.path ? -1 : a.path > b.path ? 1 : 0);
  assert.deepEqual(actual, manifest.files, 'archive must exactly match pinned old tracked-tree manifest');
  assert(!existsSync(destination)); mkdirSync(destination, {mode: 0o700});
  for (const entry of entries) {
    const path = join(destination, entry.path); privateWrite(path, entry.content);
    // Retain executable source scripts without granting access to other users.
    if (entry.mode & 0o111) chmodSync(path, 0o700);
  }
}

export function exportedDependencyClasspath(log, sourceRoot, current = false) {
  const clean = log.replace(/\u001b\[[0-9;]*m/g, '');
  assert(/BUILD SUCCESS/.test(clean) && !/BUILD FAILURE|\[ERROR\]/.test(clean), 'dependency exporter/plugin failed');
  const header = '--- dependency:3.7.0:build-classpath (default-cli) @ shop-app ---';
  const lines = clean.split(/\r?\n/).map(line => line.replace(/^\[INFO\] ?/, '').trim());
  const starts = lines.flatMap((line, index) => line === header ? [index] : []);
  assert.equal(starts.length, 1, 'unique exact shop-app dependency exporter section required');
  const section = lines.slice(starts[0] + 1);
  const next = section.findIndex(line => line.startsWith('--- ') || line.startsWith('BUILD '));
  const own = next < 0 ? section : section.slice(0, next);
  const markers = own.flatMap((line, index) => line === 'Dependencies classpath:' ? [index] : []);
  assert.equal(markers.length, 1, 'unique own dependency classpath marker required');
  const value = own[markers[0] + 1];
  assert(typeof value === 'string' && value.length > 0 && !value.includes('*'));
  const dependencies = value.split(':');
  assert(dependencies.every(path => path.startsWith('/') && path.length > 1), 'empty/relative dependency entry');
  const prefix = [join(sourceRoot, 'backend/shop-app/target/test-classes'),
    join(sourceRoot, 'backend/shop-app/target/classes'),
    ...(current ? [join(sourceRoot, 'backend/shop-core/target/test-classes')] : [])];
  const paths = [...prefix, ...dependencies];
  assert.equal(new Set(paths).size, paths.length, 'duplicate output/dependency root');
  return paths;
}

export function oldClasspathPlan(paths, oldRoot, cacheRoot = toolCache) {
  assert(oldRoot?.startsWith('/') && oldRoot.includes('/task-68-evidence/prepare-')
    && oldRoot.endsWith('/materials/old-source'), 'genuine retained Task68 old source root required');
  assert(Array.isArray(paths) && paths.length >= 2 && new Set(paths).size === paths.length);
  assert.deepEqual(paths.slice(0, 2), [join(oldRoot, 'backend/shop-app/target/test-classes'),
    join(oldRoot, 'backend/shop-app/target/classes')]);
  for (const path of paths) {
    assert(typeof path === 'string' && path.startsWith('/') && !path.includes('*'));
    if (beneath(path, oldRoot)) assert(/\/target\/(?:test-)?classes$/.test(path));
    else assert(beneath(path, cacheRoot) && path.endsWith('.jar')
      && !/\/(?:shop|beauty|food)-[^/]+\.jar$/.test(path), 'current/Task67/installed provider is not a genuine old runtime');
  }
  return [...paths];
}

function expectedOldOrigins(classpath, oldRoot) {
  const expected = {baselineCommit: oldBaseline, baselineRoot: realpathSync(oldRoot)};
  for (const name of oldOriginClasses) {
    const classRelativePath = name.replaceAll('.', '/') + '.class';
    const candidates = classpath.filter(path => statSync(path).isDirectory() && existsSync(join(path, classRelativePath)));
    assert.equal(candidates.length, 1, 'unique genuine old owner/fixture class required: ' + name);
    const directory = candidates[0]; assert(beneath(directory, oldRoot), 'expected old owner must be archived build');
    const pin = pinnedFile(join(directory, classRelativePath));
    Object.assign(expected, {[name + '.codeSourceUri']: pathToFileURL(directory + '/').href,
      [name + '.codeSourcePath']: canonicalLocalFileIdentity(pathToFileURL(directory + '/').href, oldRoot),
      [name + '.classRelativePath']: classRelativePath, [name + '.bytes']: String(pin.bytes), [name + '.sha256']: pin.sha256});
  }
  return expected;
}
const originProperties = expected => Object.keys(expected).sort().map(key => key + '=' + expected[key]).join('\n') + '\n';

function captureOldClasspath(log, oldRoot, dependencies) {
  const paths = exportedDependencyClasspath(log, oldRoot).map(path => realpathSync(path));
  assert.equal(new Set(paths).size, paths.length, 'canonical duplicate old classpath root');
  oldClasspathPlan(paths, oldRoot);
  const dependencyPins = new Map(dependencies.files.map(pin => [pin.path, pin]));
  for (const path of paths) {
    if (beneath(path, oldRoot)) assert(/\/target\/(?:test-)?classes$/.test(path));
    else {
      assert(path.endsWith('.jar') && beneath(path, realpathSync(toolCache)), 'old dependency escaped frozen cache');
      assert(!/\/(?:shop|beauty|food)-[^/]+\.jar$/.test(path), 'installed owner cannot be old source');
      assert.deepEqual(pinnedFile(path), dependencyPins.get(path), 'unapproved old dependency jar');
    }
  }
  return paths;
}

async function materializeRuntime(state) {
  const {gate, evidence, scratch, inputs, execute} = state;
  const materials = join(evidence, 'materials'); mkdirSync(materials, {mode: 0o700});
  const tree = jsonFile(gate.oldBaseline.treeManifest);
  assert.equal(tree.tree, gate.oldBaseline.tree); assert.equal(tree.commit, oldBaseline);
  const oldDependencies = jsonFile(gate.oldBaseline.dependenciesManifest);
  requirePinnedLeaves(oldDependencies.files, gate.files);
  const actualTree = (await execute('old-tree', gate.tools.git, ['rev-parse', oldBaseline + '^{tree}'], 30)).trim();
  assert.equal(actualTree, gate.oldBaseline.tree);
  const archive = join(materials, 'old-tree.tar'), oldRoot = join(materials, 'old-source');
  await execute('old-archive', gate.tools.git, ['archive', '--format=tar', '--output=' + archive, oldBaseline], 60);
  chmodSync(archive, 0o600); extractOldArchive(archive, oldRoot, tree);
  const sourcePins = runtimeClasspathFiles([oldRoot]).map(pinnedFile);
  const command = ['-o', '-Dmaven.repo.local=' + toolCache, '-Dtask68.uuid=' + gate.uuid,
    '-f', join(oldRoot, 'backend/pom.xml'), '-pl', ':shop-app', '-am', 'test-compile', '-DskipTests',
    'org.apache.maven.plugins:maven-dependency-plugin:3.7.0:build-classpath', '-DincludeScope=test'];
  // No current compile, install, online fallback or classpath inference is permitted.
  const output = await execute('old-offline-build-classpath', gate.tools.maven, command, 1200,
    {cwd: oldRoot});
  const oldClasspath = captureOldClasspath(output, oldRoot, oldDependencies);
  const oldBuildPins = oldClasspath.filter(path => beneath(path, oldRoot)).flatMap(strictClasspathLeaves).map(pinnedFile);
  registerDerivedClasspath(state, 'old-build', oldClasspath, oldBuildPins);
  for (const pin of sourcePins) assert.deepEqual(pinnedFile(pin.path), pin, 'old build changed archived source');
  const empty = join(scratch, 'empty-sourcepath'); mkdirSync(empty);
  const sources = task68DriverSources();
  const currentSources = join(materials, 'current-driver-sources');
  const currentClasses = join(materials, 'current-driver-classes'); mkdirSync(currentClasses);
  const rendered = [];
  for (const name of ['Task68GoldenMain', 'Task68SelectedMain']) {
    const path = join(currentSources, 'ai/neargo/shop/scenario', name + '.java');
    privateWrite(path, sources[name]); rendered.push(path);
  }
  const javac = (classpath, classes, files) => ['--release', '21', '-proc:none', '-sourcepath', empty,
    '-classpath', classpath.join(sep === '/' ? ':' : ';'), '-d', classes, ...files];
  await execute('current-driver-javac', gate.tools.javac,
    javac(inputs.classpath.javacClasspath, currentClasses, rendered), 120);
  registerDerivedClasspath(state, 'current-driver', [currentClasses, ...inputs.classpath.javaClasspath],
    strictClasspathLeaves(currentClasses).map(pinnedFile));
  const overlaySources = join(materials, 'old-producer-sources');
  const overlayClasses = join(materials, 'old-producer-classes'); mkdirSync(overlayClasses);
  const fixture = join(root, 'backend/shop-app/src/test/java/ai/neargo/shop/scenario/ManualCounterDiscountFixture.java');
  assert.equal(pinnedFile(fixture).sha256, 'c99b03a79d3bd01eaa02b02baaa84206550e3421b826b6d1117802598767cd21');
  const fixtureCopy = join(overlaySources, 'ai/neargo/shop/scenario/ManualCounterDiscountFixture.java');
  privateWrite(fixtureCopy, readFileSync(fixture));
  const shim = join(overlaySources, 'ai/neargo/shop/scenario/Task68OldMain.java'); privateWrite(shim, sources.Task68OldMain);
  await execute('old-overlay-javac', gate.tools.javac, javac(oldClasspath, overlayClasses, [fixtureCopy, shim]), 120);
  registerDerivedClasspath(state, 'old-producer', [overlayClasses, ...oldClasspath],
    strictClasspathLeaves(overlayClasses).map(pinnedFile));
  const expected = expectedOldOrigins(oldClasspath, oldRoot);
  const properties = join(materials, 'expected-old-origins.properties');
  privateWrite(properties, originProperties(expected));
  for (const directory of [oldRoot, overlaySources, overlayClasses, currentSources, currentClasses])
    for (const file of runtimeClasspathFiles([directory])) chmodSync(file, 0o600);
  const leaves = [...gate.files.map(corePin), ...runtimeClasspathFiles([oldRoot, overlaySources, overlayClasses, currentSources, currentClasses,
    properties, archive, ...oldClasspath, ...inputs.classpath.javaClasspath]).map(pinnedFile)];
  privateWrite(join(materials, 'runtime-manifest.json'), {schemaVersion: 1, kind: 'runtime', uuid: gate.uuid,
    outcome: 'MATERIALIZED_PENDING_ROOT_ACCEPTANCE', testOutcome: 'NOT_RUN', head: gate.head,
    oldBaseline: {commit: oldBaseline, tree: tree.tree, treeManifest: pinnedFile(gate.oldBaseline.treeManifest),
      archive: pinnedFile(archive), sourcePins, buildCommand: command, dependencyManifest: pinnedFile(gate.oldBaseline.dependenciesManifest)},
    currentDriverClasses: currentClasses, currentDriverSources: currentSources,
    effectiveClasspaths: {current: [currentClasses, ...inputs.classpath.javaClasspath],
      old: [overlayClasses, ...oldClasspath]},
    driverClasspath: inputs.classpath.javaClasspath, runnerToolSuffix: inputs.classpath.runnerToolSuffix,
    oldRoot, oldClasspath, oldProducerClasses: overlayClasses, expectedOldOrigins: properties,
    expectedOriginMap: expected, sourceContracts: {driverTemplatesSha256: hash(JSON.stringify(task68DriverSources())),
      classpathRuling: pinnedFile(join(root, workspace, 'task-68c-classpath-ruling.md'))}, originObservation: 'NOT_LOADED_DURING_PREPARATION',
    files: [...new Map(leaves.map(pin => [pin.path, pin])).values()].sort((a, b) => a.path < b.path ? -1 : a.path > b.path ? 1 : 0)});
}

async function nativeM2c(state) {
  const {gate, execute, ownership, evidence, environment} = state;
  const docker = async (tag, args, timeout = 30, extra = {}) => execute(tag, gate.tools.docker,
    ['--context', 'colima-hxkey-tests', ...args], timeout, extra);
  const image = JSON.parse(await docker('image-inspect', ['image', 'inspect', 'mysql:9.7.2']));
  assert.equal(image.length, 1); assert.equal(image[0].Id, gate.native.imageId);
  assert(image[0].RepoDigests?.includes(gate.native.imageDigest), 'offline image digest drift');
  assert.equal((await docker('owned-name-absence', ['ps', '-aq', '--filter', 'name=^/' + ownership.name + '$'])).trim(), '');
  state.containerAttempted = true;
  await docker('owned-mysql-start', ['run', '--detach', '--pull=never', '--name', ownership.name,
    '--label', ownership.label, '--publish', '127.0.0.1::3306', '--memory', '768m', '--pids-limit', '256',
    '--cpus', '1', '--env', 'MYSQL_ROOT_PASSWORD', '--env', 'MYSQL_ROOT_HOST=%', gate.native.imageId,
    '--datadir=/var/lib/counter-test', '--innodb-buffer-pool-size=64M', '--performance-schema=OFF']);
  const mysql = async (tag, input, schema, timeout = 45) => docker(tag,
    ['exec', '-i', '--env', 'MYSQL_PWD', ownership.name, 'mysql', '--protocol=TCP', '-h127.0.0.1',
      '-uroot', '--batch', '--raw', '--skip-column-names', ...(schema ? [schema] : [])], timeout, {input});
  const readyBy = Date.now() + 90000; let ready = false;
  while (Date.now() < readyBy) {
    assert(!state.interrupted, 'Task68 interrupted during readiness');
    try {
      await mysql('readiness-' + (++state.readinessAttempts), 'SELECT 1;', null, 3);
      assert(Date.now() <= readyBy, 'readiness result arrived after the approved 90 second deadline');
      ready = true; break;
    }
    catch (failure) { if (state.interrupted) throw failure; await pause(250); }
  }
  assert(ready, 'owned MySQL readiness exceeded 90s');
  const address = (await docker('owned-mysql-port', ['port', ownership.name, '3306/tcp'])).trim();
  assert.match(address, /^127\.0\.0\.1:\d+$/);
  const compact = gate.uuid.replaceAll('-', ''), schemas = {platform: 'counter_' + compact, inventory: 'counter_inv_' + compact};
  await mysql('schema-create', Object.values(schemas).map(name =>
    'CREATE DATABASE ' + name + ' CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;').join('\n'));
  const schemaEvidence = [];
  for (const step of m2cSchemaPlan().steps) {
    if (step.path) {
      const result = await mysql('schema-' + step.key, readFileSync(join(root, step.path)), schemas[step.schema]);
      schemaEvidence.push({key: step.key, schema: step.schema, source: pinnedFile(join(root, step.path)), result});
    } else if (step.key === 'require-seven-owner-tables') {
      const rows = (await mysql('schema-seven-owners', "SELECT table_name FROM information_schema.tables WHERE table_schema='"
        + schemas.platform + "' AND table_name IN (" + m2cTables.map(name => "'" + name + "'").join(',') + ') ORDER BY table_name;'))
        .trim().split('\n');
      assert.deepEqual(rows, [...m2cTables].sort(), 'all seven participating tables required before V543');
      schemaEvidence.push({key: step.key, tables: rows});
    } else {
      const ownerRows = (await mysql('schema-ten-effective-bindings', 'SELECT table_name,column_name,character_set_name,collation_name'
        + " FROM information_schema.columns WHERE table_schema='" + schemas.platform + "' AND ("
        + m2cBindings.map(([table, column]) => "(table_name='" + table + "' AND column_name='" + column + "')").join(' OR ')
        + ') ORDER BY table_name,column_name;')).trim().split('\n').map(row => row.split('\t'));
      assert.deepEqual(ownerRows.map(row => row.slice(0, 2)), [...m2cBindings].sort((a, b) => a.join('.').localeCompare(b.join('.'))));
      assert(ownerRows.every(row => row[2] === 'utf8mb4' && row[3] === 'utf8mb4_unicode_ci'),
        'effective owner-column collation differs from the owned native fixture');
      const triggerRows = (await mysql('schema-trigger-count', "SELECT trigger_name FROM information_schema.triggers WHERE trigger_schema='"
        + schemas.platform + "' AND trigger_name LIKE 'trg_ccf_%' ORDER BY trigger_name;"))
        .trim().split('\n');
      assert.equal(triggerRows.length, 21);
      const structure = await mysql('schema-effective-structure', 'SHOW CREATE DATABASE ' + schemas.platform + ';\n'
        + ['mkt_counter_campaign_fence', ...m2cTables].map(table => 'SHOW CREATE TABLE ' + table + ';').join('\n')
        + '\nSHOW TRIGGERS;', schemas.platform);
      schemaEvidence.push({key: step.key, ownerBindings: ownerRows, triggers: triggerRows, structure});
    }
  }
  const jdbc = name => 'jdbc:mysql://' + address + '/' + name
    + '?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC';
  Object.assign(environment, {CHECKOUT_NATIVE_MYSQL_URL: jdbc(schemas.platform), CHECKOUT_NATIVE_MYSQL_USER: 'root',
    CHECKOUT_NATIVE_MYSQL_PASSWORD: state.password, SPRING_DATASOURCE_URL: jdbc(schemas.platform),
    SPRING_DATASOURCE_USERNAME: 'root', SPRING_DATASOURCE_PASSWORD: state.password,
    SPRING_DATASOURCE_DRIVER_CLASS_NAME: 'com.mysql.cj.jdbc.Driver',
    SPRING_SQL_INIT_MODE: 'never', SPRING_FLYWAY_ENABLED: 'false',
    MANUAL_INVENTORY_MYSQL_URL: jdbc(schemas.inventory), MANUAL_SERVICE_TEST_PASSWORD: state.password,
    SHOP_INVENTORY_ENABLED: 'false', SHOP_INVENTORY_STOCK_AUTHORITY: 'PLATFORM', SERVER_ADDRESS: '127.0.0.1', SERVER_PORT: '0',
    SPRING_PROFILES_ACTIVE: 'test', BEAUTY_ENABLED: 'false', FOOD_ENABLED: 'false'});
  state.database = jdbc(schemas.platform);
  privateWrite(join(evidence, 'schema.json'), {schemaTopology: gate.schemaTopology, stockAuthority: 'PLATFORM',
    inactiveInventoryFixture: true, reducedStructuralFixture: true, fullHistoryMigration: false,
    authoritativeCapabilityCheck: 'CounterCampaignFence.validateSchema', schemas, properties: gate.properties,
    datasourceProduct: 'MySQL', datasourceUrl: state.database, steps: schemaEvidence});
}

function javaArgs(state, classes, classpath, main, args, extra = []) {
  return [...m2cJvmProperties, '-Dtask68.uuid=' + state.gate.uuid, '-Djava.io.tmpdir=' + join(state.scratch, 'jvm-tmp'), ...extra,
    '-cp', [classes, ...classpath].join(':'), 'ai.neargo.shop.scenario.' + main, ...args];
}

async function materializeGoldens(state) {
  const {evidence, inputs, gate, execute} = state, runtime = inputs.runtime;
  const output = join(evidence, 'materials/goldens'); mkdirSync(dirname(output), {mode: 0o700});
  const log = await execute('golden-real-mappers', gate.tools.java, javaArgs(state,
    runtime.currentDriverClasses, runtime.driverClasspath, 'Task68GoldenMain', [dirname(join(root, m2cArchiveManifest)), output]), 120);
  assert.deepEqual(readdirSync(output).sort(), [...goldenNames].sort(), 'exactly six real golden outputs required');
  const mapperLines = [...log.matchAll(/^TASK68_MAPPERS=(.+)$/gm)]; assert.equal(mapperLines.length, 1);
  const mappers = JSON.parse(mapperLines[0][1]);
  assert(typeof mappers.sameIdentity === 'boolean' && mappers.applicationClass && mappers.legacyClass && mappers.ownerOrigin);
  const goldens = goldenNames.map(name => { chmodSync(join(output, name), 0o600); return {name, ...pinnedFile(join(output, name))}; });
  privateWrite(join(evidence, 'materials/golden-manifest.json'), {schemaVersion: 1, kind: 'goldens', uuid: gate.uuid,
    head: gate.head, outcome: 'MATERIALIZED_PENDING_ROOT_ACCEPTANCE', testOutcome: 'NOT_RUN',
    runtimeManifestSha256: pinnedFile(gate.runtimeManifest).sha256,
    archiveManifest: pinnedFile(join(root, m2cArchiveManifest)), mappers, goldens,
    files: goldens.map(({name, ...pin}) => pin)});
}

function pendingEvidence(state, suffix, kind, path, originPath) {
  const inputPath = join(path, 'input.json'), pendingPath = join(path, 'old-pending.json');
  assert.deepEqual(readdirSync(path).sort(), ['input.json', 'old-pending.json']);
  const input = jsonFile(inputPath), pending = jsonFile(pendingPath);
  const origin = readFileSync(originPath, 'utf8');
  assert(/^matched=true\s*$/m.test(origin), 'old shim did not attest matched origins');
  assert.equal(pending.baselineCommit, oldBaseline);
  assert.equal(realpathSync(pending.ownerCodeOrigin), join(state.inputs.runtime.oldRoot, 'backend/shop-core/target/classes'));
  assert.deepEqual(pending.scope, input.scope); assert.deepEqual(pending.prepare, input.prepare);
  assert.equal(input.prepare.customer.kind, kind); assert.equal(pending.quote.party.kind, kind);
  assert.equal(pending.quote.quote.entityNo, input.scope.entityNo); assert.equal(pending.quote.quote.storeNo, input.scope.storeNo);
  assert.deepEqual(pending.quote.quote.operation, input.prepare.operation);
  const quote = pending.quote?.quote, confirmation = pending.confirmation;
  assert(quote && confirmation && pending.intent && pending.confirmationRow && pending.place);
  assert.equal(confirmation.purchaseAuthority, 'MERCHANT_RECORDED_IN_PERSON');
  assert.equal(confirmation.quoteHash, quote.quoteHash); assert.equal(confirmation.quoteNo, quote.quoteNo);
  assert.deepEqual(confirmation.party, pending.quote.party);
  assert.deepEqual(pending.place.operation, confirmation.operation);
  assert.equal(pending.place.confirmationNo, confirmation.confirmationNo);
  assert.equal(pending.reservationObservation?.outcome, 'RESERVED');
  assert(pending.reservationObservation.lines?.length > 0 && /^[a-f0-9]{64}$/.test(pending.reservationObservation.observationHash));
  const pricing = JSON.parse(pending.pricingJson), intent = pending.intent;
  assert(!Object.hasOwn(pricing, 'economic') && !Object.hasOwn(pricing, 'counterActivitySeal'));
  assert.equal(pricing.platformMinor, 0); assert.equal(pricing.merchantMinor, 0);
  assert.equal(pricing.discountMinor, 0); assert.equal(pricing.dueMinor, pricing.goodsMinor);
  assert.equal(quote.discountMinor, 0); assert.equal(quote.dueMinor, quote.goodsMinor);
  assert.equal(intent.operation_no, confirmation.operation.operationNo);
  assert(/^[a-f0-9]{64}$/.test(intent.plan_hash ?? ''));
  assert(typeof intent.counter_plan_json === 'string' && intent.counter_plan_json.length > 0);
  const originalPlan = JSON.parse(intent.counter_plan_json);
  assert(!Object.hasOwn(originalPlan, 'schemaVersion') && !Object.hasOwn(originalPlan, 'counterActivitySeal'));
  assert.equal(pending.confirmationRow.quote_hash, quote.quoteHash);
  // No quote, plan, confirmation, hash or row is rewritten by the runner.
  const quoteExpiryMs = Date.parse(quote.expiresAt), confirmationExpiryMs = Date.parse(confirmation.expiresAt);
  assert(Number.isFinite(quoteExpiryMs) && Number.isFinite(confirmationExpiryMs));
  for (const file of [inputPath, pendingPath, originPath]) chmodSync(file, 0o600);
  return {suffix, kind, baselineCommit: oldBaseline, inputPath, pendingPath, originPath,
    originMatched: true, reservationObserved: true, zeroEconomics: true, database: state.database,
    quoteExpiryMs, confirmationExpiryMs, originalPins: [inputPath, pendingPath, originPath].map(pinnedFile)};
}

async function runSelected(state, pending = []) {
  const {gate, inputs, evidence, execute, scratch} = state, runtime = inputs.runtime;
  let classpath = runtime.driverClasspath;
  const extra = [];
  if (gate.stage === 'counter-protocol') {
    const publication = resourcePublicationPlan(gate.goldenPins, evidence);
    state.disposableResources = publication.classpathRoot;
    for (const copy of publication.copies) {
      const original = readFileSync(copy.source);
      assert.equal(original.length, copy.bytes); assert.equal(hash(original), copy.sha256);
      privateWrite(copy.destination, original);
      assert.deepEqual(readFileSync(copy.destination), original);
    }
    state.disposableResources = publication.classpathRoot;
    classpath = [publication.classpathRoot, ...classpath]; extra.push('-Dtask68.resources=' + publication.classpathRoot);
    registerDerivedClasspath(state, 'protocol-resources', [runtime.currentDriverClasses, ...classpath],
      publication.copies.map(copy => ({path: copy.destination, bytes: copy.bytes, sha256: copy.sha256})));
    privateWrite(join(evidence, 'resource-publication.json'), publication);
  }
  const selectionPath = join(scratch, 'selection.json'); privateWrite(selectionPath, gate.selection);
  let timeout = 600;
  if (gate.stage === 'counter-old-pending') {
    const expiry = validateOldPendingBatch(pending, Date.now()); state.pendingExpiry = expiry; state.minimumPendingRemaining = 225000;
    timeout = Math.min(timeout, (expiry - Date.now()) / 1000);
    for (const entry of pending) for (const pin of entry.originalPins) assert.deepEqual(pinnedFile(pin.path), pin);
    state.beforeEachProcess = () => {
      assert(Date.now() < expiry, 'original expiry reached');
      for (const entry of pending) for (const pin of entry.originalPins) assert.deepEqual(pinnedFile(pin.path), pin);
    };
  }
  const reports = join(evidence, 'reports');
  let processFailure;
  try { await execute('selected-junit', gate.tools.java, javaArgs(state, runtime.currentDriverClasses,
    classpath, 'Task68SelectedMain', [selectionPath, reports], extra), timeout); }
  catch (failure) { processFailure = failure; }
  // Preserve/read every class report before evaluating any one class.
  const captures = gate.selection.map(([, cls, methods]) => {
    const path = join(reports, 'TEST-' + cls + '.xml');
    return {cls, methods, xml: existsSync(path) ? readFileSync(path, 'utf8') : null};
  });
  privateWrite(join(evidence, 'report-inventory.json'), captures.map(({cls, xml}) => ({cls, present: xml !== null})));
  if (processFailure) throw processFailure;
  const events = jsonFile(join(reports, 'events.json'));
  const count = validateSelectedEvents(gate.selection, events);
  assert.equal(events.summaryFound, count); assert.equal(events.summarySucceeded, count);
  assert.equal(events.summaryContainerFailures, 0);
  const summaries = captures.map(({cls, methods, xml}) => ({class: cls, ...inspectReport(xml ?? '', methods, false)}));
  privateWrite(join(evidence, 'junit-summary.json'), {count, summaries, testOutcome: 'PASS'});
}

async function produceOldAndTest(state) {
  const {evidence, inputs, gate, execute, environment} = state, runtime = inputs.runtime;
  mkdirSync(join(evidence, 'pending'), {mode: 0o700}); mkdirSync(join(evidence, 'origins'), {mode: 0o700});
  const entries = [], timestamps = [];
  for (const [suffix, kind] of pendingKinds) {
    for (const entry of entries) assert(Math.min(entry.quoteExpiryMs, entry.confirmationExpiryMs) - Date.now() >= 225000,
      'stop producing: an existing original has insufficient 225 second recovery budget');
    const path = join(evidence, 'pending', suffix), origin = join(evidence, 'origins', suffix + '.properties');
    const startedAt = new Date().toISOString(), startedMonotonicNs = process.hrtime.bigint().toString();
    const freshnessDeadline = entries.length ? Math.min(...entries.flatMap(e => [e.quoteExpiryMs, e.confirmationExpiryMs])) - 225000 : Date.now() + 75000;
    state.producerDeadline = freshnessDeadline;
    await execute('old-producer-' + suffix, gate.tools.java, javaArgs(state, runtime.oldProducerClasses,
      runtime.oldClasspath, 'Task68OldMain', [gate.expectedOldOrigins, origin, kind, path],
      ['-Dtask68.old.classes=' + join(runtime.oldRoot, 'backend/shop-core/target/classes')]),
    Math.min(75, Math.max(0.001, (freshnessDeadline - Date.now()) / 1000)));
    state.producerDeadline = undefined;
    const entry = pendingEvidence(state, suffix, kind, path, origin); entries.push(entry);
    timestamps.push({suffix, startedAt, startedMonotonicNs, endedAt: new Date().toISOString(),
      endedMonotonicNs: process.hrtime.bigint().toString(), quoteExpiresAt: new Date(entry.quoteExpiryMs).toISOString(),
      confirmationExpiresAt: new Date(entry.confirmationExpiryMs).toISOString()});
    environment['TASK68_OLD_PENDING_' + suffix] = entry.pendingPath;
    // Partial batch evidence survives any later producer failure.
    writeFileSync(join(evidence, 'pending-batch.json'), JSON.stringify({entries, timestamps}, null, 2), {mode: 0o600});
  }
  const earliestExpiry = validateOldPendingBatch(entries, Date.now());
  privateWrite(join(evidence, 'pending-current-boundary.json'), {earliestExpiry, remainingMs: earliestExpiry - Date.now(),
    startedAt: new Date().toISOString(), startedMonotonicNs: process.hrtime.bigint().toString(), budgetMs: 225000});
  await runSelected(state, entries);
  for (const entry of entries) for (const pin of entry.originalPins) assert.deepEqual(pinnedFile(pin.path), pin);
}

/** All execution below is reachable only through an approved, consumed Task68 UUID. */
async function runM2c(phase) {
  const gatePath = join(root, m2cGatePath(phase)), gate = jsonFile(gatePath);
  assert.equal(gate.phase, phase); assert.equal(gate.slice, 'm2c');
  assert.equal(gate.approved, true); assert(uuid4(gate.uuid));
  assert(m2cStages[gate.stage] && m2cStages[gate.stage][0] === phase, 'finite stage rejected before mutation');
  if (phase === 'prepare') materializationPlan('m2c', phase, gate.stage);
  else phaseSelection('m2c', phase, gate.stage);
  const head = execFileSync('git', ['rev-parse', 'HEAD'], {cwd: root, encoding: 'utf8', timeout: 3000}).trim();
  const inputs = m2cInputs(gate, head), ownership = m2cOwnershipPlan(gate.uuid);
  const parent = join(root, workspace, 'task-68-evidence'); mkdirSync(parent, {recursive: true, mode: 0o700});
  assert.equal(realpathSync(parent), parent, 'evidence root cannot escape through a symlink');
  const evidence = inputs.inventory.runRoot;
  // Exclusive mkdir is the one-use receipt. No lock/container/temp exists before this succeeds.
  mkdirSync(evidence, {mode: 0o700});
  privateWrite(join(evidence, 'root-gate.json'), readFileSync(gatePath));
  privateWrite(join(evidence, 'receipt.json'), {uuid: gate.uuid, pid: process.pid, gate: pinnedFile(gatePath),
    consumedAt: new Date().toISOString(), phase, stage: gate.stage, action: gate.action,
    head, sourceInputs: gate.files, testOutcome: 'NOT_RUN'});
  const password = randomBytes(24).toString('base64url');
  const environment = {...process.env, TASK68_UUID: gate.uuid,
    JAVA_HOME: dirname(dirname(gate.tools.java)), MYSQL_ROOT_PASSWORD: password, MYSQL_PWD: password};
  // Ambient injection cannot alter the approved Java/classpath/properties or Maven offline closure.
  for (const name of Object.keys(environment)) if (/^(SPRING_|SHOP_|CHECKOUT_|MANUAL_|BEAUTY_|FOOD_)/.test(name)) delete environment[name];
  for (const name of ['JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'CLASSPATH', 'MAVEN_OPTS',
    'MAVEN_ARGS', 'TASK68_OLD_PENDING_USER', 'TASK68_OLD_PENDING_GUEST', 'TASK68_OLD_PENDING_BASE_CHANGE',
    'TASK68_OLD_PENDING_CATALOG_CHANGE']) delete environment[name];
  environment.PATH = dirname(gate.tools.java) + ':' + (process.env.PATH ?? '/usr/bin:/bin');
  const safe = value => String(value).replaceAll(password, '[REDACTED]');
  const state = {gate, evidence, scratch: ownership.temporary, ownership, inputs, environment, password,
    interrupted: false, locked: false, containerAttempted: false, readinessAttempts: 0, children: [], sequence: 0, derivedClasspathClosures: []};
  const verifyInputs = () => {
    assert.equal(hash(readFileSync(gatePath)), hash(readFileSync(join(evidence, 'root-gate.json'))), 'root gate drift');
    for (const pin of gate.files) verifyFilePin(pin, gate.inputRoots);
    assert.equal(execFileSync(gate.tools.git, ['rev-parse', 'HEAD'], {cwd: root, env: environment, encoding: 'utf8', timeout: 3000}).trim(), gate.head, 'root HEAD drift');
    state.beforeEachProcess?.();
    verifyRuntimeClasspaths(inputs, gate.files, state.derivedClasspathClosures);
  };
  const ps = args => execFileSync(gate.tools.ps, args, {encoding: 'utf8', timeout: 3000,
    env: environment, stdio: ['ignore', 'pipe', 'pipe']});
  const aliveGroup = record => {
    const lines = ps(['-axo', 'pid=,pgid=,lstart=']).trim().split('\n');
    const members = lines.map(line => line.trim().match(/^(\d+)\s+(\d+)\s+(.+)$/)).filter(Boolean)
      .filter(match => Number(match[2]) === record.pid);
    const observed = [];
    for (const match of members) {
      const pid = Number(match[1]);
      if (pid === record.pid) assert.equal(match[3], record.startIdentity, 'owned PID was reused');
      let command;
      try { command = ps(['eww', '-p', String(pid), '-o', 'command=']); }
      catch (failure) { try { process.kill(pid, 0); } catch (gone) { if (gone.code === 'ESRCH') continue; } throw failure; }
      observed.push({pid, pgid: Number(match[2]), startIdentity: match[3],
        uuid: command.includes('TASK68_UUID=' + gate.uuid) ? gate.uuid : null});
    }
    record.members = observed;
    return ownedProcessSignalPlan(record, observed, 'SIGTERM') !== null;
  };
  const signalGroup = (record, signal) => {
    if (!aliveGroup(record)) return;
    const plan = ownedProcessSignalPlan(record, record.members, signal);
    try { process.kill(plan.pid, plan.signal); } catch (failure) { if (failure.code !== 'ESRCH') throw failure; }
  };
  const terminate = async record => {
    signalGroup(record, 'SIGTERM');
    const until = Date.now() + 5000;
    while (aliveGroup(record) && Date.now() < until) await pause(50);
    if (aliveGroup(record)) signalGroup(record, 'SIGKILL');
    const killedBy = Date.now() + 1000;
    while (aliveGroup(record) && Date.now() < killedBy) await pause(20);
    assert(!aliveGroup(record), 'owned process group survived termination');
  };
  state.execute = async (tag, executable, args, timeout, extra = {}) => {
    assert(!state.interrupted, 'Task68 interrupted'); verifyInputs();
    if (state.deadline) timeout = Math.min(timeout, (state.deadline - Date.now()) / 1000);
    if (state.minimumPendingRemaining) assert(state.pendingExpiry - Date.now() >= state.minimumPendingRemaining, 'less than 225s after final input verification');
    if (state.producerDeadline) timeout = Math.min(timeout, (state.producerDeadline - Date.now()) / 1000);
    if (state.pendingExpiry) timeout = Math.min(timeout, (state.pendingExpiry - Date.now()) / 1000);
    assert(timeout > 0 && timeout <= 1200, 'approved process timeout exceeded');
    const prefix = String(++state.sequence).padStart(3, '0') + '-' + tag;
    const startedAt = new Date().toISOString(), startedMonotonicNs = process.hrtime.bigint().toString();
    const child = spawn(executable, args, {cwd: extra.cwd ?? root, env: environment, detached: true,
      stdio: ['pipe', 'pipe', 'pipe']});
    state.minimumPendingRemaining = undefined;
    let record, timer, timedOut = false, exitCode, processError, rejectDeadline;
    const deadline = new Promise((_, reject) => { rejectDeadline = reject; });
    state.abortActive = () => rejectDeadline(new Error('Task68 signal interrupted active process'));
    const stdout = [], stderr = [];
    child.stdout.on('data', bytes => stdout.push(bytes)); child.stderr.on('data', bytes => stderr.push(bytes));
    const finished = new Promise(resolve => {
      child.once('error', failure => { processError = failure; resolve(); });
      child.once('close', (code, signal) => { exitCode = code; if (signal) processError ??= new Error('process signal: ' + signal); resolve(); });
    });
    try {
      if (child.pid) {
        let startIdentity;
        try { startIdentity = ps(['-p', String(child.pid), '-o', 'lstart=']).trim(); }
        catch (failure) { try { process.kill(child.pid, 0); throw failure; } catch (gone) { if (gone.code !== 'ESRCH') throw gone; } }
        record = {pid: child.pid, startIdentity, uuid: gate.uuid, tag, startedAt}; state.children.push(record);
        privateWrite(join(evidence, prefix + '-process.json'), {...record, executable, args: args.map(safe), timeout});
        timer = setTimeout(() => {
          timedOut = true;
          terminate(record).then(() => rejectDeadline(new Error('process deadline exceeded: ' + tag)),
            failure => rejectDeadline(failure));
        }, timeout * 1000);
      }
      child.stdin.on('error', failure => { if (failure.code !== 'EPIPE') processError = failure; });
      child.stdin.end(extra.input ?? undefined);
      await Promise.race([finished, deadline]);
    } finally {
      clearTimeout(timer); state.abortActive = undefined;
      let terminationFailure;
      try { if (record) await terminate(record); } catch (error) { terminationFailure = error; }
      privateWrite(join(evidence, prefix + '.log'), safe(Buffer.concat([...stdout, ...stderr])));
      privateWrite(join(evidence, prefix + '-status.json'), {executable, args: args.map(safe), exitCode,
        timedOut, terminationFailure: terminationFailure ? safe(terminationFailure.message) : null,
        error: processError ? safe(processError.message) : null, startedAt, startedMonotonicNs,
        endedAt: new Date().toISOString(), endedMonotonicNs: process.hrtime.bigint().toString()});
      verifyInputs();
      if (terminationFailure) throw terminationFailure;
    }
    assert(!timedOut, 'process deadline exceeded: ' + tag);
    if (processError) throw processError;
    assert.equal(exitCode, 0, 'process failed: ' + tag);
    return Buffer.concat(stdout).toString('utf8');
  };
  const onSignal = () => {
    state.interrupted = true; state.abortActive?.();
    for (const child of state.children) try { signalGroup(child, 'SIGTERM'); } catch { /* finally records ownership failure */ }
  };
  process.on('SIGINT', onSignal); process.on('SIGTERM', onSignal);
  let failure, cleanupFailure, outcome;
  try {
    assert(!existsSync(state.scratch), 'fresh canonical Task68 scratch root required');
    mkdirSync(state.scratch, {mode: 0o700});
    privateWrite(join(state.scratch, 'owner.json'), {uuid: gate.uuid, pid: process.pid});
    mkdirSync(join(state.scratch, 'jvm-tmp'), {mode: 0o700});
    environment.TMPDIR = join(state.scratch, 'jvm-tmp');
    if (gate.schemaTopology.length) {
      mkdirSync(ownership.lock, {mode: 0o700}); state.locked = true;
      privateWrite(join(ownership.lock, 'task68-owner.json'), {uuid: gate.uuid, pid: process.pid});
      state.deadline = Date.now() + 600000;
      await nativeM2c(state);
    }
    if (gate.stage === 'materialize-runtime') await materializeRuntime(state);
    else if (gate.stage === 'materialize-goldens') await materializeGoldens(state);
    else if (gate.stage === 'counter-old-pending') await produceOldAndTest(state);
    else await runSelected(state);
    verifyInputs(); assert(!state.interrupted);
    outcome = phase === 'prepare' ? 'MATERIALIZED_PENDING_ROOT_ACCEPTANCE' : 'TESTS_PASSED_PENDING_ROOT_RECONCILIATION';
  } catch (error) {
    failure = error; outcome = phase === 'prepare' ? 'MATERIALIZATION_FAILED' : 'ACTION_FAILED';
    privateWrite(join(evidence, 'failure.json'), {message: safe(error.message), stack: safe(error.stack),
      producerFailureCreatesNoTestcase: true});
  } finally {
    const errors = [];
    for (const child of state.children) try { await terminate(child); } catch (error) { errors.push(error); }
    if (state.containerAttempted) try {
      const docker = args => execFileSync(gate.tools.docker, ['--context', 'colima-hxkey-tests', ...args],
        {env: environment, encoding: 'utf8', timeout: 10000, stdio: ['ignore', 'pipe', 'pipe']}).trim();
      verifyFilePin(gate.files.find(pin => pin.path === gate.tools.docker), gate.inputRoots);
      if (docker(['ps', '-aq', '--filter', 'name=^/' + ownership.name + '$'])) {
        assert.equal(docker(['inspect', '--format', '{{index .Config.Labels "codex.task68.uuid"}}', ownership.name]), gate.uuid);
        docker(['rm', '--force', ownership.name]);
      }
      assert.equal(docker(['ps', '-aq', '--filter', 'name=^/' + ownership.name + '$']), '');
    } catch (error) { errors.push(error); }
    if (state.disposableResources && existsSync(state.disposableResources)) try {
      assert.equal(realpathSync(state.disposableResources), join(evidence, 'resources'));
      rmSync(state.disposableResources, {recursive: true, force: false});
    } catch (error) { errors.push(error); }
    if (existsSync(state.scratch)) try {
      assert.equal(realpathSync(state.scratch), ownership.temporary);
      assert.deepEqual(jsonFile(join(state.scratch, 'owner.json')), {uuid: gate.uuid, pid: process.pid});
      rmSync(state.scratch, {recursive: true, force: false});
    } catch (error) { errors.push(error); }
    if (state.locked) try {
      assert.equal(realpathSync(ownership.lock), '/private/tmp/codex-beauty-bm04-native.lock');
      assert.deepEqual(jsonFile(join(ownership.lock, 'task68-owner.json')), {uuid: gate.uuid, pid: process.pid});
      rmSync(join(ownership.lock, 'task68-owner.json')); rmdirSync(ownership.lock);
    } catch (error) { errors.push(error); }
    process.off('SIGINT', onSignal); process.off('SIGTERM', onSignal);
    try { for (const file of runtimeClasspathFiles([evidence])) chmodSync(file, 0o600); }
    catch (error) { errors.push(error); }
    privateWrite(join(evidence, 'cleanup.json'), {uuid: gate.uuid, retainedMaterials: true,
      ownedScratchAbsent: !existsSync(state.scratch), ownedLockReleased: !state.locked || !existsSync(ownership.lock),
      errors: errors.map(error => safe(error.message))});
    if (errors.length) { cleanupFailure = new AggregateError(errors, 'Task68 owned cleanup incomplete'); outcome = 'CLEANUP_FAILED'; }
    privateWrite(join(evidence, 'outcome.json'), {outcome, testOutcome: phase === 'prepare' ? 'NOT_RUN'
      : failure || cleanupFailure ? 'FAILED' : 'PASS', rootAcceptance: 'PENDING', uuid: gate.uuid});
  }
  if (cleanupFailure) throw cleanupFailure;
  if (failure) throw failure;
  console.log('Task68 ' + outcome + ': ' + evidence);
}

if (process.argv[1] && realpathSync(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const args = process.argv.slice(2);
    assert(args.length === 2 && ((['--slice=m2a', '--slice=m2b'].includes(args[0])
      && /^--phase=(red|green|ablation|restored)$/.test(args[1]))
      || (args[0] === '--slice=m2c' && /^--phase=(prepare|green)$/.test(args[1]))), 'invalid Task66 selection');
    if (args[0] === '--slice=m2c') await runM2c(args[1].slice('--phase='.length));
    else await run(args[0].slice('--slice='.length), args[1].slice('--phase='.length));
  } catch (failure) { console.error(failure.message); process.exitCode = 1; }
}
