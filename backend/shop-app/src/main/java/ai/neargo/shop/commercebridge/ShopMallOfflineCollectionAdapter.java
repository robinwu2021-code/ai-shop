package ai.neargo.shop.commercebridge;

import ai.neargo.commerce.api.offline.OfflineCollectionTerms;
import ai.neargo.commerce.api.common.StoreRef;
import ai.neargo.commerce.api.offline.OfflinePurchaseFacade;
import ai.neargo.commerce.api.port.OfflineCollectionPort;
import ai.neargo.commerce.api.port.PaymentIntentCommand;
import ai.neargo.shop.pay.service.PlatformCollectionService;
import ai.neargo.shop.pay.service.PlatformRefundService;

import java.util.*;
import java.util.function.Function;

/**
 * Translates the commerce manual-rail port onto the pay domain's single ledger. It never decides a
 * disposition itself: MATCHED / LATE / UNMATCHED come only from {@link PlatformCollectionService}
 * after it has written (or found) the real money row. Called outside commerce transactions.
 */
public final class ShopMallOfflineCollectionAdapter implements OfflineCollectionPort {
    private final Function<StoreRef,List<Collector>> collectors;
    @Override public List<Collector> collectors(StoreRef store) { return collectors.apply(store); }
    private final PlatformCollectionService collection;
    private final PlatformRefundService refunds;
    private final String recipient;

    public ShopMallOfflineCollectionAdapter(PlatformCollectionService collection) {
        this(collection, null, null);
    }

    public ShopMallOfflineCollectionAdapter(
            PlatformCollectionService collection, PlatformRefundService refunds) {
        this(collection, refunds, null);
    }

    /** @param recipient configured platform merchant; profiles for any other receiver are refused */
    public ShopMallOfflineCollectionAdapter(
            PlatformCollectionService collection, PlatformRefundService refunds, String recipient) {
        this(collection,refunds,recipient,store->List.of());
    }

    public ShopMallOfflineCollectionAdapter(PlatformCollectionService collection,PlatformRefundService refunds,
            String recipient,Function<StoreRef,List<Collector>> collectors) {
        this.collectors=Objects.requireNonNull(collectors);
        this.collection = Objects.requireNonNull(collection);
        this.refunds = refunds;
        this.recipient = recipient;
    }

    @Override
    public OfflineCollectionTerms configure(
            String tenant, String operatorNo, OfflinePurchaseFacade.ProfileCommand c) {
        if (c == null) throw new IllegalArgumentException("OFFLINE_PROFILE_INVALID");
        if (recipient == null || !recipient.equals(c.platformMerchantNo()))
            throw new IllegalArgumentException("OFFLINE_PROFILE_MERCHANT_MISMATCH");
        return terms(
                collection.configureOffline(
                        new PlatformCollectionService.OfflineProfile(
                                tenant,
                                c.profileNo(),
                                c.expectedVersion(),
                                c.platformMerchantNo(),
                                c.currency(),
                                c.bankCode(),
                                c.bankAccount(),
                                c.accountName(),
                                c.paymentWindowVersion(),
                                c.paymentWindowMs(),
                                c.enabled(),
                                operatorNo,
                                c.reason(),c.collectionMethod(),c.contactInstructions())));
    }

    static OfflineCollectionTerms terms(PlatformCollectionService.OfflineInstructions i) {
        return new OfflineCollectionTerms(
                i.profileNo(),
                i.version(),
                i.merchantNo(),
                i.currency(),
                i.receivingAccountKey(),
                i.paymentWindowVersion(),
                i.paymentWindowMs(),
                i.instructions());
    }

    static PlatformCollectionService.OfflineInstructions instructions(OfflineCollectionTerms t) {
        return new PlatformCollectionService.OfflineInstructions(
                t.profileNo(),
                t.profileVersion(),
                t.platformMerchantNo(),
                t.currency(),
                t.receivingAccountKey(),
                t.paymentWindowVersion(),
                t.paymentWindowMs(),
                t.instructions());
    }

