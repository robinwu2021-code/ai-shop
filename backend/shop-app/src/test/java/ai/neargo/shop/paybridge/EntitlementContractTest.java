package ai.neargo.shop.paybridge;

import ai.neargo.shop.common.CursorPage;
import ai.neargo.shop.spi.entitlement.DecisionRef;
import ai.neargo.shop.spi.entitlement.DecisionRef.DecisionType;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementGrantPort;
import ai.neargo.shop.spi.entitlement.EntitlementGrantPort.GrantCommand;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.AssetKind;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.UseCommand;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.UseItem;
import ai.neargo.shop.spi.entitlement.SourceDecisionPort;
import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort;
import ai.neargo.shop.spi.storedvalue.StoredValuePort;
import ai.neargo.shop.spi.promotion.ActivityParticipationPort;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Internal records must preserve scope and reject ambiguous commands before transport. */
class EntitlementContractTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final SourceRef source = new SourceRef("CORE", "ORDER", "o1", "l1", 1, 1, 2);
    private final String digest = "a".repeat(64);

    @Test
    void contextAndDecisionRoundTripAndRejectInvalidIdentityOrRevision() {
        var context = new EntitlementContext("MAIN", "u1", "RequestKey", digest);
        var decision = new DecisionRef("d1", Instant.parse("2026-10-18T01:00:00Z"), 2, DecisionType.USE_COMMIT);
        roundTrip(context, EntitlementContext.class);
        roundTrip(decision, DecisionRef.class);
        assertThatThrownBy(() -> new EntitlementContext(" MAIN", "u1", "k", digest))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EntitlementContext("MAIN", "u1", "k", "forged"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DecisionRef("d1", Instant.now(), -1, DecisionType.USE_COMMIT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> json.readValue("\"UNKNOWN_FUTURE_KIND\"", DecisionType.class))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void paidOrderGrantRequiresUnitIdentityAndPaymentFact() {
        var paid = new SourceRef("CORE", "PAID_ORDER", "o1", "l1", 1, 1, 2);
        var command = new GrantCommand("g1", paid, null, "v1", "u1", "m1", 1, digest, "payment1");
        roundTrip(command, GrantCommand.class);
        assertThatThrownBy(() -> new GrantCommand("g1", paid, null, "v1", "u1", "m1", 1, digest, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GrantCommand("g1", new SourceRef("CORE", "PAID_ORDER", "o1", null,
                null, null, 2), null, "v1", "u1", "m1", 1, digest, "payment1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void useSeparatesMoneyAndUnitsAndDefensivelyCopiesItemSnapshot() {
        var items = new ArrayList<>(List.of(new UseItem("l1", "service1", "护理", 1, 3000)));
        var use = new UseCommand("op1", source, "u1", "m1", "s1", "CNY", AssetKind.STORED_VALUE,
                null, "account1", null, null, 3000L, items, "auth1", null);
        items.clear();
        assertThat(use.itemsSnapshot()).hasSize(1);
        roundTrip(use, UseCommand.class);
        assertThatThrownBy(() -> new UseCommand("op1", source, "u1", "m1", "s1", "CNY", AssetKind.STORED_VALUE,
                "instance1", "account1", null, null, 3000L, use.itemsSnapshot(), "auth1", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UseCommand("op1", source, "u1", "m1", "s1", "CNY", AssetKind.PAID_CARD,
                "instance1", null, LocalDate.of(2026, 10, 18), 0, null, use.itemsSnapshot(), "auth1", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cancellationCannotCarryACommitDecisionAndReversalIsCouponOnly() {
        var commit = new DecisionRef("d1", Instant.now(), 2, DecisionType.USE_COMMIT);
        assertThatThrownBy(() -> new EntitlementUsePort.CancelUse("op1", source, commit))
                .isInstanceOf(IllegalArgumentException.class);
        var reverse = new DecisionRef("r1", Instant.now(), 2, DecisionType.ORDER_REVERSE);
        assertThatThrownBy(() -> new EntitlementUsePort.OrderBenefitReverse("usage1", "refund1", source,
                AssetKind.STORED_VALUE, 100L, reverse))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EntitlementGrantPort.GrantDecision("g1", source, commit))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownSourceNeverPretendsToHaveACommittedDecision() {
        var unknown = new SourceDecisionPort.SourceDecision(source, SourceDecisionPort.State.UNKNOWN,
                null, null, null, null, null, null, null);
        roundTrip(unknown, SourceDecisionPort.SourceDecision.class);
        assertThatThrownBy(() -> new SourceDecisionPort.SourceDecision(source, SourceDecisionPort.State.COMMITTED,
                null, "u1", "m1", "s1", "CNY", 100L, digest))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void participationReservationCarriesSourceAndRejectsNegativeCapacity() {
        var request = new ActivityParticipationPort.ReserveActivity(source, "a1", "v1", "tier1", "u1",
                "m1", "s1", "CNY", 1);
        roundTrip(request, ActivityParticipationPort.ReserveActivity.class);
        assertThatThrownBy(() -> new ActivityParticipationPort.ReserveActivity(source, "a1", "v1", null,
                "u1", "m1", "s1", "CNY", 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void storedValueReceiveAndCreditAreDifferentCommands() {
        var receivedSource = new SourceRef("PAY", "RECHARGE", "r1", null, null, null, 3);
        var received = new DecisionRef("d1", Instant.now(), 3, DecisionType.RECHARGE_RECEIVED);
        var credit = new StoredValuePort.CreditRecharge("r1", receivedSource, received);
        roundTrip(credit, StoredValuePort.CreditRecharge.class);
        assertThatThrownBy(() -> new StoredValuePort.CreditRecharge("r1", receivedSource,
                new DecisionRef("d1", Instant.now(), 3, DecisionType.USE_COMMIT)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StoredValuePort.SubmitRecharge("r1", 0, 0,
                StoredValuePort.CollectionMethod.CASH, "receipt1", digest))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void scopedQueriesRejectUnboundedPagesAndKeepServiceDay() {
        var query = new EntitlementQueryPort.AssetQuery("u1", "m1", AssetKind.PAID_CARD, null, "cursor1", 20);
        roundTrip(query, EntitlementQueryPort.AssetQuery.class);
        assertThatThrownBy(() -> new EntitlementQueryPort.AssetQuery("u1", "m1", null, null, null, 101))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EntitlementQueryPort.AssetQuery("u1", "m1", null, null, null, 51))
                .isInstanceOf(IllegalArgumentException.class);
        var day = new EntitlementQueryPort.AllowanceQuery("u1", "m1", "card1", LocalDate.of(2026, 10, 18));
        roundTrip(day, EntitlementQueryPort.AllowanceQuery.class);
    }

    @Test
    void unknownQueryCannotSmuggleACommittedFact() {
        assertThatThrownBy(() -> new SourceDecisionPort.SourceDecision(source, SourceDecisionPort.State.UNKNOWN,
                null, "u1", "m1", "s1", "CNY", 100L, digest))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cursorPageHasMoreMatchesItsNextCursorAndCopiesRecords() {
        var rows = new ArrayList<>(List.of("record1"));
        var page = new CursorPage<>(rows, "cursor1");
        rows.clear();
        assertThat(page.records()).containsExactly("record1");
        var tree = json.readTree(json.writeValueAsString(page));
        assertThat(tree.has("hasMore")).isTrue();
        assertThat(tree.get("hasMore").asBoolean()).isTrue();
        assertThatThrownBy(() -> new CursorPage<>(List.of("row"), "cursor", false))
                .isInstanceOf(IllegalArgumentException.class);
        var last = new CursorPage<>(List.of("row"), null);
        assertThat(last.hasMore()).isFalse();
    }

    private <T> void roundTrip(T value, Class<T> type) {
        assertThat(json.readValue(json.writeValueAsString(value), type)).isEqualTo(value);
    }
}
