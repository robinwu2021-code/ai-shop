import {execFileSync, spawnSync} from 'node:child_process';
import {readFileSync, writeFileSync, readdirSync, mkdirSync, rmSync, rmdirSync, statSync} from 'node:fs';
import {fileURLToPath, pathToFileURL} from 'node:url';
import {createHash, randomBytes, randomUUID} from 'node:crypto';
import assert from 'node:assert/strict';

const root = fileURLToPath(new URL('../', import.meta.url));
// Reviewed scenario @Test declarations; never discover selectable capabilities from runtime source files.
const methods = {
  "PaymentOperationNativeMysqlTest": [
    "preparedReadBeforeAliasCommitRecoversCanonicalReceiptUnderRepeatableRead",
    "preparedReadBeforeAliasCommitCannotClaimOrMutateSuccessfulAlias",
    "canonicalOperationsRejectCallerTransactionsBeforeTakingLocks",
    "preparedQueryDoesNotDispatch",
    "aliasWinningTheFirstSendReturnsItsOwnIdentityAndCanonicalOriginalNumber",
    "aliasWinningUncertainDispatchKeepsItsIdentityAndOriginalPendingPayment",
    "aliasWinningProvenUnsentDispatchKeepsItsIdentityAndRejectedOriginal",
    "differentKeysShareOneCanonicalAttempt",
    "trustedCheckoutInstructionCannotCrossItsFrozenStoreOrOmitOnlineRoute",
    "twoPreparedRequestsConcurrentlyShareOneCanonicalAndOneSendClaim",
    "oldOpenAndNewOpenUseOneGlobalGuardDespiteHistoricalAuditTenant",
    "successfulReceiptPreventsBothOldAndNewOpenFromCreatingAnotherChargeableAttempt",
    "sendingAndUnknownNotFoundCannotBeClosedByLegacyCloseOrReconciliation",
    "provenNotSentKeepsOriginalRejectedMappingAndPermitsOnlyNewOperationToAllocate",
    "offlineLedgerIsExcludedFromExternalChannelReconciliation",
    "payAndPlatformUseDifferentActualConnectionsToTheSameAuthoritativeSchema"
  ],
  "PaymentOperationProcessRestartNativeTest": [
    "crashAfterSendingCommitBeforeNetworkNeverResendsOrAllocatesAnotherNumber",
    "crashAfterThirdPartyAcceptanceRecoversRealReceiptByOriginalKeyInAnotherJvm"
  ],
  "OfflineReceiptFlowTest": [
    "authorizedCashCreatesOneRealReceiptAndReplaysOriginalKeyAndNewAlias",
    "firstAlreadyReceivedCashAfterActualCancellationAndPolicyChangePreservesReceiptAndLateResolution",
    "strictAmountAndOriginalConsumedTimeBoundaryCannotCreateFinancialFacts",
    "bankTransferBindsActualEvidenceAndCannotReuseItAcrossOrdersButKeepsOriginalReceiptOnReplay",
    "sourceExposesOneCanonicalOriginalReceiptAndCallsFinancialOwnerOnlyOutsideSnapshot",
    "twoBankRequestsPreparedBeforeOpenShareOneEvidenceBindingAndRealReceipt",
    "offlineP0SurvivesUnavailableFinancialOwnerAndReusesTheSameOriginalOperation",
    "mappedOwnerUnavailablePreservesLastVerifiedRevisionAndHashWithoutInventingZeroMoney",
    "cashReplayRequiresCurrentPermissionAndRawKeyLookupIsCurrentActorAndStoreScoped"
  ],
  "CheckoutPaymentAuthenticationFlowTest": [
    "lateSuccessfulOriginalBlocksOpenedOtherAttemptBeforeFirstSend",
    "lateSuccessfulOriginalBlocksPreparedAliasOfNewerPendingAttempt",
    "acceptedKeyCannotFirstSendAfterActualOrderCancellation",
    "acceptedKeyCannotFirstSendAfterActualOrderExpiry",
    "openedOriginalCannotFirstSendAfterActualOrderCancellation",
    "acceptedKeyCannotFirstSendAfterDeadlineBeforeExpirySweep",
    "previouslyAcceptedPaidFlagWithoutRealReceiptCannotAuthorizeFirstSend",
    "acceptedUnknownStillRecoversOriginalActualMoneyAfterCancellation",
    "paidOrderRejectsNewKeyBeforeMappingWhilePreviouslyAcceptedAliasRecoversActualReceipt",
    "definitivelyUnsentOriginalDoesNotHideNewKeySuccessOrContraryLateMoney",
    "originalKeySurvivesFailureBeforePayOwnerAndReadNeverDispatches",
    "merchantAndRevokedConsumerCannotInitiateOrReadParamsAndOldManagedPayCannotBypass",
    "revokedDuringAcceptedDispatchCannotReceiveParametersButOriginalFinancialFactSurvives",
    "realReceiptCannotProjectAcrossChangedOriginalManagedStoreFacts",
    "realReceiptCannotProjectIfOriginalItemShareNoLongerMatchesConsumedQuote",
    "zeroDueManagedCheckoutImmediatelyProjectsItsActualFreeReceipt",
    "simultaneousOriginalKeyAndNewAliasKeepOnePaymentAndOneLockFreeSend",
    "currentConsumerHttpRejectsForeignBuyerMerchantForgedAmountAndRevokedRead"
  ],
  "CheckoutCashPermissionFlowTest": [
    "ownerWildcardResolvesActualAccountButDelegationIsNotCurrentCashAuthorization",
    "receiveAndViewDoNotGrantCashAndGrantRevocationIsImmediateWithinOneSession",
    "userLoginResolvesLinkedAccountAndCannotBorrowAnotherStoreOrNamespace"
  ],
  "ReceiptEvidenceFlowTest": [
    "unboundReceiptEvidenceSurvivesScannerAndExplicitOrFilteredPurge",
    "alreadyQueuedReceiptCannotBeDeletedOrReceiveAnOrdinaryPreviewCredential",
    "pendingReconciliationRetainsAbsentBytesAndCompletesExistingBytesWithoutDeletingEitherOwnerRecord",
    "dedicatedUploadUsesCurrentCashierAccountAndActualStoreAndRemainsPrivate",
    "authoritativeEvidenceUsesUtcActualOwnerAndBytesAndNeverAcceptsCallerTransaction",
    "consumerAndGenericUploadsCannotCreateDedicatedPrivateCashEvidence"
  ],
  "CheckoutNativeMysqlTest": [
    "sameKeyCreatesOneOrderAndStableSourceLines",
    "differentKeysCannotConsumeConfirmationTwice",
    "confirmationFirstKeyIsPermanent",
    "expiredIdempotencyCacheDoesNotCreateAgain",
    "expiryBeforePlaceRequiresRequoteAndNeverRegistersIntent",
    "nativeConstraintsRejectInvalidAuthorityPairs",
    "placeClaimSurvivesWriterRollbackAndReleasedIntentCannotRevive",
    "prepareReplayUsesOriginalResolvedBuyerAfterReferenceExpiry",
    "preparedAndRecoveringOriginalIntentNeverAllocateAnotherOrder",
    "prepareKeyIsScopedByActualMerchantActorNamespace",
    "frozenOwnerRowsAndDiagnosticRequestsDoNotRetainIdentityCredentials"
  ],
  "CheckoutRestartNativeTest": [
    "lostConfirmationAndPlaceResponsesRecoverOriginalOrderAfterContextRestart"
  ],
  "CheckoutPermissionFlowTest": [
    "ownerHasNewPermissionByExistingWildcard",
    "clerkWithoutExplicitGrantCannotPrepareCheckout",
    "currentOwnerRevocationTakesEffect",
    "crossStoreCannotUseAnOwnedEntityAsAuthority",
    "delegatedNamespaceCannotTurnMerchantAccountIntoUserActor",
    "explicitGrantAndRevocationTakeEffectWithoutNewLogin",
    "pickerAndCourierCannotReadCheckoutMoneyOrPrepare"
  ],
  "CheckoutPricingFlowTest": [
    "prepareQuotesActualServicePriceWithoutCreatingOrdersOrReservingStock",
    "ordinaryLocalAmountCouponIsQuotedWithoutConsumption",
    "unsupportedCouponScopeCannotBeSilentlyIgnoredByOrdinaryAllocator",
    "unsupportedQuantityAndMultiUseCouponsAreRejected",
    "migrationFenceIsRejectedAsUnsupportedBeforeAQuoteIsPublished",
    "zeroMoneyPointsComboCannotDisappearFromTheConfirmedContents",
    "committedReplayKeepsTheOriginalQuoteWhileNewPayloadConflicts",
    "overflowThatWrapsPositiveIsRejectedBeforeQuoteOrIntent",
    "currentBuyerConfirmationAuthorizesExactlyOneActualOrder",
    "priceMutationAfterPrepareIsRejectedAtWriterSealBeforeStock",
    "sameAmountDifferentDiscountFundingFailsSeal",
    "catalogMutationIsRecheckedInFinalWriterTransaction",
    "ordinaryLocalCouponStillUsesOwnerAllocation",
    "couponLockWaitAcrossExpiryRejectsBeforeIntent",
    "frozenEconomicEvidenceRetainsCatalogScopeAndCommandHash",
    "merchantPrepareUsesBuyerAudienceForFlashAndDiscount",
    "buyerAudienceGiftCannotSilentlyDisappearInMerchantPrepare",
    "explicitBuyerFlashCannotAcceptCutoffPriceWhileOldOrdinaryApiKeepsIt",
    "couponWaitCrossingScheduledCatalogVersionRequiresNewQuote",
    "persistedItemSharesKeepActualLineOrderForEqualRemainders"
  ],
  "CheckoutHttpFlowTest": [
    "consumerReadsConfirmsAndPlacesThroughRealAuthenticatedHttp",
    "internalPrepareIsVersionedBareAndDoesNotEchoIdentityCredentials",
    "internalExtraUnitIsRejectedInsteadOfImplicitCount",
    "internalProtocolAndTransportCannotImpersonateCurrentBuyer",
    "unknownConsumerMoneyAndActorFieldsNeverBecomePurchaseAuthorization",
    "fractionalCountAndQuotedCountAreRejectedByActualInternalHttp",
    "foreignConsumerCannotReadConfirmOrReplayOrder",
    "actualConsumerReceiptHttpCannotCompleteManagedLegacyService"
  ],
  "CheckoutManagedLifecycleFlowTest": [
    "managedPaidTransitionDoesNotIssueLegacyVerificationCode",
    "oldMerchantOfflineShortcutCannotInventManagedReceipt",
    "sharedOfflineTrustBranchCannotBypassManagedGuard",
    "currentBuyerCanCancelUnpaidManagedOrderWithoutCompletingService",
    "ordinaryExpirySweepCanCloseUnpaidManagedOrderWithoutCompletingService",
    "actualFulfillmentPickupCompletionRejectsManagedOrder",
    "actualPickupVerificationCannotCompleteHistoricalManagedCode",
    "actualMerchantDeliveredAndInterventionRejectManagedOrder",
    "actualCurrentBuyerLegacyReceiptRejectsManagedOrder",
    "actualAutoLegacyReceiptRejectsManagedOrder",
    "actualPackageReceiptRollsBackShipmentWriteWhenManagedCompletionIsDenied",
    "actualSignedReceiptRejectsManagedServiceFulfillmentBeforeShipmentMutation"
  ],
  "CheckoutOwnerBoundaryFlowTest": [
    "catalogLockRequiresCurrentBuyerAndActualPlatformTransaction",
    "unsupportedDirectoryCapabilityNeverFallsBackToRemotePresentationRead",
    "migrationWinsBeforeFinalLockAndRejectsBeforeIntent",
    "actualCheckoutRootLockMakesMigrationWaitUntilCouponUseCommits",
    "ordinaryCouponCheckoutNeverCallsPayRoutingLogisticsOrAppointmentBoundary"
  ],
  "CheckoutSourceFactsFlowTest": [
    "unknownReceiptNeverBecomesZeroMoney",
    "billUsesHistoricalOwnerMoneyAndStableBusinessLines",
    "refundWithoutVersionIncrementChangesSourceHashAndAvailableQuantity",
    "unknownKeepsLastVerifiedPairAndReturnsNoConsumableLines",
    "mismatchedReceiptDoesNotBecomeReceivedMoney",
    "cancelledOrderHasZeroAvailableQuantity",
    "sourceCannotSubstituteAnotherSubOrderOrJoinAnOuterSnapshot",
    "refundBetweenFactReadsAndSerializedObservationNeverMixesSnapshots",
    "openAfterSaleBlocksExecutionAndCorruptedMappingBecomesUnknown"
  ],
  "BeautyClaimedGuestResourceIT": [
    "claimedGuestReplacementUsesCanonicalUserAndPreservesReceipts",
    "revokedUsrBookingGrantCannotReplaceClaimedGuestAppointment",
    "failedClaimedGuestReplacementPreservesOriginalResourceHold"
  ],
  "BeautySourceCheckoutIT": [
    "realGuestClaimThenCurrentConsumerPurchaseFeedsBindingAndBill",
    "lostQuoteResponseRecoversRealOriginalUnderCurrentManager",
    "closedSourceRetainsActualLateReceiptWithoutBindingOrExecution",
    "realOfflineCanonicalReceiptAndRefundBillRemainOwnerFacts"
  ]
};

