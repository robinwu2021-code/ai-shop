package ai.neargo.shop.spi.points;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

class PointsOrderEventTest {
    @Test
    void paid_cancelled_and_finalized_preserve_original_line_scope() {
        for (var kind :
                List.of(
                        PointsFactPort.FactKind.PAID,
                        PointsFactPort.FactKind.CANCELLED,
                        PointsFactPort.FactKind.FINALIZED)) {
            var lines = List.of(new PointsFactPort.LineSnapshot("ITEM1", 1, 100, 10));
            var lifecycle =
                    new PointsFactPort.LifecycleFact(
                            "L1", "HASH", "SUB1", "USER1", "TYPE1", 2, kind, 100, 10, lines, at);
            var saved = event(new SourceFactsPort.Fact(10, lifecycle, null, null, null, null));
            assertThat(saved.fact().lifecycle()).isEqualTo(lifecycle);
            assertThat(saved.eventNo()).isEqualTo("L1");
        }
    }

    private final Instant at = Instant.parse("2026-10-18T00:00:00Z");

    private PointsEarnPort.GrantCommand grant(String store, long version) {
        return new PointsEarnPort.GrantCommand(
                "E1",
                "SUB1",
                "USER1",
                "TYPE1",
                store,
                "TEST",
                "ONLINE",
                version,
                List.of(
                        new PointsEarnPort.EarnLine(
                                "ITEM1",
                                "G1",
                                "C1",
                                1,
                                100,
                                0,
                                new PointsEarnPort.EarnRule(PointsEarnPort.RuleMode.FIXED, 0))),
                "M1",
                "MP",
                new PointsRefundPort.PaymentSnapshot(
                        "DIRECT", "TEST", "M1", store, "V1", "SM1", "T1", "OUT1", 100));
    }

    private PointsOrderEvent event(SourceFactsPort.Fact fact) {
        return new PointsOrderEvent(
                "ORDER1", "SUB1", "M1", "STORE1", "USER1", "TYPE1", "CNY", 2, at, fact);
    }

    @Test
    void earning_preserves_original_zero_rule_payment_and_replay_identity() {
        var fact = new SourceFactsPort.Fact(10, null, grant("STORE1", 2), null, null, null);
        var first = event(fact);
        assertThat(event(fact)).isEqualTo(first);
        assertThat(first.eventNo()).isEqualTo("E1");
        assertThat(first.fact()).isSameAs(fact);
        assertThat(first.fact().earning().lines().getFirst().rule().value()).isZero();
    }

    @Test
    void rejects_other_store_and_other_source_version() {
        assertThatThrownBy(
                        () ->
                                event(
                                        new SourceFactsPort.Fact(
                                                10, null, grant("STORE2", 2), null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                event(
                                        new SourceFactsPort.Fact(
                                                10, null, grant("STORE1", 3), null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_empty_or_ambiguous_payload() {
        var lifecycle =
                new PointsFactPort.LifecycleFact(
                        "L1",
                        "HASH",
                        "SUB1",
                        "USER1",
                        "TYPE1",
                        2,
                        PointsFactPort.FactKind.PAID,
                        100,
                        0,
                        List.of(),
                        at);
        assertThatThrownBy(() -> event(new SourceFactsPort.Fact(10, null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                event(
                                        new SourceFactsPort.Fact(
                                                10,
                                                lifecycle,
                                                grant("STORE1", 2),
                                                null,
                                                null,
                                                null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lifecycle_is_not_a_shortcut_for_refund_or_responsibility_closure() {
        for (var kind :
                List.of(
                        PointsFactPort.FactKind.REFUND_SUCCEEDED,
                        PointsFactPort.FactKind.REFUND_CLOSED)) {
            var lifecycle =
                    new PointsFactPort.LifecycleFact(
                            "L1", "HASH", "SUB1", "USER1", "TYPE1", 2, kind, 100, 0, List.of(), at);
            assertThatThrownBy(
                            () ->
                                    event(
                                            new SourceFactsPort.Fact(
                                                    10, lifecycle, null, null, null, null)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void requires_store_currency_time_and_nonnegative_sequence() {
        var fact = new SourceFactsPort.Fact(10, null, grant("STORE1", 2), null, null, null);
        assertThatThrownBy(
                        () ->
                                new PointsOrderEvent(
                                        "ORDER1", "SUB1", "M1", "", "USER1", "TYPE1", "CNY", 2, at,
                                        fact))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new PointsOrderEvent(
                                        "ORDER1", "SUB1", "M1", "STORE1", "USER1", "TYPE1", "", 2,
                                        at, fact))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new PointsOrderEvent(
                                        "ORDER1", "SUB1", "M1", "STORE1", "USER1", "TYPE1", "CNY",
                                        2, null, fact))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                event(
                                        new SourceFactsPort.Fact(
                                                -1, null, grant("STORE1", 2), null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void closure_requires_both_final_funds_and_no_open_after_sale() {
        var valid =
                new PointsFactPort.RefundClosureFact(
                        "C1", "TYPE1", "SUB1", "USER1", 2, at.minusSeconds(1), true, true, at);
        assertThat(event(new SourceFactsPort.Fact(10, null, null, valid, null, null)).eventNo())
                .isEqualTo("C1");
        var invalid =
                new PointsFactPort.RefundClosureFact(
                        "C1", "TYPE1", "SUB1", "USER1", 2, at, false, true, at);
        assertThatThrownBy(
                        () -> event(new SourceFactsPort.Fact(10, null, null, invalid, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zero_cash_proof_cannot_cross_currency_or_store() {
        var valid =
                new PointsRefundPort.ZeroCashCompletionFact(
                        "Z1",
                        "PREP1",
                        "HASH",
                        1,
                        "TYPE1",
                        "AS1",
                        "SUB1",
                        "USER1",
                        "STORE1",
                        "CNY",
                        2,
                        0,
                        0,
                        0,
                        PointsRefundPort.ZeroCashReason.NO_CASH_DUE,
                        at);
        assertThat(event(new SourceFactsPort.Fact(10, null, null, null, valid, null)).eventNo())
                .isEqualTo("Z1");
        var invalid =
                new PointsRefundPort.ZeroCashCompletionFact(
                        "Z1",
                        "PREP1",
                        "HASH",
                        1,
                        "TYPE1",
                        "AS1",
                        "SUB1",
                        "USER1",
                        "STORE2",
                        "USD",
                        2,
                        0,
                        0,
                        0,
                        PointsRefundPort.ZeroCashReason.NO_CASH_DUE,
                        at);
        assertThatThrownBy(
                        () -> event(new SourceFactsPort.Fact(10, null, null, null, invalid, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refund_confirmation_preserves_original_preparation_reference_and_time() {
        var confirmation =
                new PointsRefundPort.RefundConfirmation("PREP1", "HASH", "PAY1", null, null);
        var valid =
                new PointsFactPort.RefundConfirmationFact(
                        "R1", "TYPE1", "SUB1", "USER1", 2, at, confirmation);
        assertThat(
                        event(new SourceFactsPort.Fact(10, null, null, null, null, valid))
                                .fact()
                                .refund()
                                .confirmation())
                .isSameAs(confirmation);
        var invalid =
                new PointsFactPort.RefundConfirmationFact(
                        "R1", "TYPE1", "SUB1", "USER1", 2, at.plusSeconds(1), confirmation);
        assertThatThrownBy(
                        () -> event(new SourceFactsPort.Fact(10, null, null, null, null, invalid)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
