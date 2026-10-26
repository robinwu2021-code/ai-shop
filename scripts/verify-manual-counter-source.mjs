import assert from 'node:assert/strict';
import {execFileSync, spawn} from 'node:child_process';
import {createHash, randomBytes} from 'node:crypto';
import {readFileSync, writeFileSync, mkdirSync, rmSync, existsSync, realpathSync, rmdirSync, readdirSync, statSync} from 'node:fs';
import {join, relative, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';

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
  + 'V542__shared_manual_order_customer_party.sql';
const migrations = [
  'backend/shop-app/src/main/resources/db/migration/V539__checkout_source_foundation.sql',
  'backend/shop-app/src/main/resources/db/migration/V540__checkout_payment_operation.sql',
  'backend/shop-app/src/main/resources/db/migration/V541__manual_service_admission.sql',
  'backend/shop-inventory/src/main/resources/db/inventory/V14__original_stock_disposition.sql'
];

/** Root-controlled finite stages prevent a TDD owner run from silently expanding into all suites. */
export function phaseSelection(slice, phase, stage = 'm2a') {
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

if (process.argv[1] && realpathSync(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const args = process.argv.slice(2);
    assert(args.length === 2 && ['--slice=m2a', '--slice=m2b'].includes(args[0])
      && /^--phase=(red|green|ablation|restored)$/.test(args[1]), 'invalid Task66 selection');
    await run(args[0].slice('--slice='.length), args[1].slice('--phase='.length));
  } catch (failure) { console.error(failure.message); process.exitCode = 1; }
}
