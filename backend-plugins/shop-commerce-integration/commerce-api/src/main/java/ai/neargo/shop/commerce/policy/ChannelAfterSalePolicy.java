package ai.neargo.shop.commerce.policy;

import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.commerce.ChannelAfterSaleFundsPort;
import ai.neargo.shop.spi.commerce.ChannelAfterSaleGateway;
import ai.neargo.shop.spi.commerce.ChannelAfterSaleSourcePort;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway;
import ai.neargo.shop.spi.trade.IndustryChannelAfterSalePort;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;

/**
 * Scope, allocation and freshness checks bind independent original facts without inferring local
 * refund completion.
 */
public final class ChannelAfterSalePolicy {
    private ChannelAfterSalePolicy() {}

    public static void snapshot(
            ChannelOrderGateway.Query scope,
            ChannelAfterSaleGateway.Snapshot snapshot,
            Clock clock) {
        require(snapshot != null && snapshot.document() != null);
        var d = snapshot.document();
        require(
                scope.externalAccountRef().equals(d.externalAccountRef())
                        && scope.externalStoreRef().equals(d.externalStoreRef())
                        && scope.externalOrderRef().equals(d.externalOrderRef())
                        && d.type() != null
                        && d.state() != null
                        && d.currency() != null
                        && d.currency().matches("[A-Z]{3}")
                        && d.sequence() != null
                        && d.sequence() >= 0
                        && d.requestedAt() != null
                        && !d.requestedAt().isAfter(clock.instant())
                        && d.merchantRefundMinor() >= 0);
        text(d.externalCaseRef(), 128);
        text(d.revisionRef(), 128);
        text(snapshot.originalReceiptRef(), 256);
        freshness(snapshot.observedAt(), snapshot.validUntil(), clock);
        require(
                PricingSnapshotHash.of(d).equals(snapshot.contentHash())
                        && d.lines() != null
                        && !d.lines().isEmpty()
                        && d.lines().size() <= 1000);
        var seen = new HashSet<String>();
        long total = 0;
        for (var line : d.lines()) {
            require(line != null);
            text(line.externalLineRef(), 128);
            text(line.unit(), 16);
            quantity(line.quantity());
            require(seen.add(line.externalLineRef()) && line.merchantRefundMinor() >= 0);
            total = Math.addExact(total, line.merchantRefundMinor());
        }
        require(total == d.merchantRefundMinor());
    }

    public static void source(
            ChannelAfterSaleSourcePort.Query query,
            ChannelAfterSaleSourcePort.Facts facts,
            Clock clock) {
        require(facts != null && query.equals(facts.query()) && facts.decision() != null);
        var d = facts.decision();
        require(
                query.entityNo().equals(d.original().scope().entityNo())
                        && query.storeNo().equals(d.original().scope().storeNo())
                        && query.coreOrderNo().equals(d.coreOrderNo())
                        && query.externalCaseRef().equals(d.externalCaseRef())
                        && query.sourceDecisionRef().equals(d.decisionNo())
                        && query.sourceRevision().equals(facts.decisionHash())
                        && facts.decisionHash().equals(PricingSnapshotHash.of(d))
                        && d.action() != null
                        && d.refundCeilingMinor() >= 0
                        && d.authorizedAt() != null
                        && !d.authorizedAt().isAfter(clock.instant())
                        && d.currency() != null);
        freshness(facts.observedAt(), facts.validUntil(), clock);
        text(facts.verificationReceiptRef(), 256);
        text(d.originalReceiptRef(), 256);
        text(facts.localFundsStatus(), 64);
        text(facts.localInventoryStatus(), 64);
        require(
                d.lines() != null
                        && d.lines().size() <= 1000
                        && (d.action() == IndustryChannelAfterSalePort.Action.REJECT
                                ? d.lines().isEmpty()
                                : !d.lines().isEmpty()));
        var seen = new HashSet<String>();
        long amount = 0;
        for (var line : d.lines()) {
            require(line != null);
            text(line.sourceLineRef(), 128);
            text(line.componentRef(), 64);
            text(line.coreItemRef(), 128);
            require(
                    seen.add(
                                    ChannelIdentity.part(line.sourceLineRef())
                                            + ChannelIdentity.part(line.componentRef()))
                            && line.refundCeilingMinor() >= 0);
            quantity(line.physicalQuantity());
            text(line.unit(), 16);
            text(line.originalAllocationReceiptRef(), 256);
            amount = Math.addExact(amount, line.refundCeilingMinor());
        }
        require(amount == d.refundCeilingMinor());
    }

    public static void funds(
            ChannelAfterSaleFundsPort.Query query, ChannelAfterSaleFundsPort.Facts f, Clock clock) {
        require(f != null && query.equals(f.query()) && f.status() != null);
        freshness(f.observedAt(), f.validUntil(), clock);
        text(f.receiptRef(), 256);
        if (f.status() == ChannelAfterSaleFundsPort.Status.AUTHORIZED)
            text(f.originalAuthorizationRef(), 256);
        else require(f.originalAuthorizationRef() == null);
    }

    public static BigDecimal quantity(String value) {
        require(value != null && value.matches("(?:0|[1-9][0-9]{0,13})(?:\\.[0-9]{1,6})?"));
        var quantity = new BigDecimal(value);
        require(quantity.signum() > 0);
        return quantity;
    }

    public static void freshness(Instant observed, Instant until, Clock clock) {
        require(
                observed != null
                        && !observed.isAfter(clock.instant())
                        && until != null
                        && until.isAfter(clock.instant())
                        && !until.isAfter(observed.plusSeconds(30)));
    }

    private static void text(String value, int max) {
        CommerceValidation.text(value, max);
    }

    private static void require(boolean value) {
        if (!value) throw new IllegalStateException("Original after-sale evidence mismatch");
    }
}
