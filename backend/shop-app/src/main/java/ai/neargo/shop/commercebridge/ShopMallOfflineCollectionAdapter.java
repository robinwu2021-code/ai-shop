package ai.neargo.shop.commercebridge;

import ai.neargo.commerce.api.offline.OfflineCollectionTerms;
import ai.neargo.commerce.api.port.OfflineCollectionPort;
import ai.neargo.commerce.api.port.PaymentIntentCommand;
import ai.neargo.shop.pay.service.PlatformCollectionService;
import ai.neargo.shop.pay.service.PlatformRefundService;

import java.util.*;

/**
 * Translates the commerce manual-rail port onto the pay domain's single ledger. It never decides a
 * disposition itself: MATCHED / LATE / UNMATCHED come only from {@link PlatformCollectionService}
 * after it has written (or found) the real money row. Called outside commerce transactions.
 */
public final class ShopMallOfflineCollectionAdapter implements OfflineCollectionPort {
    private final PlatformCollectionService collection;
    private final PlatformRefundService refunds;

    public ShopMallOfflineCollectionAdapter(PlatformCollectionService collection) {
        this(collection, null);
    }

    public ShopMallOfflineCollectionAdapter(
            PlatformCollectionService collection, PlatformRefundService refunds) {
        this.collection = Objects.requireNonNull(collection);
        this.refunds = refunds;
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
                || !"BANK_TRANSFER".equals(original.payMethod()))
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
                                c.historical() ? "HISTORICAL" : "NORMAL",
                                c.confirmedAt().toEpochMilli()));
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