export function validateSelection(mode, selected) {
  const invalid = message => assert.fail('invalid checkout selection: ' + message);
  if (!['payment', 'beauty-source'].includes(mode)) invalid('mode');
  if (selected === undefined) selected = mode === 'payment' ? 'PaymentOperationNativeMysqlTest' : 'BeautySourceCheckoutIT';
  if (typeof selected !== 'string' || !selected || /\s/u.test(selected)) invalid('empty or whitespace');
  const selectors = selected.split(',');
  if (mode === 'beauty-source' && selectors.length !== 1) invalid('beauty-source requires one class selector');
  const classes = [], requestedMethods = {};
  for (const selector of selectors) {
    const parts = selector.split('#'), [name, explicit] = parts;
    if (parts.length > 2 || !Object.hasOwn(methods, name)
      || (mode === 'beauty-source') !== (['BeautySourceCheckoutIT', 'BeautyClaimedGuestResourceIT'].includes(name))) invalid('class or mode');
    const requested = explicit === undefined ? [] : explicit.split('+');
    if (explicit !== undefined && requested.some(method => !methods[name].includes(method))) invalid('method');
    if (Object.hasOwn(requestedMethods, name)) {
      if (!requested.length || !requestedMethods[name].length) invalid('duplicate whole class');
    } else { classes.push(name); requestedMethods[name] = []; }
    for (const method of requested) {
      if (requestedMethods[name].includes(method)) invalid('duplicate method');
      requestedMethods[name].push(method);
    }
  }
  return {mode, selectors, classes, requestedMethods};
}

