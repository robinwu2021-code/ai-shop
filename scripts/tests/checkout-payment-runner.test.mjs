import {test} from 'node:test';
import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
import {readFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {validateSelection, inspectReport, main} from '../verify-checkout-payment.mjs';

// Empty PATH makes the old provisioning path incapable of executing any resource tool.
test('rejectsInvalidSelectionBeforeAnyEffect', () => {
  for (const [args, selected] of [[[], 'UnknownTest'], [[], ''], [['--unknown'], 'PaymentOperationNativeMysqlTest']]) {
    const child = spawnSync(process.execPath, ['scripts/verify-checkout-payment.mjs', ...args], {
      encoding: 'utf8', env: {PATH: '', CHECKOUT_PAYMENT_TESTS: selected}
    });
    assert.notEqual(child.status, 0);
    assert.match(child.stderr, /invalid checkout (selection|arguments)/);
    assert.doesNotMatch(child.stderr, /ENOENT|limactl|docker/);
  }
});

const known = {
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
  "CheckoutPlaceAuthorityFlowTest": [
    "revokedDuringPlacePreflightCannotClaimOrCreateOrder",
    "disabledDuringPlacePreflightCannotClaimOrCreateOrder",
    "revokedAfterPlaceClaimCannotReachWriterAndOriginalKeyRecovers"
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
  "BeautySourceCheckoutIT": [
    "realGuestClaimThenCurrentConsumerPurchaseFeedsBindingAndBill",
    "lostQuoteResponseRecoversRealOriginalUnderCurrentManager",
    "closedSourceRetainsActualLateReceiptWithoutBindingOrExecution",
    "realOfflineCanonicalReceiptAndRefundBillRemainOwnerFacts"
  ]
};

const primary = 'PaymentOperationNativeMysqlTest';
const beauty = 'BeautySourceCheckoutIT';
const lock = '/tmp/codex-beauty-bm04-native.lock';
const fqcn = name => 'ai.neargo.shop.scenario.' + name;
function xml(name, names = [known[name][0]]) {
  return `<testsuite name="${fqcn(name)}" tests="${names.length}" failures="0" errors="0" skipped="0">`
    + names.map(method => `<testcase classname="${fqcn(name)}" name="${method}"/>`).join('') + '</testsuite>';
}
function fake(options = {}) {
  const events = [], children = [], logs = [], removed = [], unlocked = [];
  const reportDir = new URL('../../backend/shop-app/target/surefire-reports/', import.meta.url).pathname;
  const files = new Map(Object.entries(options.initialFiles ?? {}));
  let selection, artifactWritten = false;
  const effects = {
    randomUUID() { events.push('uuid'); return '9c1958d4-36ad-4395-9673-1c6e3500b326'; },
    randomBytes(n) { events.push('secret'); return Buffer.alloc(n, 79); },
    mkdirSync(path) {
      events.push(['mkdir', path]);
      if (path === lock && options.foreignLock) throw new Error('EEXIST foreign lock');
    },
    rmdirSync(path) { unlocked.push(path); },
    rmSync(path) { files.delete(path); removed.push(path); events.push(['rm', path]); },
    writeFileSync(path, value) {
      events.push(['write', path]);
      if (path.startsWith(reportDir) && /\.(xml|txt)$/.test(path)) artifactWritten = true;
      if (options.failSanitize && path.endsWith('.xml')) throw new Error('simulated artifact write failure');
      files.set(path, String(value));
    },
    readdirSync(path) { return path.replace(/\/$/, '') === reportDir.replace(/\/$/, '')
      ? [...files.keys()].filter(file => file.startsWith(reportDir)).map(file => file.slice(reportDir.length))
        .sort((a, b) => options.reverseArtifactOrder ? b.localeCompare(a) : a.localeCompare(b)) : []; },
    readFileSync(path, encoding) {
      events.push(['read', path]);
      if (path.startsWith(reportDir) && (path.endsWith(options.failReadArtifact ?? '\0')
        || (artifactWritten && path.endsWith(options.failReadAfterArtifactWrite ?? '\0')))) {
        throw new Error('simulated artifact discovery/verification read failure');
      }
      if (files.has(path)) return encoding ? files.get(path) : Buffer.from(files.get(path));
      if (path.startsWith(reportDir)) throw Object.assign(new Error('ENOENT missing fresh XML'), {code: 'ENOENT'});
      if (path.endsWith('/schema-v538.sql')) return 'CREATE TABLE fixture(id int);';
      return readFileSync(path, encoding);
    },
    statSync(path) {
      if (!files.has(path)) throw Object.assign(new Error('ENOENT missing artifact'), {code: 'ENOENT'});
      return {mtimeMs: options.stale ? 999 : 1001, size: Buffer.byteLength(files.get(path))};
    }, now() { return 1000; },
    async pause() {}, log(message) { logs.push(message); },
    execFileSync(command, args, config) {
      children.push({command, args, config}); events.push([command, ...args]);
      if (command === 'limactl') return 'MemAvailable:       524288 kB\n';
      if (command === 'python3') return '';
      if (args[2] === 'run' && options.createLoss) throw new Error('creation response lost');
      if (args[2] === 'rm' && options.removeError) throw new Error(options.removeError);
      if (args[2] === 'port') return '127.0.0.1:34971\n';
      return '';
    },
    spawnSync(command, args, config) {
      children.push({command, args, config}); events.push([command, ...args]);
      const selected = args.find(arg => arg.startsWith('-Dtest=')).slice(7);
      selection = validateSelection(args.includes('-Pbeauty-embedded') ? 'beauty-source' : 'payment', selected);
      for (const name of selection.classes) {
        const requested = selection.requestedMethods[name];
        let report = options.report ?? xml(name, requested.length ? requested : [known[name][0]]);
        if (options.withArtifacts) {
          const output = 'WARN retained warning\nUsing generated security password: temporary-generated-fixture-value\n'
            + 'runner credential: ' + Buffer.alloc(24, 79).toString('base64url') + '\n'
            + 'repeated default credential: temporary-generated-fixture-value\n';
          report = report.replace('</testsuite>', '<system-out><![CDATA[' + output + ']]></system-out></testsuite>');
          files.set(reportDir + fqcn(name) + '.txt', output);
          files.set(reportDir + fqcn(name) + '-output.txt', output);
        }
        if (!options.missingReport) files.set(reportDir + 'TEST-' + fqcn(name) + '.xml', report);
      }
      for (const [filename, content] of Object.entries(options.artifacts ?? {})) files.set(reportDir + filename, content);
      if (options.freshAmbiguousDump) files.set(reportDir + 'unknown-jvmRun1.dumpstream', 'unknown owner output');
      if (options.spawnThrows) throw new Error('simulated process effect exception');
      return options.result ?? {status: 0, signal: null};
    }
  };
  return {effects, events, children, logs, removed, unlocked, files, reportDir};
}

test('invalidModesFlagsAndSelectorsHaveZeroEffects', async () => {
  const [one, two] = known[primary];
  const invalid = [null, 7, '', ' ', primary + ' ', ' ' + primary, 'UnknownTest', '*', primary + '#',
    primary + '#helper', primary + '#*', primary + '##' + one, primary + '#+' + one,
    primary + '#' + one + '+', primary + '#' + one + '++' + two, primary + '#' + one + '+' + one,
    primary + '#' + one + '+unknown', primary + ',', ',' + primary, primary + ',,' + primary,
    primary + ',' + primary, primary + ',' + primary + '#' + one,
    primary + '#' + one + ',' + primary, primary + '#' + one + ',' + primary + '#' + one,
    beauty, primary + ',' + beauty, primary + '#' + one + '\n'];
  const effects = new Proxy({}, {ownKeys() { assert.fail('effect boundary was reached'); }});
  for (const selected of invalid) {
    assert.throws(() => validateSelection('payment', selected), /invalid checkout selection/);
    await assert.rejects(main([], {CHECKOUT_PAYMENT_TESTS: selected}, effects), /invalid checkout selection/);
  }
  for (const selected of [primary, beauty + ',' + beauty, beauty + '#unknown', beauty + '#']) {
    await assert.rejects(main(['--beauty-source'], {CHECKOUT_PAYMENT_TESTS: selected}, effects), /invalid checkout/);
  }
  for (const args of [['--unknown'], ['--beauty-source', '--beauty-source'], ['--beauty-source', '--unknown']]) {
    await assert.rejects(main(args, {}, effects), /invalid checkout arguments/);
  }
  assert.throws(() => validateSelection('unknown', primary), /invalid checkout selection/);
});

test('usesDefaultsOnlyWhenVariableIsAbsent', () => {
  assert.deepEqual(validateSelection('payment', undefined).classes, [primary]);
  assert.deepEqual(validateSelection('beauty-source', undefined).classes, [beauty]);
  for (const value of ['', ' ', null, false, 0]) assert.throws(() => validateSelection('payment', value));
});

test('acceptsOnlyReviewedClaimedGuestResourceSelectors', async () => {
  const name = 'BeautyClaimedGuestResourceIT';
  const methods = ['claimedGuestReplacementUsesCanonicalUserAndPreservesReceipts',
    'revokedUsrBookingGrantCannotReplaceClaimedGuestAppointment',
    'failedClaimedGuestReplacementPreservesOriginalResourceHold'];
  assert.deepEqual(validateSelection('beauty-source', name).classes, [name]);
  for (const method of methods) {
    assert.deepEqual(validateSelection('beauty-source', name + '#' + method).requestedMethods[name], [method]);
    assert.throws(() => validateSelection('payment', name + '#' + method));
    assert.throws(() => validateSelection('beauty-source', name + '#' + method + 'Typo'));
    const f = fake({report: xml(name, [method])});
    await main(['--beauty-source'], {CHECKOUT_PAYMENT_TESTS: name + '#' + method}, f.effects);
    assert(f.children.find(child => child.command === 'mvn').args.includes('-Dtest=' + name + '#' + method));
  }
  assert.throws(() => validateSelection('payment', name));
  assert.throws(() => validateSelection('beauty-source', 'BeautyUnknownIT'));
  assert.throws(() => validateSelection('beauty-source', name + '#setup'));
  assert.throws(() => validateSelection('beauty-source', name + ',' + beauty));
  assert.deepEqual(validateSelection('beauty-source', name + '#' + methods.join('+')).requestedMethods[name], methods);
  assert.equal(inspectReport(xml(name, methods), {className: name, methods}).tests, 3);
});

test('acceptsOnlyExactPlaceAuthoritySelectorsBeforeProvisioning', async () => {
  const name = 'CheckoutPlaceAuthorityFlowTest', methods = known[name];
  assert.deepEqual(validateSelection('payment', name).classes, [name]);
  assert.deepEqual(validateSelection('payment', name + '#' + methods.join('+')).requestedMethods[name], methods);
  const all = fake({report: xml(name, methods)});
  await main([], {CHECKOUT_PAYMENT_TESTS: name}, all.effects);
  assert(all.logs.includes('Native execution verified: ' + name + '; tests=3; skips=0.'));
  for (const method of methods) {
    const f = fake({report: xml(name, [method])});
    await main([], {CHECKOUT_PAYMENT_TESTS: name + '#' + method}, f.effects);
    assert(f.children.find(child => child.command === 'mvn').args.includes('-Dtest=' + name + '#' + method));
  }
  const invalid = [name + 'Typo', 'Prefix' + name, name + '#setup', name + '#*', name + '#',
    name + ',' + name, name + '#' + methods[0] + '+' + methods[0],
    ...methods.flatMap(method => [name + '#prefix' + method, name + '#' + method + 'Suffix']),
    name + ',BeautySourceCheckoutIT'];
  const effects = new Proxy({}, {ownKeys() { assert.fail('effect boundary was reached'); }});
  for (const selected of invalid) {
    await assert.rejects(main([], {CHECKOUT_PAYMENT_TESTS: selected}, effects), /invalid checkout selection/);
    const child = spawnSync(process.execPath, ['scripts/verify-checkout-payment.mjs'], {
      encoding: 'utf8', env: {PATH: '', CHECKOUT_PAYMENT_TESTS: selected}
    });
    assert.notEqual(child.status, 0);
    assert.match(child.stderr, /invalid checkout selection/);
    assert.doesNotMatch(child.stderr, /ENOENT|limactl|docker/);
  }
  for (const selected of [name, ...methods.map(method => name + '#' + method)]) {
    await assert.rejects(main(['--beauty-source'], {CHECKOUT_PAYMENT_TESTS: selected}, effects),
      /invalid checkout selection/);
  }
  for (const content of [xml(name, methods.slice(1)), xml(name, methods).replace('tests="3"', 'tests="0"'),
    xml(name, methods).replace('skipped="0"', 'skipped="1"')]) {
    assert.throws(() => inspectReport(content, {className: name, methods}));
  }
});

test('acceptsEachKnownClassAndExplicitMethod', () => {
  for (const [name, methods] of Object.entries(known)) {
    const mode = name === beauty ? 'beauty-source' : 'payment';
    assert.deepEqual(validateSelection(mode, name).classes, [name]);
    for (const method of methods) {
      assert.deepEqual(validateSelection(mode, name + '#' + method).requestedMethods[name], [method]);
      assert.throws(() => validateSelection(mode, name + '#' + method + 'Typo'));
    }
  }
  for (const helper of ['stalePreparedAlias', 'assertLateSuccessfulOriginalBlocksUnsentReplay', 'openUser']) {
    for (const name of [primary, beauty]) assert.throws(() => validateSelection(name === beauty ? 'beauty-source' : 'payment',
      name + '#' + helper));
  }
});

test('preservesPlusSelectionsAndChecksEveryRequestedMethod', async () => {
  for (const name of [primary, beauty]) {
    const [a, b] = known[name], selected = name + '#' + a + '+' + b;
    const mode = name === beauty ? 'beauty-source' : 'payment';
    const selection = validateSelection(mode, selected);
    assert.deepEqual(selection.selectors, [selected]);
    assert.deepEqual(selection.requestedMethods[name], [a, b]);
    assert.equal(inspectReport(xml(name, [a, b]), {className: name, methods: [a, b]}).tests, 2);
    assert.throws(() => inspectReport(xml(name, [a]), {className: name, methods: [a, b]}), /missing requested method/);
    const f = fake(); await main(name === beauty ? ['--beauty-source'] : [], {CHECKOUT_PAYMENT_TESTS: selected}, f.effects);
    assert(f.children.find(child => child.command === 'mvn').args.includes('-Dtest=' + selected));
  }
  const name = primary, method = known[name][0];
  for (const suffix of ['(boolean)[1]', '[2]', '(String, int)[3] display']) {
    assert.equal(inspectReport(xml(name, [method + suffix]), {className: name, methods: [method]}).tests, 1);
  }
  for (const suffix of ['Other', 'Extra(boolean)[1]', '[oops]', '(boolean)Suffix']) {
    assert.throws(() => inspectReport(xml(name, [method + suffix]), {className: name, methods: [method]}));
  }
});

test('acceptsPaymentMultiClassAndDisjointSameClassSelections', async () => {
  const other = 'CheckoutNativeMysqlTest', [a, b, c] = known[primary];
  const selected = primary + '#' + a + '+' + b + ',' + other + ',' + primary + '#' + c;
  const selection = validateSelection('payment', selected);
  assert.deepEqual(selection.classes, [primary, other]);
  assert.deepEqual(selection.requestedMethods, {[primary]: [a, b, c], [other]: []});
  const f = fake(); await main([], {CHECKOUT_PAYMENT_TESTS: selected}, f.effects);
  assert.equal(f.removed.filter(path => path.endsWith('.xml')).length, 2);
  assert.equal(f.logs.filter(line => line.startsWith('Native execution verified:')).length, 2);
  const commandAt = f.events.findIndex(event => Array.isArray(event) && event[0] === 'mvn');
  for (const name of selection.classes) {
    const deleted = f.events.findIndex(event => Array.isArray(event) && event[0] === 'rm' && event[1].endsWith(name + '.xml'));
    assert(deleted >= 0 && deleted < commandAt);
  }
});

test('rejectsStaleZeroSkippedAndMissingMethodReports', async () => {
  const method = known[primary][0], requested = {className: primary, methods: [method], startedAt: 1000, mtimeMs: 1001};
  const good = xml(primary, [method]);
  assert.equal(inspectReport(good, requested).tests, 1);
  for (const bad of [good.replace('tests="1"', 'tests="0"'), good.replace('skipped="0"', 'skipped="1"'),
    good.replace('failures="0"', 'failures="1"'), good.replace('errors="0"', 'errors="1"'),
    good.replace('name="' + fqcn(primary), 'name="wrong.Class'),
    good.replace('classname="' + fqcn(primary), 'classname="wrong.Class'),
    good.replace(method + '"', method + 'Other"'), good.replace('tests="1"', 'tests="2"'),
    good.replace('</testsuite>', ''), '<testsuite/>', good.replace('/></testsuite>', '><skipped/></testcase></testsuite>')]) {
    assert.throws(() => inspectReport(bad, requested));
  }
  assert.throws(() => inspectReport(good, {...requested, mtimeMs: 999}), /fresh report/);
  for (const options of [{stale: true}, {missingReport: true}, {report: good.replace(method, 'unrequested')}]) {
    const f = fake(options);
    await assert.rejects(main([], {CHECKOUT_PAYMENT_TESTS: primary + '#' + method}, f.effects));
    assert.equal(f.unlocked.length, 1);
  }
});

test('keepsSecretsOutOfAllChildArguments', async () => {
  const f = fake(); await main(['--beauty-source'], {}, f.effects);
  const secret = Buffer.alloc(24, 79).toString('base64url');
  assert(f.children.length > 5);
  for (const child of f.children) {
    assert(!JSON.stringify(child.args).includes(secret));
    if (child.command === 'docker') {
      assert.equal(child.config.env.MYSQL_ROOT_PASSWORD, secret);
      assert.equal(child.config.env.MYSQL_PWD, secret);
      if (child.args.includes('exec')) {
        assert(child.args.includes('MYSQL_PWD')); assert.equal(typeof child.config.input, 'string');
      }
    }
    if (child.command === 'mvn') assert.equal(child.config.env.CHECKOUT_NATIVE_MYSQL_PASSWORD, secret);
  }
  assert(!JSON.stringify(f.logs).includes(secret));
  const bad = fake({removeError: 'failure ' + secret});
  await assert.rejects(main([], {}, bad.effects), error => !error.message.includes(secret) && /REDACTED/.test(error.message));
});

test('cleansOnlyItsOwnUuidAfterCreateResponseLoss', async () => {
  for (const removeError of [undefined, 'No such container: owned UUID']) {
    const f = fake({createLoss: true, removeError});
    await assert.rejects(main([], {}, f.effects), /creation response lost/);
    const commands = f.children.filter(child => child.command === 'docker');
    const create = commands.find(child => child.args[2] === 'run');
    const name = create.args[create.args.indexOf('--name') + 1];
    assert.equal(name, 'codex-checkout-payment-9c1958d4-36ad-4395-9673-1c6e3500b326');
    assert.deepEqual(commands.filter(child => child.args[2] === 'rm').map(child => child.args.slice(2)),
      [['rm', '--force', '--volumes', name]]);
    assert.deepEqual(f.unlocked, [lock]);
    assert(f.removed.includes('/tmp/' + name));
  }
  const f = fake({createLoss: true, removeError: 'daemon unavailable'});
  await assert.rejects(main([], {}, f.effects), /Owned cleanup failed.*daemon unavailable/);
  assert(!f.logs.some(line => /UUID cleanup complete|UUID confirmed absent/.test(line)));
  assert.deepEqual(f.unlocked, [lock]);
});

test('neverRemovesAnotherRunsSerialLock', async () => {
  const f = fake({foreignLock: true});
  await assert.rejects(main([], {}, f.effects), /EEXIST foreign lock/);
  assert.deepEqual(f.unlocked, []); assert.deepEqual(f.removed, []); assert.deepEqual(f.children, []);
  assert.equal(f.events.filter(event => Array.isArray(event) && event[0] === 'read').length, 0);
});

test('mavenFailureTimeoutOrSignalCannotSucceedAndAlwaysCleansOwnResources', async () => {
  for (const result of [{status: 1}, {status: null, signal: 'SIGTERM'},
    {status: 0, error: new Error('ETIMEDOUT')}, {status: 0, signal: 'SIGTERM'}]) {
    const f = fake({result});
    await assert.rejects(main([], {}, f.effects), /native tests failed/);
    assert.deepEqual(f.unlocked, [lock]);
    assert(f.children.some(child => child.command === 'docker' && child.args[2] === 'rm'));
    assert(!f.logs.some(line => line.startsWith('Native execution verified:')));
  }
});

test('redactsNativeOutputWithoutSuppressingEnvironmentWarnings', async () => {
  const secret = Buffer.alloc(24, 79).toString('base64url');
  const generated = 'temporary-generated-fixture-value';
  const f = fake({result: {status: 0, stdout: 'WARN MySQL newer than Flyway verified version\n'
    + 'Using generated security password: ' + generated + '\nrunner credential: ' + secret + '\n',
    stderr: 'WARN dynamic agent instrumentation\n'}});
  await main([], {}, {...f.effects, error: message => f.logs.push(message)});
  const output = f.logs.join('\n');
  assert(output.includes('WARN MySQL newer than Flyway verified version'));
  assert(output.includes('WARN dynamic agent instrumentation'));
  assert(output.includes('Using generated security password: [REDACTED]'));
  assert(!output.includes(secret)); assert(!output.includes(generated));
  const child = f.children.find(item => item.command === 'mvn');
  assert.equal(child.config.maxBuffer, 16 * 1024 * 1024);
  assert.equal(child.config.timeout, 600000);
});


test('requiresActualXmlTestcasesAndIgnoresCdataCommentsAndLiteralLogText', () => {
  const method = known[primary][0], good = xml(primary, [method]);
  const requested = {className: primary, methods: [method]};
  const testcase = `<testcase classname="${fqcn(primary)}" name="${method}"/>`;
  const suite = good.replace(testcase, '');
  for (const fakeBody of ['<system-out><![CDATA[' + testcase + ']]></system-out>', '<!--' + testcase + '-->',
    '<system-out>&lt;testcase classname=&quot;' + fqcn(primary) + '&quot; name=&quot;' + method + '&quot;/&gt;</system-out>']) {
    assert.throws(() => inspectReport(suite.replace('</testsuite>', fakeBody + '</testsuite>'), requested));
  }
  for (const log of ['<system-out><![CDATA[<failure> <testcase name="literal"/> </testsuite>]]></system-out>',
    '<system-err>&lt;failure&gt; &lt;testcase name=&quot;literal&quot;/&gt;</system-err>',
    '<!-- <testcase name="literal"/> <failure/> -->']) {
    assert.equal(inspectReport(good.replace('</testsuite>', log + '</testsuite>'), requested).tests, 1);
  }
});

test('rejectsMalformedXmlAndInvalidSuiteTestcaseRelationships', () => {
  const good = xml(primary), requested = {className: primary, methods: [known[primary][0]]};
  for (const bad of [good.replace('/></testsuite>', '></testsuite>'), good + '<broken>',
    good.replace('</testsuite>', '<!-- unclosed </testsuite>'), good.replace('</testsuite>', '<![CDATA[unclosed </testsuite>'),
    good.replace('name="' + fqcn(primary) + '"', 'name="' + fqcn(primary) + '" name="duplicate"'),
    good.replace('/></testsuite>', '><system-out>bad &unknown;</system-out></testcase></testsuite>'),
    good.replace('/></testsuite>', '><system-out>bad literal < text</system-out></testcase></testsuite>'),
    good.replace('<testcase', '<properties><testcase').replace('/></testsuite>', '/></properties></testsuite>'),
    good.replace('<testcase', '<system-out><testcase').replace('/></testsuite>', '/></system-out></testsuite>'),
    '<outer>' + good + '</outer>', '<!DOCTYPE testsuite [<!ENTITY x "test">]>' + good,
    good + '\u00a0', '<!-- invalid-end--->' + good,
    good.replace('tests="1"', 'tests="1" __proto__="x" __proto__="y"')]) {
    assert.throws(() => inspectReport(bad, requested));
  }
});

test('sanitizesEveryOwnedRetainedArtifactOnSuccessFailureSignalAndThrownProcess', async () => {
  for (const outcome of [{}, {result: {status: 1}}, {result: {status: null, signal: 'SIGTERM'}}, {spawnThrows: true}]) {
    const f = fake({...outcome, withArtifacts: true});
    const action = main([], {}, f.effects);
    if (Object.keys(outcome).length) await assert.rejects(action); else await action;
    const secret = Buffer.alloc(24, 79).toString('base64url');
    const artifacts = [...f.files].filter(([name]) => /\.(xml|txt)$/.test(name));
    assert.equal(artifacts.length, 3);
    for (const [path, content] of artifacts) {
      assert(!content.includes(secret)); assert(!content.includes('temporary-generated-fixture-value'));
      assert(content.includes('WARN retained warning'));
      const digest = JSON.parse(f.files.get(path + '.runner-sanitization.json'));
      assert.equal(digest.sanitizedSha256, createHash('sha256').update(content).digest('hex'));
      assert(digest.originalSha256 !== digest.sanitizedSha256); assert.equal(digest.credentialsAbsent, true);
    }
    const report = artifacts.find(([name]) => name.endsWith('.xml'));
    assert.equal(inspectReport(report[1], {className: primary, methods: [known[primary][0]]}).tests, 1);
    const digest = JSON.parse(f.files.get(report[0] + '.runner-sanitization.json'));
    assert.deepEqual(digest.structureBefore, digest.structureAfter);
    assert.deepEqual(f.unlocked, [lock]);
    assert(f.children.some(child => child.command === 'docker' && child.args[2] === 'rm'));
  }
});

test('artifactSanitationFailureCannotPassAndDoesNotRewriteForeignArtifacts', async () => {
  const f = fake({withArtifacts: true, failSanitize: true});
  await assert.rejects(main([], {}, f.effects), /artifact write failure/);
  assert.deepEqual(f.unlocked, [lock]);
  assert(f.children.some(child => child.command === 'docker' && child.args[2] === 'rm'));
  const foreign = new URL('../../backend/shop-app/target/surefire-reports/TEST-foreign.Other.xml', import.meta.url).pathname;
  const untouched = '<foreign>Using generated security password: foreign-fixture-value</foreign>';
  const g = fake({withArtifacts: true, initialFiles: {[foreign]: untouched}});
  await main([], {}, g.effects);
  assert(g.files.get(foreign) === untouched);
  assert(!g.files.has(foreign + '.runner-sanitization.json'));
  const ambiguous = fake({freshAmbiguousDump: true});
  await assert.rejects(main([], {}, ambiguous.effects), /ownership.*dumpstream|dumpstream.*ownership/i);
  assert.deepEqual(ambiguous.unlocked, [lock]);
});


test('discoversCompleteCredentialSetAcrossStreamsAndArtifactsBeforeRewritingOrCertifying', async () => {
  const generatedA = 'generated-cross-source-fixture-a', generatedB = 'generated-cross-source-fixture-b';
  const marker = value => 'Using generated security password: ' + value + '\n';
  const bare = value => 'WARN retained ordering diagnostic\nbare repeated value: ' + value + ' ' + value + '\n';
  const report = content => xml(primary).replace('</testsuite>',
    '<system-out><![CDATA[' + content + ']]></system-out></testsuite>');
  const xmlName = 'TEST-' + fqcn(primary) + '.xml', summary = fqcn(primary) + '.txt', output = fqcn(primary) + '-output.txt';
  const cases = [
    {artifacts: {[output]: bare(generatedA), [xmlName]: report(marker(generatedA))}},
    {artifacts: {[xmlName]: report(bare(generatedA)), [summary]: marker(generatedA)}},
    {artifacts: {[xmlName]: report(bare(generatedA)), [output]: bare(generatedA)}, stdout: marker(generatedA)},
    {artifacts: {[xmlName]: report(marker(generatedA))}, stdout: bare(generatedA)},
    {artifacts: {[xmlName]: report(bare(generatedA)), [output]: marker(generatedA)}, stderr: bare(generatedA)},
    {artifacts: {[xmlName]: report(bare(generatedA) + bare(generatedB)), [summary]: marker(generatedA)},
      stdout: bare(generatedB), stderr: marker(generatedB)},
    {artifacts: {[xmlName]: report(bare(generatedA))}, stdout: bare(generatedA), stderr: marker(generatedA)}
  ];
  for (const fixture of cases) for (const reverseArtifactOrder of [false, true]) {
    for (const outcome of ['success', 'nonzero', 'signal', 'throw']) {
      // A thrown spawn effect has no captured result streams; retain throw cases whose markers exist in artifacts.
      if (outcome === 'throw' && [generatedA, generatedB].some(value =>
        Object.values(fixture.artifacts).some(content => content.includes(value))
        && !Object.values(fixture.artifacts).some(content => content.includes(marker(value).trim())))) continue;
      const result = {status: outcome === 'nonzero' ? 1 : outcome === 'signal' ? null : 0,
        signal: outcome === 'signal' ? 'SIGTERM' : null, stdout: fixture.stdout ?? '', stderr: fixture.stderr ?? ''};
      const f = fake({artifacts: fixture.artifacts, reverseArtifactOrder, result, spawnThrows: outcome === 'throw'});
      const action = main([], {}, {...f.effects, error: message => f.logs.push(message)});
      if (outcome === 'success') await action; else await assert.rejects(action);
      const artifacts = [...f.files].filter(([path]) => /\.(xml|txt)$/.test(path));
      for (const [path, content] of artifacts) {
        assert(!content.includes(generatedA)); assert(!content.includes(generatedB));
        const metadata = JSON.parse(f.files.get(path + '.runner-sanitization.json'));
        assert.equal(metadata.credentialsAbsent, true);
        assert.equal(metadata.sanitizedSha256, createHash('sha256').update(content).digest('hex'));
        assert.deepEqual(metadata.structureBefore, metadata.structureAfter);
      }
      assert(!f.logs.join('\n').includes(generatedA)); assert(!f.logs.join('\n').includes(generatedB));
      const firstWrite = f.events.findIndex(event => Array.isArray(event) && event[0] === 'write'
        && event[1].startsWith(f.reportDir));
      const firstCertificate = f.events.findIndex(event => Array.isArray(event) && event[0] === 'write'
        && event[1].endsWith('.runner-sanitization.json'));
      for (const [path] of artifacts) {
        const reads = f.events.map((event, i) => Array.isArray(event) && event[0] === 'read' && event[1] === path ? i : -1)
          .filter(i => i >= 0);
        assert(reads.some(i => i < firstWrite));
        assert(reads.filter(i => i < firstCertificate).length >= 2);
      }
      assert.deepEqual(f.unlocked, [lock]);
      assert(f.children.some(child => child.command === 'docker' && child.args[2] === 'rm'));
    }
  }
});

test('failedCredentialDiscoveryCannotRewriteCertifyOrExposeCapturedBareValues', async () => {
  const generated = 'generated-unreadable-marker-fixture';
  const xmlName = 'TEST-' + fqcn(primary) + '.xml', output = fqcn(primary) + '-output.txt';
  const f = fake({artifacts: {[xmlName]: xml(primary), [output]: 'Using generated security password: ' + generated + '\n'},
    failReadArtifact: output, result: {status: 0, stdout: 'bare generated value: ' + generated, stderr: ''}});
  await assert.rejects(main([], {}, {...f.effects, error: message => f.logs.push(message)}), /artifact.*read failure/);
  assert(!f.events.some(event => Array.isArray(event) && event[0] === 'write' && event[1].startsWith(f.reportDir)));
  assert(!f.logs.join('\n').includes(generated));
  assert(!f.logs.some(line => /Native execution verified:|Retained native artifact sanitized:/.test(line)));
  assert.deepEqual(f.unlocked, [lock]);
  assert(f.children.some(child => child.command === 'docker' && child.args[2] === 'rm'));
});

test('verifiesAllPersistedArtifactsBeforeIssuingAnyCredentialAbsenceCertificate', async () => {
  const f = fake({withArtifacts: true, failReadAfterArtifactWrite: fqcn(primary) + '-output.txt'});
  await assert.rejects(main([], {}, {...f.effects, error: message => f.logs.push(message)}), /artifact.*read failure/);
  assert(![...f.files.keys()].some(path => path.endsWith('.runner-sanitization.json')));
  assert(!f.logs.some(line => /Native execution verified:|Retained native artifact sanitized:/.test(line)));
  assert.deepEqual(f.unlocked, [lock]);
  assert(f.children.some(child => child.command === 'docker' && child.args[2] === 'rm'));
});