    private static PlatformCollectionService.Key key(PaymentIntentCommand original) {
        if (original == null
                || original.offline() == null
                || !"OFFLINE".equals(original.channel())
                || !original.offline().collectionMethod().equals(original.payMethod()))
            throw new IllegalArgumentException("OFFLINE_INTENT_REQUIRED");
        return new PlatformCollectionService.Key(
                original.tenantNo(), original.orderNo(), original.paymentAttemptNo());
    }

    @Override
    public Optional<OfflineCollectionTerms> terms(String tenant, String profile, String currency) {
        return collection
                .offlineInstructions(tenant, profile, currency)
                .filter(i -> i.currency().equals(currency))
                .map(ShopMallOfflineCollectionAdapter::terms);
    }

    @Override
    public Optional<ai.neargo.commerce.api.offline.OfflineCollectionProfile> profile(String tenant, String profile, String currency) {
        return collection.offlineProfile(tenant, profile, currency)
                .map(p -> new ai.neargo.commerce.api.offline.OfflineCollectionProfile(terms(p.instructions()), p.enabled()));
    }

    @Override
    public ManualResult confirm(ManualPayment c) {
        if (c == null || c.actualAmount() == null || c.actualPaidAt() == null || c.confirmedAt() == null)
            throw new IllegalArgumentException("OFFLINE_PROOF_REQUIRED");
        var receipt =
                collection.confirmOffline(
                        new PlatformCollectionService.OfflineProof(
                                key(c.original()),
                                c.operatorNo(),
                                c.actualReceiver(),
                                c.actualAmount().minor(),
                                c.actualAmount().currency(),
                                c.actualPaidAt().toEpochMilli(),
                                c.bankReference(),
                                c.evidenceReference(),
                                c.testPayment() ? "TEST" : c.historical() ? "HISTORICAL" : "NORMAL",
                                c.confirmedAt().toEpochMilli(),c.collectorNo(),c.collectionReference()));
        if (receipt == null || receipt.receipt() == null)
            throw new IllegalStateException("OFFLINE_MONEY_UNKNOWN");
        return new ManualResult(receipt.disposition(), receipt.receipt().paymentNo());
    }

    @Override
    public void payout(RefundPayout p) {
        if (refunds == null) throw new IllegalStateException("REFUND_UNAVAILABLE");
        if (p == null || p.actualAmount() == null || p.paidOutAt() == null || p.confirmedAt() == null)
            throw new IllegalArgumentException("OFFLINE_PAYOUT_REQUIRED");
        refunds.confirmOfflinePayout(
                new PlatformRefundService.OfflinePayout(
                        new PlatformRefundService.Key(p.tenantNo(), p.orderNo(), p.refundNo()),
                        p.operatorNo(),
                        p.actualAmount().minor(),
                        p.actualAmount().currency(),
                        p.paidOutAt().toEpochMilli(),
                        p.payeeReference(),
                        p.bankReference(),
                        p.evidenceReference(),
                        p.confirmedAt().toEpochMilli()));
    }

    @Override
    public List<CashReceipt> receipts(String tenant, String orderNo) {
        return collection.receipts(tenant, orderNo).stream().map(r -> new CashReceipt(
                r.paymentNo(), r.paymentAttemptNo(), r.platformMerchantNo(),
                new ai.neargo.commerce.api.common.Money(r.currency(), r.amountMinor()),
                r.refundedMinor(), r.pendingRefund(), r.exceptionCash(), r.closed())).toList();
    }

    @Override
    public void reconcile(Reconciliation c) {
        if (c == null || c.reconciledAt() == null)
            throw new IllegalArgumentException("OFFLINE_RECONCILIATION_REQUIRED");
        collection.reconcileOffline(
                new PlatformCollectionService.OfflineVoid(
                        key(c.original()),
                        c.operatorNo(),
                        c.reference(),
                        c.reason(),
                        c.evidenceReference(),
                        c.reconciledAt().toEpochMilli(),
                        c.unmatchedDispositions()));
    }
}
