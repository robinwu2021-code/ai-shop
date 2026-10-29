package ai.neargo.shop.commerce.policy;

import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.commerce.ChannelStatementGateway;
import ai.neargo.shop.spi.commerce.ChannelStatementGateway.Breakdown;
import ai.neargo.shop.spi.commerce.ChannelStatementGateway.Document;
import ai.neargo.shop.spi.commerce.ChannelStatementGateway.Lookup;
import ai.neargo.shop.spi.commerce.ChannelStatementGateway.Receipt;

import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Exact original signed minor amounts and original component sums; no inferred bank receipt or
 * local paid status.
 */
public final class ChannelStatementPolicy {
    private ChannelStatementPolicy() {}

    public static void lookup(Lookup lookup) {
        require(lookup != null && lookup.scope() != null);
        var s = lookup.scope();
        CommerceValidation.text(lookup.operationNo(), 32);
        CommerceValidation.text(s.entityNo(), 32);
        CommerceValidation.text(s.accountNo(), 32);
        CommerceValidation.text(s.profileNo(), 32);
        CommerceValidation.text(s.externalAccountRef(), 128);
        CommerceValidation.text(s.authorizationNo(), 32);
        CommerceValidation.text(s.credentialRef(), 256);
        CommerceValidation.text(lookup.statementRef(), 128);
        CommerceValidation.text(lookup.sourceRevision(), 128);
        require(lookup.documentHash() != null && lookup.documentHash().matches("[a-f0-9]{64}"));
    }

    public static void document(Document d, Clock clock) {
        require(d != null);
        CommerceValidation.text(d.statementRef(), 128);
        CommerceValidation.text(d.sourceRevision(), 128);
        CommerceValidation.text(d.profileNo(), 32);
        CommerceValidation.text(d.externalAccountRef(), 128);
        require(
                d.currency() != null
                        && d.currency().matches("[A-Z]{3}")
                        && d.periodStart() != null
                        && d.periodEnd() != null
                        && d.periodStart().isBefore(d.periodEnd())
                        && d.issuedAt() != null
                        && !d.issuedAt().isAfter(clock.instant())
                        && !d.periodEnd().isAfter(d.issuedAt())
                        && d.lines() != null
                        && !d.lines().isEmpty()
                        && d.lines().size() <= 10000);
        var seen = new HashSet<String>();
        long[] sum = new long[7];
        for (var line : d.lines()) {
            require(line != null && seen.add(line.lineRef()));
            CommerceValidation.text(line.lineRef(), 128);
            require(
                    Set.of("SALE", "REFUND", "FEE", "AFTER_SALE_FEE", "ADJUSTMENT")
                            .contains(line.kind()));
            if (line.externalStoreRef() != null)
                CommerceValidation.text(line.externalStoreRef(), 128);
            if (line.externalOrderRef() != null)
                CommerceValidation.text(line.externalOrderRef(), 128);
            if (line.externalAfterSaleRef() != null)
                CommerceValidation.text(line.externalAfterSaleRef(), 128);
            var values = amounts(line.amounts());
            require(values[0] >= 0 && values[1] >= 0 && values[3] >= 0);
            if ("FEE".equals(line.kind()) || "AFTER_SALE_FEE".equals(line.kind())) {
                require(
                        values[0] == 0
                                && values[1] == 0
                                && values[2] == 0
                                && values[3] == 0
                                && values[5] == 0);
                if ("AFTER_SALE_FEE".equals(line.kind()))
                    require(line.externalAfterSaleRef() != null);
            } else if ("ADJUSTMENT".equals(line.kind())) {
                require(
                        values[0] == 0
                                && values[1] == 0
                                && values[2] == 0
                                && values[3] == 0
                                && values[4] == 0);
            } else if ("REFUND".equals(line.kind())) {
                require(
                        values[0] == 0
                                && values[1] == 0
                                && values[4] == 0
                                && values[5] == 0
                                && line.externalAfterSaleRef() != null);
            } else
                require(
                        values[3] == 0
                                && values[4] == 0
                                && values[5] == 0
                                && values[1] <= values[0]);
            for (int i = 0; i < 7; i++) sum[i] = Math.addExact(sum[i], values[i]);
        }
        var totals = amounts(d.totals());
        for (int i = 0; i < 7; i++) require(sum[i] == totals[i]);
    }

    public static void receipt(Lookup lookup, Receipt receipt, Clock clock) {
        lookup(lookup);
        require(
                receipt != null
                        && receipt.status() != null
                        && lookup.operationNo().equals(receipt.operationNo())
                        && PricingSnapshotHash.of(lookup).equals(receipt.lookupHash()));
        if (receipt.status() == ChannelStatementGateway.Status.UNKNOWN) {
            require(receipt.document() == null && receipt.documentHash() == null);
            return;
        }
        CommerceValidation.text(receipt.originalReceiptRef(), 256);
        if (receipt.status() == ChannelStatementGateway.Status.REJECTED) {
            require(receipt.document() == null);
            return;
        }
        var d = receipt.document();
        document(d, clock);
        require(
                lookup.statementRef().equals(d.statementRef())
                        && lookup.sourceRevision().equals(d.sourceRevision())
                        && lookup.scope().profileNo().equals(d.profileNo())
                        && lookup.scope().externalAccountRef().equals(d.externalAccountRef())
                        && lookup.documentHash().equals(receipt.documentHash())
                        && receipt.documentHash().equals(PricingSnapshotHash.of(d)));
    }

    private static long[] amounts(Breakdown b) {
        require(b != null);
        var strings =
                List.of(
                        b.saleExTaxMinor(),
                        b.discountMinor(),
                        b.taxMinor(),
                        b.refundMinor(),
                        b.feeMinor(),
                        b.adjustmentMinor(),
                        b.netMinor());
        var values = new long[7];
        for (int i = 0; i < 7; i++) {
            require(strings.get(i).matches("(?:0|-?[1-9][0-9]{0,14})"));
            values[i] = Long.parseLong(strings.get(i));
        }
        long net = Math.subtractExact(values[0], values[1]);
        net = Math.addExact(net, values[2]);
        net = Math.subtractExact(net, values[3]);
        net = Math.subtractExact(net, values[4]);
        net = Math.addExact(net, values[5]);
        require(net == values[6]);
        return values;
    }

    private static void require(boolean valid) {
        CatalogDocumentPolicy.require(valid);
    }
}
