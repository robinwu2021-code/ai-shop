package ai.neargo.shop.commercebridge;

import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.port.*;
import ai.neargo.shop.pay.service.*;

import java.time.Instant;
import java.util.*;

/**
 * Translates verified pay-domain receipts; never upgrades prepay, NOT_FOUND or client claims to
 * money.
 */
public final class ShopMallPlatformPaymentAdapter implements PlatformPaymentPort {
    private final PlatformCollectionService collection;
    private final PlatformRefundService refunds;
    private final ShopMallPayerBindings bindings;

    public ShopMallPlatformPaymentAdapter(
            PlatformCollectionService collection,
            PlatformRefundService refunds,
            ShopMallPayerBindings bindings) {
        this.collection = Objects.requireNonNull(collection);
        this.refunds = refunds;
        this.bindings = Objects.requireNonNull(bindings);
    }

    private PlatformCollectionService.Request original(PaymentIntentCommand c) {
        if (c != null && "OFFLINE".equals(c.channel())) return offline(c);
        if (c == null || c.buyerIdentity() == null || c.preparedAt() == null) return null;
        var raw = c.buyerIdentity();
        if (!"MERCHANT".equals(raw.realm())
                || !("USR".equals(raw.kind()) || "MCH".equals(raw.kind()))) return null;
        if (!ShopMallIdentity.buyerKey(raw.kind(), raw.rawNo()).equals(c.buyerNo())) return null;
        var proof =
                bindings.frozen(raw, c.buyerNo(), c.channel(), c.payMethod(), c.payerReference())
                        .orElse(null);
        if (proof == null
                || !raw.equals(proof.identity())
                || !Objects.equals(c.payerReference(), proof.reference())
                || proof.appId() == null
                || proof.appId().isBlank()
                || proof.providerIdentity() == null
                || proof.providerIdentity().isBlank()) return null;
        return new PlatformCollectionService.Request(
                new PlatformCollectionService.Key(c.tenantNo(), c.orderNo(), c.paymentAttemptNo()),
                c.buyerNo(),
                c.platformMerchantNo(),
                c.channel(),
                c.payMethod(),
                c.amount().minor(),
                c.amount().currency(),
                c.expiresAt().toEpochMilli(),
                c.preparedAt().toEpochMilli(),
                new PlatformCollectionService.Payer(
                        raw.realm(),
                        raw.kind(),
                        raw.rawNo(),
                        proof.appId(),
                        proof.providerIdentity()));
    }

    /**
     * Bank transfer has no payer binding: the frozen pay-domain terms are the only route evidence,
     * and the ledger re-verifies them against the current immutable profile version on issuance.
     */
    private static PlatformCollectionService.Request offline(PaymentIntentCommand c) {
        if (c.offline() == null
                || c.preparedAt() == null
                || !c.offline().collectionMethod().equals(c.payMethod())
                || !c.offline().platformMerchantNo().equals(c.platformMerchantNo())
                || !c.offline().currency().equals(c.amount().currency())) return null;
        return new PlatformCollectionService.Request(
                new PlatformCollectionService.Key(c.tenantNo(), c.orderNo(), c.paymentAttemptNo()),
                c.buyerNo(),
                c.platformMerchantNo(),
                c.channel(),
                c.payMethod(),
                c.amount().minor(),
                c.amount().currency(),
                c.expiresAt().toEpochMilli(),
                c.preparedAt().toEpochMilli(),
                null,
                ShopMallOfflineCollectionAdapter.instructions(c.offline()));
    }

    public PaymentOutcome create(PaymentIntentCommand c) {
        var request = original(c);
        if (request == null)
            return unavailable(
                    c.orderNo(),
                    c,
                    "OFFLINE".equals(c.channel()) ? "OFFLINE_INTENT_INVALID" : "PAYER_BINDING_UNPROVEN");
        return payment(collection.create(request), c);
    }

    private static PlatformCollectionService.Key key(PaymentQuery q) {
        if (q.original() != null
                && (!Objects.equals(q.tenantNo(), q.original().tenantNo())
                        || !Objects.equals(q.orderNo(), q.original().orderNo())
                        || !Objects.equals(q.paymentAttemptNo(), q.original().paymentAttemptNo())))
            throw new IllegalArgumentException("Original payment coordinates mismatch");
        return new PlatformCollectionService.Key(q.tenantNo(), q.orderNo(), q.paymentAttemptNo());
    }

    public PaymentOutcome query(PaymentQuery q) {
        return payment(collection.query(key(q)), q.original());
    }

