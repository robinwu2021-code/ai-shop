package ai.neargo.shop.commerce.policy;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.commerce.ChannelStatementGateway;
import ai.neargo.shop.spi.commerce.ChannelStatementGateway.Breakdown;
import ai.neargo.shop.spi.commerce.ChannelStatementGateway.Document;
import ai.neargo.shop.spi.commerce.ChannelStatementGateway.Line;
import ai.neargo.shop.spi.commerce.ChannelStatementGateway.Lookup;
import ai.neargo.shop.spi.commerce.ChannelStatementGateway.Receipt;
import ai.neargo.shop.spi.commerce.ChannelStatementGateway.Scope;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

class ChannelStatementPolicyTest {
    final Instant now = Instant.parse("2026-10-29T00:00:00Z");
    final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

    Breakdown amounts(
            String sale,
            String discount,
            String tax,
            String refund,
            String fee,
            String adjustment,
            String net) {
        return new Breakdown(sale, discount, tax, refund, fee, adjustment, net);
    }

    Document document(Breakdown total, List<Line> lines) {
        return new Document(
                "STATEMENT",
                "R1",
                "MOCK_PROFILE",
                "EXTERNAL_ACCOUNT",
                "CNY",
                now.minusSeconds(86400),
                now.minusSeconds(1),
                now,
                total,
                lines);
    }

    @Test
    void signedRefundTaxAndAfterSaleFeesRemainSeparateOriginalArithmetic() {
        var refund = amounts("0", "0", "-5", "100", "0", "0", "-105");
        var fee = amounts("0", "0", "0", "0", "3", "0", "-3");
        var d =
                document(
                        amounts("0", "0", "-5", "100", "3", "0", "-108"),
                        List.of(
                                new Line(
                                        "REFUND", "REFUND", "STORE", "ORDER", "AFTER_SALE", refund),
                                new Line(
                                        "FEE",
                                        "AFTER_SALE_FEE",
                                        "STORE",
                                        "ORDER",
                                        "AFTER_SALE",
                                        fee)));
        ChannelStatementPolicy.document(d, clock);
        var lookup =
                new Lookup(
                        "OP",
                        new Scope(
                                "E",
                                "ACCOUNT",
                                "MOCK_PROFILE",
                                "EXTERNAL_ACCOUNT",
                                "AUTH",
                                "vault:ref"),
                        "STATEMENT",
                        "R1",
                        PricingSnapshotHash.of(d));
        ChannelStatementPolicy.receipt(
                lookup,
                new Receipt(
                        "OP",
                        PricingSnapshotHash.of(lookup),
                        ChannelStatementGateway.Status.FOUND,
                        d,
                        PricingSnapshotHash.of(d),
                        "ORIGINAL_RECEIPT"),
                clock);
    }

    @Test
    void unknownCannotCarryAnUnverifiedDocumentAndOriginalHashCannotBeReplaced() {
        var zero = amounts("0", "0", "0", "0", "0", "0", "0");
        var d = document(zero, List.of(new Line("ZERO", "ADJUSTMENT", null, null, null, zero)));
        var lookup =
                new Lookup(
                        "OP",
                        new Scope(
                                "E", "A", "MOCK_PROFILE", "EXTERNAL_ACCOUNT", "AUTH", "vault:ref"),
                        "STATEMENT",
                        "R1",
                        PricingSnapshotHash.of(d));
        assertThatThrownBy(
                        () ->
                                ChannelStatementPolicy.receipt(
                                        lookup,
                                        new Receipt(
                                                "OP",
                                                PricingSnapshotHash.of(lookup),
                                                ChannelStatementGateway.Status.UNKNOWN,
                                                d,
                                                PricingSnapshotHash.of(d),
                                                null),
                                        clock))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(
                        () ->
                                ChannelStatementPolicy.receipt(
                                        lookup,
                                        new Receipt(
                                                "OP",
                                                PricingSnapshotHash.of(lookup),
                                                ChannelStatementGateway.Status.FOUND,
                                                d,
                                                "a".repeat(64),
                                                "ORIGINAL"),
                                        clock))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void wrongNetDuplicateOriginalLinesAndFeeHiddenInsideRefundAreRejected() {
        var bad = amounts("100", "10", "18", "0", "0", "0", "109");
        assertThatThrownBy(
                        () ->
                                ChannelStatementPolicy.document(
                                        document(
                                                bad,
                                                List.of(
                                                        new Line(
                                                                "SALE", "SALE", "S", "O", null,
                                                                bad))),
                                        clock))
                .isInstanceOf(RuntimeException.class);
        var zero = amounts("0", "0", "0", "0", "0", "0", "0");
        var line = new Line("DUP", "ADJUSTMENT", null, null, null, zero);
        assertThatThrownBy(
                        () ->
                                ChannelStatementPolicy.document(
                                        document(zero, List.of(line, line)), clock))
                .isInstanceOf(RuntimeException.class);
        var refund = amounts("0", "0", "0", "10", "2", "0", "-12");
        assertThatThrownBy(
                        () ->
                                ChannelStatementPolicy.document(
                                        document(
                                                refund,
                                                List.of(
                                                        new Line(
                                                                "REF", "REFUND", "S", "O", "AS",
                                                                refund))),
                                        clock))
                .isInstanceOf(RuntimeException.class);
    }
}
