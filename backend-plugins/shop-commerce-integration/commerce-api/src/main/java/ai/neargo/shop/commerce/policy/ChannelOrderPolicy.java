package ai.neargo.shop.commerce.policy;

import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway.Query;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway.Snapshot;
import ai.neargo.shop.spi.commerce.ChannelResourceIdentityGateway.Identity;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Exact original arithmetic catches mismatched callbacks and overflow; it never re-prices with
 * today's retail price.
 */
public final class ChannelOrderPolicy {
    private ChannelOrderPolicy() {}

    public static void validate(Query q, Snapshot snapshot, Clock clock) {
        if (q == null || snapshot == null || snapshot.document() == null) invalid();
        CommerceValidation.text(q.entityNo(), 32);
        CommerceValidation.text(q.storeNo(), 32);
        CommerceValidation.text(q.connectionNo(), 32);
        CommerceValidation.text(q.profileNo(), 32);
        CommerceValidation.text(q.externalAccountRef(), 128);
        CommerceValidation.text(q.externalStoreRef(), 128);
        CommerceValidation.text(q.externalOrderRef(), 128);
        var d = snapshot.document();
        if (d.schemaVersion() != 1
                || !q.externalAccountRef().equals(d.externalAccountRef())
                || !q.externalStoreRef().equals(d.externalStoreRef())
                || !q.externalOrderRef().equals(d.externalOrderRef())
                || q.externalRevision() != null && !q.externalRevision().equals(d.revisionRef())
                || d.revisionSequence() != null && d.revisionSequence() < 0
                || d.catalogHash() == null
                || !d.catalogHash().matches("[a-f0-9]{64}")
                || d.currency() == null
                || !d.currency().matches("[A-Z]{3}")
                || d.state() == null
                || d.paymentState() == null
                || d.fulfillment() == null
                || d.orderedAt() == null
                || d.lines() == null
                || d.lines().isEmpty()
                || d.lines().size() > 1000
                || d.totals() == null
                || snapshot.observedAt() == null
                || snapshot.observedAt().isAfter(clock.instant())
                || snapshot.validUntil() == null
                || !snapshot.validUntil().isAfter(clock.instant())
                || snapshot.validUntil().isAfter(snapshot.observedAt().plusSeconds(30))
                || !Objects.equals(PricingSnapshotHash.of(d), snapshot.documentHash())) invalid();
        CommerceValidation.text(d.revisionRef(), 128);
        CommerceValidation.text(snapshot.originalReceiptRef(), 256);
        if (d.paymentState() == ChannelOrderGateway.PaymentState.PLATFORM_PAID)
            CommerceValidation.text(d.originalPaymentRef(), 128);
        var lines = new HashSet<String>();
        long net = 0;
        for (var line : d.lines()) {
            CommerceValidation.text(line.lineRef(), 128);
            CommerceValidation.text(line.unit(), 16);
            if (!lines.add(line.lineRef())
                    || line.grossExTaxMinor() < 0
                    || line.merchantDiscountExTaxMinor() < 0
                    || line.merchantDiscountExTaxMinor() > line.grossExTaxMinor()
                    || line.taxMinor() < 0
                    || Math.addExact(
                                    Math.subtractExact(
                                            line.grossExTaxMinor(),
                                            line.merchantDiscountExTaxMinor()),
                                    line.taxMinor())
                            != line.netMinor()) invalid();
            quantity(line.quantity());
            identity(line.offerIdentity());
            identity(line.skuIdentity());
            if (line.selections().size() > 200) invalid();
            var choices = new HashSet<String>();
            for (var selection : line.selections()) {
                CommerceValidation.text(selection.lineRef(), 128);
                CommerceValidation.text(selection.kind(), 32);
                if (!choices.add(selection.lineRef())
                        || selection.amountMinor() < 0
                        || !Set.of("OPTION", "SKU", "BUNDLE_MEMBER").contains(selection.kind()))
                    invalid();
                quantity(selection.quantity());
                identity(selection.externalIdentity());
            }
            net = Math.addExact(net, line.netMinor());
        }
        var t = d.totals();
        if (t.merchantChargesMinor() < 0
                || t.platformSubsidyMinor() < 0
                || t.platformFeesMinor() < 0
                || t.deliveryFeesMinor() < 0
                || t.merchantReceivableMinor() < 0
                || t.customerPayableMinor() < 0
                || Math.addExact(net, t.merchantChargesMinor()) != t.merchantReceivableMinor()
                || Math.addExact(
                                Math.addExact(
                                        Math.subtractExact(
                                                t.merchantReceivableMinor(),
                                                t.platformSubsidyMinor()),
                                        t.platformFeesMinor()),
                                t.deliveryFeesMinor())
                        != t.customerPayableMinor()) invalid();
    }

    public static void quantity(String value) {
        if (value == null || !value.matches("(?:0|[1-9][0-9]{0,8})(?:\\.[0-9]{1,6})?")) invalid();
        var quantity = new BigDecimal(value);
        if (quantity.signum() <= 0 || quantity.compareTo(new BigDecimal("999999")) > 0) invalid();
    }

    public static void identity(Identity identity) {
        if (identity == null || identity.parents() == null || identity.parents().size() > 8)
            invalid();
        CommerceValidation.text(identity.externalRef(), 128);
        var seen = new HashSet<String>();
        for (var parent : identity.parents()) {
            if (parent == null || parent.objectType() == null) invalid();
            CommerceValidation.text(parent.externalRef(), 128);
            if (!seen.add(parent.objectType() + ":" + parent.externalRef())) invalid();
        }
    }

    private static void invalid() {
        throw new IllegalStateException("Invalid original platform order evidence");
    }
}