// Bounded Surefire XML reader: markup inside comments/CDATA is text, never execution evidence.
const xmlCharacter = point => point === 9 || point === 10 || point === 13
  || (point >= 32 && point <= 0xD7FF) || (point >= 0xE000 && point <= 0xFFFD)
  || (point >= 0x10000 && point <= 0x10FFFF);
function xmlValue(value) {
  const entity = /&(?:amp|lt|gt|quot|apos|#x[\da-fA-F]+|#\d+);/g;
  assert(!value.replace(entity, '').includes('&'), 'Malformed XML entity');
  return value.replace(entity, token => {
    const named = {'&quot;': '"', '&apos;': "'", '&lt;': '<', '&gt;': '>', '&amp;': '&'};
    if (Object.hasOwn(named, token)) return named[token];
    const point = token[2] === 'x' ? parseInt(token.slice(3, -1), 16) : Number(token.slice(2, -1));
    assert(xmlCharacter(point), 'Invalid XML character reference');
    return String.fromCodePoint(point);
  });
}
function reportStructure(xml) {
  assert(typeof xml === 'string' && Buffer.byteLength(xml) <= 16 * 1024 * 1024, 'Surefire XML exceeds bound');
  for (const character of xml) assert(xmlCharacter(character.codePointAt(0)), 'Invalid XML character');
  const stack = [], cases = [], outcomes = [];
  const children = {testsuite: ['properties', 'testcase', 'system-out', 'system-err'], properties: ['property'],
    testcase: ['failure', 'error', 'skipped', 'flakyFailure', 'flakyError', 'rerunFailure', 'rerunError',
      'system-out', 'system-err']};
  const textNodes = new Set(['system-out', 'system-err', 'failure', 'error', 'skipped',
    'flakyFailure', 'flakyError', 'rerunFailure', 'rerunError']);
  const space = character => character !== undefined && /[ \t\r\n]/.test(character);
  let position = xml.charCodeAt(0) === 0xFEFF ? 1 : 0, suite, nodes = 0;
  const text = value => {
    if (!textNodes.has(stack.at(-1))) assert(/^[ \t\r\n]*$/.test(value), 'Unexpected XML text');
  };
  while (position < xml.length) {
    if (xml[position] !== '<') {
      const end = xml.indexOf('<', position), stop = end < 0 ? xml.length : end;
      const value = xml.slice(position, stop);
      assert(!value.includes(']]>'), 'Invalid XML text delimiter');
      text(xmlValue(value)); position = stop; continue;
    }
    if (xml.startsWith('<!--', position)) {
      const end = xml.indexOf('-->', position + 4);
      const body = xml.slice(position + 4, end);
      assert(end >= 0 && !body.includes('--') && !body.endsWith('-'), 'Malformed XML comment');
      position = end + 3; continue;
    }
    if (xml.startsWith('<![CDATA[', position)) {
      const end = xml.indexOf(']]>', position + 9);
      assert(stack.length && end >= 0, 'Malformed XML CDATA');
      text(xml.slice(position + 9, end)); position = end + 3; continue;
    }
    if (xml.startsWith('<?', position)) {
      assert(!suite && position === (xml.charCodeAt(0) === 0xFEFF ? 1 : 0), 'Unexpected XML instruction');
      const declaration = /^<\?xml version=(['"])1\.0\1(?: encoding=(['"])[A-Za-z][\w.-]*\2)?\s*\?>/
        .exec(xml.slice(position));
      assert(declaration, 'Unsupported XML declaration'); position += declaration[0].length; continue;
    }
    assert(!xml.startsWith('<!', position), 'Unsupported XML declaration');
    if (xml.startsWith('</', position)) {
      const end = /^<\/([A-Za-z_][\w.:-]*)[ \t\r\n]*>/.exec(xml.slice(position));
      assert(end && stack.pop() === end[1], 'Mismatched XML closing element');
      position += end[0].length; continue;
    }
    const opening = /^<([A-Za-z_][\w.:-]*)/.exec(xml.slice(position));
    assert(opening && ++nodes <= 100000 && stack.length < 64, 'Malformed or oversized XML element');
    const name = opening[1], attributes = Object.create(null);
    position += opening[0].length;
    let selfClosing = false;
    while (true) {
      const beforeSpace = position;
      while (space(xml[position])) position++;
      if (xml.startsWith('/>', position)) { selfClosing = true; position += 2; break; }
      if (xml[position] === '>') { position++; break; }
      assert(position > beforeSpace, 'Malformed XML attribute separator');
      const attribute = /^([A-Za-z_][\w.:-]*)[ \t\r\n]*=[ \t\r\n]*(['"])(.*?)\2/s.exec(xml.slice(position));
      assert(attribute && !attribute[3].includes('<') && !Object.hasOwn(attributes, attribute[1]),
        'Malformed or duplicate XML attribute');
      attributes[attribute[1]] = xmlValue(attribute[3]); position += attribute[0].length;
    }
    const parent = stack.at(-1);
    if (!parent) { assert(name === 'testsuite' && !suite, 'Expected exactly one root Surefire suite'); suite = attributes; }
    else assert(children[parent]?.includes(name), 'Unexpected Surefire element relationship');
    if (name === 'testcase') cases.push(attributes);
    if (textNodes.has(name) && !['system-out', 'system-err'].includes(name)) outcomes.push(name);
    if (!selfClosing) stack.push(name);
  }
  assert(suite && !stack.length, 'Incomplete Surefire XML');
  return {suite, cases, outcomes};
}

export function inspectReport(xml, requested) {
  const {className, methods: explicit = [], startedAt, mtimeMs} = requested;
  if (startedAt !== undefined || mtimeMs !== undefined) {
    assert(Number.isFinite(startedAt) && Number.isFinite(mtimeMs) && mtimeMs >= startedAt,
      className + ' must produce a fresh report');
  }
  const expected = 'ai.neargo.shop.scenario.' + className;
  const {suite, cases, outcomes} = reportStructure(xml);
  assert.equal(suite.name, expected, 'Unexpected suite class');
  const counts = {};
  for (const field of ['tests', 'failures', 'errors', 'skipped']) {
    assert.match(suite[field] ?? '', /^\d+$/, 'Missing report count ' + field);
    counts[field] = Number(suite[field]);
  }
  assert(counts.tests > 0, className + ' must execute at least one test');
  for (const field of ['failures', 'errors', 'skipped']) assert.equal(counts[field], 0, 'Nonzero ' + field);
  assert.equal(outcomes.length, 0, 'Unsuccessful testcase');
  assert.equal(cases.length, counts.tests, 'Testcase count differs from suite');
  for (const testcase of cases) assert.equal(testcase.classname, expected, 'Unexpected testcase class');
  for (const method of explicit) {
    const escaped = method.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    const pattern = new RegExp('^' + escaped + '(?:\\([^)]*\\))?(?:\\[\\d+\\](?: .*)?)?$','u');
    assert(cases.some(testcase => pattern.test(testcase.name)), className + ' missing requested method ' + method);
  }
  return counts;
}

const nativeEffects = {readFileSync, writeFileSync, readdirSync, mkdirSync, rmSync, rmdirSync, statSync,
  execFileSync, spawnSync, randomUUID, randomBytes, now: Date.now,
  pause: ms => new Promise(resolve => setTimeout(resolve, ms)), log: console.log, error: console.error};

export async function main(argv = process.argv.slice(2), env = process.env, effects = {}) {
  assert(argv.length === 0 || (argv.length === 1 && argv[0] === '--beauty-source'), 'invalid checkout arguments');
  const selection = validateSelection(argv.length ? 'beauty-source' : 'payment', env.CHECKOUT_PAYMENT_TESTS);
  const e = {...nativeEffects, ...effects};
  const beautySource = selection.mode === 'beauty-source', selected = selection.selectors.join(',');
  const name = 'codex-checkout-payment-' + e.randomUUID(), temporary = '/tmp/' + name;
  const database = 'checkout_pay_' + e.randomBytes(8).toString('hex');
  const password = e.randomBytes(24).toString('base64url');
  const environment = {...env, MYSQL_ROOT_PASSWORD: password, MYSQL_PWD: password};
  const lock = '/tmp/codex-beauty-bm04-native.lock';
  let locked = false, attempted = false, temporaryAttempted = false;
  const credentials = new Set([password]);
  const discoverCredentials = value => {
    for (const match of String(value).matchAll(/Using generated security password:\s*([^\s]+)/g)) {
      if (match[1] !== '[REDACTED]') credentials.add(match[1]);
    }
  };
  const redact = value => {
    let output = String(value);
    for (const credential of credentials) output = output.replaceAll(credential, '[REDACTED]');
    return output;
  };
  const docker = (args, input) => {
    try {
      return e.execFileSync('docker', ['--context', 'colima-hxkey-tests', ...args],
        {encoding: 'utf8', input, env: environment, stdio: ['pipe', 'pipe', 'pipe'], timeout: 120000}).trim();
    } catch (error) {
      discoverCredentials(error.stderr || error.message);
      throw new Error('Owned Docker operation failed: ' + redact(error.stderr || error.message));
    }
  };
  const mysql = (sql, schema) => docker(['exec', '-i', '--env', 'MYSQL_PWD', name,
    'mysql', '--protocol=TCP', '-h127.0.0.1', '-uroot', ...(schema ? [schema] : [])], sql);
  try {
    e.mkdirSync(lock); locked = true;
    const migrationDirectory = root + 'backend/shop-app/src/main/resources/db/migration/';
    const migration = e.readFileSync(migrationDirectory + 'V539__checkout_source_foundation.sql');
    assert.equal(migration.length, 8444, 'V539 size changed after root pin');
    assert.equal(createHash('sha256').update(migration).digest('hex'),
      '5a8e346341e5b378403761f2a77555c0558ff6b27a6071584207a9d8f9e14d3b', 'V539 differs from root pin');
    const paymentMigration = e.readFileSync(migrationDirectory + 'V540__checkout_payment_operation.sql');
    assert.equal(paymentMigration.length, 7477, 'V540 size changed after root pin');
    assert.equal(createHash('sha256').update(paymentMigration).digest('hex'),
      'ca76b6512cf79c6f25fa71a4441f533e03cc609c6320611a0dcdc9634a10f745', 'V540 differs from root pin');
    if (beautySource) {
      const observation = e.readFileSync(root + 'backend-plugins/shop-industry-beauty/beauty-core/src/main/resources/'
        + 'db/industry/beauty/V8__beauty_source_observation.sql');
      assert.equal(observation.length, 4582, 'V8 size differs from actual-file pin');
      assert.equal(createHash('sha256').update(observation).digest('hex'),
        'c5b54d574624a5265366cf4bf7897ec949d107bd302f171539c461a89a9d8343', 'V8 differs from actual-file pin');
    }
    const memory = e.execFileSync('limactl', ['shell', '--instance', 'colima-hxkey-tests', 'cat', '/proc/meminfo'],
      {encoding: 'utf8', env: {...env, LIMA_HOME: '/Users/robin/.colima/_lima'}});
    const available = Number(memory.match(/^MemAvailable:\s+(\d+)\s+kB$/m)?.[1]);
    assert(Number.isFinite(available) && available >= 512 * 1024, 'Colima MemAvailable must be at least 512 MiB');
    docker(['image', 'inspect', 'mysql:9.7.2']);
    e.log('Native preflight: MemAvailable=' + available + ' KiB; cached MySQL 9.7.2; disk 64M; one owned UUID.');
    temporaryAttempted = true;
    e.mkdirSync(temporary + '/migrations', {recursive: true});
    for (const filename of e.readdirSync(migrationDirectory)) {
      const version = Number(filename.match(/^V(\d+)__/i)?.[1]);
      if (version > 0 && version <= 538) {
        e.writeFileSync(temporary + '/migrations/' + filename, e.readFileSync(migrationDirectory + filename));
      }
    }
    e.execFileSync('python3', [root + 'backend/scripts/gen-test-schema.py',
      temporary + '/schema-v538.sql', temporary + '/migrations'], {stdio: 'pipe', env});
    // The UUID belongs to this attempt even when Docker creates it but its response is lost.
    attempted = true;
    docker(['run', '--rm', '--pull', 'never', '--detach', '--name', name, '--publish', '127.0.0.1::3306',
      '--env', 'MYSQL_ROOT_PASSWORD', '--env', 'MYSQL_ROOT_HOST=%', 'mysql:9.7.2',
      '--datadir=/var/lib/checkout-test', '--innodb-buffer-pool-size=64M', '--performance-schema=OFF']);
    let ready = false;
    for (let i = 0; i < 150; i++) {
      try { mysql('SELECT 1'); ready = true; break; }
      catch { await e.pause(300); }
    }
    assert(ready, 'Owned MySQL did not become ready');
    const address = docker(['port', name, '3306/tcp']);
    assert.match(address, /^127\.0\.0\.1:[0-9]+$/);
    mysql('CREATE DATABASE ' + database + ' CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci');
    // V538 structural fixture, not a claim of replaying every historical Flyway migration.
    const fixture = e.readFileSync(temporary + '/schema-v538.sql', 'utf8')
      .replace(/\b([A-Za-z_]\w*) IS JSON\b/g, 'JSON_VALID($1)').split(/\nINSERT (?:IGNORE )?INTO /)[0];
    mysql(fixture, database);
    mysql(migration.toString('utf8'), database);
    mysql(paymentMigration.toString('utf8'), database);
    e.log('Applied exact frozen V539 and V540 to isolated V538 structural fixture.');
    const url = 'jdbc:mysql://' + address + '/' + database
      + '?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC';
    const childEnv = {...environment,
      JAVA_HOME: '/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home',
      CHECKOUT_NATIVE_MYSQL_URL: url, CHECKOUT_NATIVE_MYSQL_USER: 'root', CHECKOUT_NATIVE_MYSQL_PASSWORD: password,
      SPRING_DATASOURCE_URL: url, SPRING_DATASOURCE_USERNAME: 'root', SPRING_DATASOURCE_PASSWORD: password,
      SPRING_DATASOURCE_DRIVER_CLASS_NAME: 'com.mysql.cj.jdbc.Driver', SPRING_SQL_INIT_MODE: 'never'};
    const reportDirectory = root + 'backend/shop-app/target/surefire-reports/';
    const nativeReport = test => reportDirectory + 'TEST-ai.neargo.shop.scenario.' + test + '.xml';
    const reportNames = new Set(selection.classes.flatMap(test => {
      const qualified = 'ai.neargo.shop.scenario.' + test;
      return ['TEST-' + qualified + '.xml', qualified + '.txt', qualified + '-output.txt'];
    }));
    const snapshot = () => {
      const entries = new Map();
      let names;
      try { names = e.readdirSync(reportDirectory); } catch (error) {
        if (error.code === 'ENOENT') return entries;
        throw error;
      }
      for (const filename of names) {
        if (!reportNames.has(filename) && !/\.dump(?:stream)?$/.test(filename)) continue;
        try { entries.set(filename, e.statSync(reportDirectory + filename)); }
        catch (error) { if (error.code !== 'ENOENT') throw error; }
      }
      return entries;
    };
    for (const test of selection.classes) e.rmSync(nativeReport(test), {force: true});
    const beforeReports = snapshot(), startedAt = e.now();
    let credentialDiscoveryComplete = false;
    const sanitizeReports = () => {
      const failures = [], discoveryFailures = [], artifacts = [];
      // Complete every eligible read and credential discovery before rewriting even the first artifact.
      for (const [filename, stat] of snapshot()) {
        const before = beforeReports.get(filename);
        if (stat.mtimeMs < startedAt || (before && before.mtimeMs === stat.mtimeMs && before.size === stat.size)) continue;
        if (!reportNames.has(filename)) {
          failures.push('Ambiguous native report ownership: ' + filename); continue;
        }
        try {
          const path = reportDirectory + filename, original = e.readFileSync(path, 'utf8');
          discoverCredentials(original);
          artifacts.push({filename, stat, path, original});
        } catch (error) { discoveryFailures.push(error.message); }
      }
      assert.equal(discoveryFailures.length, 0,
        'Native artifact credential discovery failed: ' + discoveryFailures.map(redact).join('; '));
      credentialDiscoveryComplete = true;
      for (const artifact of artifacts) {
        const {filename, path, original} = artifact;
        artifact.sanitized = redact(original);
        artifact.structureBefore = null; artifact.structureAfter = null;
        try {
          if (filename.endsWith('.xml')) {
            try { artifact.structureBefore = reportStructure(original); } catch { /* Partial failed-Maven XML. */ }
            try { artifact.structureAfter = reportStructure(artifact.sanitized); } catch { /* Retain diagnostics. */ }
            assert.deepEqual(artifact.structureAfter, artifact.structureBefore,
              'Artifact sanitization changed XML test evidence');
          }
          if (original !== artifact.sanitized) e.writeFileSync(path, artifact.sanitized);
        } catch (error) { failures.push(redact(error.message)); }
      }
      // Check all persisted artifacts against the completed set before issuing any absence certificate.
      for (const artifact of artifacts) {
        try {
          artifact.retained = e.readFileSync(artifact.path, 'utf8');
          assert.equal(artifact.retained, artifact.sanitized, 'Native artifact sanitization did not persist');
          assert.equal(redact(artifact.retained), artifact.retained, 'Native artifact still contains a credential');
        } catch (error) { failures.push(redact(error.message)); }
      }
      assert.equal(failures.length, 0, 'Native artifact sanitization failed: ' + failures.join('; '));
      const digest = value => createHash('sha256').update(value).digest('hex');
      for (const {filename, stat, path, original, retained, structureBefore, structureAfter} of artifacts) {
        const provenance = {artifact: filename, originalSha256: digest(original), sanitizedSha256: digest(retained),
          originalMtimeMs: stat.mtimeMs, startedAt, credentialsAbsent: true, structureBefore, structureAfter};
        e.writeFileSync(path + '.runner-sanitization.json', JSON.stringify(provenance, null, 2) + '\n');
        e.log('Retained native artifact sanitized: ' + filename + '; originalSHA256=' + provenance.originalSha256
          + '; sanitizedSHA256=' + provenance.sanitizedSha256);
      }
    };
    const args = ['-o', '-Dmaven.repo.local=/tmp/codex-beauty-bm01-m2', '-f', root + 'backend/pom.xml',
      '-pl', 'shop-app', '-am', 'test', '-Dtest=' + selected, '-Dsurefire.failIfNoSpecifiedTests=false',
      '-Dcheckout.test.native=true'];
    if (beautySource) args.push('-Pbeauty-embedded', '-Dbeauty.source.test.native=true');
    let result;
    try {
      result = e.spawnSync('mvn', args, {cwd: root, env: childEnv, encoding: 'utf8',
        stdio: ['ignore', 'pipe', 'pipe'], timeout: 600000, maxBuffer: 16 * 1024 * 1024});
    } finally {
      // Discover generated values before sanitizing reports, including failures or truncated child output.
      if (result?.stdout) discoverCredentials(result.stdout);
      if (result?.stderr) discoverCredentials(result.stderr);
      try { sanitizeReports(); }
      finally {
        if (credentialDiscoveryComplete) {
          if (result?.stdout) e.log(redact(result.stdout));
          if (result?.stderr) e.error(redact(result.stderr));
        } else if (result?.stdout || result?.stderr) {
          e.error('Captured Maven output withheld because native credential discovery did not complete.');
        }
      }
    }
    assert(!result.error && !result.signal && result.status === 0,
      'Checkout payment native tests failed: status=' + result.status + '; signal=' + (result.signal ?? 'none')
      + '; error=' + (result.error?.message ?? 'none'));
    for (const test of selection.classes) {
      const report = nativeReport(test);
      const counts = inspectReport(e.readFileSync(report, 'utf8'), {className: test,
        methods: selection.requestedMethods[test], startedAt, mtimeMs: e.statSync(report).mtimeMs});
      e.log('Native execution verified: ' + test + '; tests=' + counts.tests + '; skips=0.');
    }
  } catch (error) {
    discoverCredentials(error.message);
    throw new Error(redact(error.message));
  }
  finally {
    const failures = [];
    if (attempted) {
      try { docker(['rm', '--force', '--volumes', name]); e.log('Own UUID cleanup complete: ' + name); }
      catch (error) {
        if (/No such container:/i.test(error.message)) e.log('Own UUID confirmed absent: ' + name);
        else failures.push(error.message);
      }
    }
    if (temporaryAttempted) {
      try { e.rmSync(temporary, {recursive: true, force: true}); } catch (error) { failures.push(redact(error.message)); }
    }
    if (locked) {
      try { e.rmdirSync(lock); e.log('Own serial lock cleanup complete.'); }
      catch (error) { failures.push(redact(error.message)); }
    }
    if (failures.length) throw new Error('Owned cleanup failed: ' + failures.join('; '));
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch(error => { console.error(error.message); process.exitCode = 1; });
}