    public PaymentOutcome close(PaymentQuery q) {
        return payment(collection.close(key(q), original(q.original())), q.original());
    }

    private static PaymentOutcome unavailable(String order, PaymentIntentCommand c, String error) {
        return new PaymentOutcome(
                order,
                Optional.empty(),
                c == null ? null : c.platformMerchantNo(),
                c == null ? null : c.amount(),
                AsyncStatus.UNKNOWN,
                Optional.empty(),
                Optional.of(error));
    }

    private static PaymentOutcome payment(
            PlatformCollectionService.Receipt r, PaymentIntentCommand original) {
        if (r.request() == null) return unavailable(r.key().orderNo(), original, r.errorCode());
        var request = r.request();
        boolean closed = "CLOSED".equals(r.state()) && r.closureReference() != null;
        AsyncStatus status =
                "SUCCESS".equals(r.state())
                        ? AsyncStatus.SUCCEEDED
                        : closed
                                ? AsyncStatus.FAILED
                                : "PENDING".equals(r.state())
                                        ? AsyncStatus.PENDING
                                        : AsyncStatus.UNKNOWN;
        return new PaymentOutcome(
                r.key().orderNo(),
                Optional.ofNullable(r.paymentNo()),
                request.merchantNo(),
                new Money(request.currency(), request.amountMinor()),
                status,
                Optional.ofNullable(r.paidAt()).map(Instant::ofEpochMilli),
                Optional.ofNullable(r.errorCode()),
                status == AsyncStatus.PENDING ? r.checkoutParameters() : Map.of(),
                closed,
                Optional.ofNullable(r.closureReference()),
                r.paidAtPrecisionMs());
    }

    public PaymentRefundOutcome refund(PaymentRefundCommand c) {
        if (refunds == null) return refundUnavailable(c.refundNo(), "REFUND_UNAVAILABLE");
        if (c.store() == null
                || !Objects.equals(c.tenantNo(), c.store().tenantNo())
                || c.originalPaid() == null
                || !Objects.equals(c.amount().currency(), c.originalPaid().currency()))
            return refundUnavailable(c.refundNo(), "REFUND_ORIGINAL_UNPROVEN");
        return refundReceipt(
                c.refundNo(),
                refunds.refund(
                        new PlatformRefundService.Request(
                                new PlatformRefundService.Key(
                                        c.tenantNo(), c.orderNo(), c.refundNo()),
                                c.store().storeNo(),
                                c.paymentAttemptNo(),
                                c.originalPaymentNo(),
                                c.platformMerchantNo(),
                                c.originalPaid().minor(),
                                c.amount().minor(),
                                c.amount().currency(),
                                c.reason())));
    }

    public PaymentRefundOutcome queryRefund(PaymentRefundQuery q) {
        return refunds == null
                ? refundUnavailable(q.refundNo(), "REFUND_UNAVAILABLE")
                : refundReceipt(
                        q.refundNo(),
                        refunds.query(
                                new PlatformRefundService.Key(
                                        q.tenantNo(), q.orderNo(), q.refundNo())));
    }

    private static PaymentRefundOutcome refundUnavailable(String no, String error) {
        return new PaymentRefundOutcome(
                no, AsyncStatus.UNKNOWN, Optional.empty(), Optional.ofNullable(error));
    }

    private static PaymentRefundOutcome refundReceipt(String no, PlatformRefundService.Receipt r) {
        var request = r.request();
        if (request == null) return refundUnavailable(no, r.errorCode());
        boolean succeeded = "SUCCESS".equals(r.state());
        var status =
                succeeded
                        ? AsyncStatus.SUCCEEDED
                        : "PENDING".equals(r.state()) ? AsyncStatus.PENDING : AsyncStatus.UNKNOWN;
        var key = request.key();
        var fact =
                succeeded
                        ? new VerifiedRefundFact(
                                key.tenantNo(),
                                r.paymentNo(),
                                key.orderNo(),
                                key.refundNo(),
                                new StoreRef(key.tenantNo(), request.storeNo()),
                                request.originalAttemptNo(),
                                request.originalPaymentNo(),
                                request.merchantNo(),
                                new Money(request.currency(), request.originalPaidMinor()),
                                new Money(request.currency(), request.refundMinor()),
                                r.providerNo(),
                                AsyncStatus.SUCCEEDED,
                                true,
                                Instant.ofEpochMilli(r.verifiedAt()),
                                "platform-ledger:" + r.paymentNo())
                        : null;
        return new PaymentRefundOutcome(
                no,
                status,
                Optional.ofNullable(r.providerNo()),
                Optional.ofNullable(r.errorCode()),
                fact);
    }
}
