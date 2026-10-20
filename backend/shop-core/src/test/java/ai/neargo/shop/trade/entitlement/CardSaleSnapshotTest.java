package ai.neargo.shop.trade.entitlement;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.spi.entitlement.SourceRef;
import java.util.List;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entitlement.impl.CardSaleSnapshotServiceImpl;
import ai.neargo.shop.trade.mapper.TradeMappers.CardSaleSnapshotMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

class CardSaleSnapshotTest {

    @Test void persistedCardSaleIdentityUsesPaidOrderIssuanceRulesAndRequiresPaymentProof() {
        var source = new SourceRef("CORE", "PAID_CARD_ORDER", "O1", "OL1", 1, 1, 0);
        var command = new ai.neargo.shop.spi.entitlement.EntitlementGrantPort.GrantCommand(
                "G1", source, null, "V1", "U1", "M1", 1, "a".repeat(64), "PAY1");
        assertThat(command.issueMode()).isEqualTo("PAID_ORDER");
        assertThat(command.sourceRef()).isEqualTo(source);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new ai.neargo.shop.spi.entitlement.EntitlementGrantPort.GrantCommand(
                        "G1", source, null, "V1", "U1", "M1", 1, "a".repeat(64), null))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new ai.neargo.shop.spi.entitlement.EntitlementGrantPort.GrantCommand(
                        "G1", source, null, "V1", "U1", "M1", 2, "a".repeat(64), "PAY1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void expandsPurchasedUnitsIntoStablePerEntitlementSources() {
        var facts = new CardSaleSnapshotPlan.OrderFacts("SO20261018001", "U1", "CNY");
        var entitlementA = new CardSaleSnapshotPlan.EntitlementRule("ETV1", "COUNT", "{\"total\":10}");
        var entitlementB = new CardSaleSnapshotPlan.EntitlementRule("ETV2", "COUNT", "{\"total\":2}");
        var line = new CardSaleSnapshotPlan.SaleLine("OL1", "SUB1", "E1", "ST1", "G1", "SK1", 12000L, 2,
                List.of(entitlementA, entitlementB));

        List<CardSaleSnapshotPlan.Snapshot> first = CardSaleSnapshotPlan.expand(facts, List.of(line));
        List<CardSaleSnapshotPlan.Snapshot> replay = CardSaleSnapshotPlan.expand(facts, List.of(line));

        assertThat(first).hasSize(4);
        assertThat(first).extracting(s -> s.source().grantKey()).doesNotHaveDuplicates();
        assertThat(first).extracting(s -> s.source().grantKey())
                .containsExactlyElementsOf(replay.stream().map(s -> s.source().grantKey()).toList());
        assertThat(first).extracting(CardSaleSnapshotPlan.Snapshot::snapshotHash)
                .containsExactlyElementsOf(replay.stream().map(CardSaleSnapshotPlan.Snapshot::snapshotHash).toList());
        assertThat(first).extracting(s -> s.source().unitIndex()).containsExactly(1, 1, 2, 2);
        assertThat(first).extracting(s -> s.source().entitlementIndex()).containsExactly(1, 2, 1, 2);
        assertThat(first).allSatisfy(snapshot -> {
            SourceRef source = snapshot.source();
            assertThat(source.sourceNo()).isEqualTo(facts.orderNo());
            assertThat(source.sourceLineNo()).isEqualTo("OL1");
            assertThat(snapshot.line().unitPriceMinor()).isEqualTo(12000L);
        });
    }

    @Test
    void rejectsAmbiguousOrIncompleteSaleFacts() {
        var facts = new CardSaleSnapshotPlan.OrderFacts("SO1", "U1", "CNY");
        var rule = new CardSaleSnapshotPlan.EntitlementRule("ETV1", "COUNT", "{\"total\":10}");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> CardSaleSnapshotPlan.expand(facts, List.of(
                new CardSaleSnapshotPlan.SaleLine("OL1", "SUB1", "E1", "ST1", "G1", "SK1", 1000L, 0, List.of(rule)))))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> CardSaleSnapshotPlan.expand(facts, List.of(
                new CardSaleSnapshotPlan.SaleLine("OL1", "SUB1", "E1", "ST1", "G1", "SK1", 1000L, 1, List.of()))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void persistedOrderItemIdMapsToStableInternalLineIdentity() {
        assertThat(CardSaleSnapshotPlan.orderLineIdentity(987654L)).isEqualTo("OL987654");
        assertThat(CardSaleSnapshotPlan.orderLineIdentity(987654L))
                .isEqualTo(CardSaleSnapshotPlan.orderLineIdentity(987654L));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> CardSaleSnapshotPlan.orderLineIdentity(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void snapshotHashUsesUnambiguousFieldBoundaries() {
        var firstOrder = new CardSaleSnapshotPlan.OrderFacts("SO\nU1", "U2", "CNY");
        var secondOrder = new CardSaleSnapshotPlan.OrderFacts("SO", "U1\nU2", "CNY");
        var rule = new CardSaleSnapshotPlan.EntitlementRule("ETV1", "COUNT", "{\"total\":10}");
        var first = CardSaleSnapshotPlan.expand(firstOrder, List.of(
                new CardSaleSnapshotPlan.SaleLine("OL1", "SUB1", "E1", "ST1", "G1", "SK1", 1000, 1, List.of(rule))));
        var second = CardSaleSnapshotPlan.expand(secondOrder, List.of(
                new CardSaleSnapshotPlan.SaleLine("OL1", "SUB1", "E1", "ST1", "G1", "SK1", 1000, 1, List.of(rule))));

        assertThat(first.getFirst().snapshotHash()).isNotEqualTo(second.getFirst().snapshotHash());
    }

    @Test
    void replayPersistsOneSnapshotPerStableSourceAndConflictingFactsFailClosed() {
        CardSaleSnapshotMapper snapshotMapper = mock(CardSaleSnapshotMapper.class);
        OrderMapper orderMapper = mock(OrderMapper.class);
        Map<String, ai.neargo.shop.trade.entitlement.entity.OrdCardSaleSnapshot> stored = new ConcurrentHashMap<>();
        when(snapshotMapper.insertIfAbsent(any())).thenAnswer(invocation -> {
            var row = (ai.neargo.shop.trade.entitlement.entity.OrdCardSaleSnapshot) invocation.getArgument(0);
            return stored.putIfAbsent(row.getSourceKey(), row) == null ? 1 : 0;
        });
        when(snapshotMapper.findBySource(eq("MAIN"), anyString())).thenAnswer(invocation ->
                stored.get(invocation.getArgument(1)));
        var service = new CardSaleSnapshotServiceImpl(snapshotMapper, orderMapper,
                mock(ai.neargo.shop.trade.service.EntitlementSourceDecisionService.class));
        var order = new CardSaleSnapshotPlan.OrderFacts("SO-REPLAY", "U1", "CNY");
        var rule = new CardSaleSnapshotPlan.EntitlementRule("ETV1", "COUNT", "{\"total\":10}");
        var line = new CardSaleSnapshotPlan.SaleLine("OL-1", "SUB-1", "E1", "ST1", "G-1", "SK-1", 12000, 2,
                List.of(rule, new CardSaleSnapshotPlan.EntitlementRule("ETV2", "COUNT", "{\"total\":1}")));

        var first = service.capture(order, List.of(line));
        var replay = service.capture(order, List.of(line));

        assertThat(stored).hasSize(4);
        assertThat(first).extracting(CardSaleSnapshotPlan.Snapshot::snapshotHash)
                .containsExactlyElementsOf(replay.stream().map(CardSaleSnapshotPlan.Snapshot::snapshotHash).toList());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.capture(order, List.of(new CardSaleSnapshotPlan.SaleLine("OL-1", "SUB-1",
                "E1", "ST1", "G-1", "SK-1", 13000, 2, line.entitlements()))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("different immutable facts");
        assertThat(stored).hasSize(4);
    }

    @Test
    void snapshotsKeepMerchantAndStoreOwnershipPerSubOrderLine() {
        CardSaleSnapshotMapper snapshotMapper = mock(CardSaleSnapshotMapper.class);
        OrderMapper orderMapper = mock(OrderMapper.class);
        Map<String, ai.neargo.shop.trade.entitlement.entity.OrdCardSaleSnapshot> stored = new ConcurrentHashMap<>();
        when(snapshotMapper.insertIfAbsent(any())).thenAnswer(invocation -> {
            var row = (ai.neargo.shop.trade.entitlement.entity.OrdCardSaleSnapshot) invocation.getArgument(0);
            return stored.putIfAbsent(row.getSourceKey(), row) == null ? 1 : 0;
        });
        when(snapshotMapper.findBySource(eq("MAIN"), anyString())).thenAnswer(invocation ->
                stored.get(invocation.getArgument(1)));
        var service = new CardSaleSnapshotServiceImpl(snapshotMapper, orderMapper,
                mock(ai.neargo.shop.trade.service.EntitlementSourceDecisionService.class));
        var order = new CardSaleSnapshotPlan.OrderFacts("SO-MIXED", "U1", "CNY");
        var rule = new CardSaleSnapshotPlan.EntitlementRule("ETV1", "COUNT", "{\"total\":10}");

        service.capture(order, List.of(
                new CardSaleSnapshotPlan.SaleLine("OL-1", "SUB-1", "E1", "ST1", "G-1", "SK-1", 12000, 1, List.of(rule)),
                new CardSaleSnapshotPlan.SaleLine("OL-2", "SUB-2", "E2", "ST2", "G-2", "SK-2", 15000, 1, List.of(rule))));

        assertThat(stored.values()).extracting(ai.neargo.shop.trade.entitlement.entity.OrdCardSaleSnapshot::getEntityNo)
                .containsExactlyInAnyOrder("E1", "E2");
        assertThat(stored.values()).extracting(ai.neargo.shop.trade.entitlement.entity.OrdCardSaleSnapshot::getStoreNo)
                .containsExactlyInAnyOrder("ST1", "ST2");
    }

    @Test
    void repeatedLatePaymentDeliveryUsesOrderPaidAtAndNeverAcceptsDeliveryTime() {
        CardSaleSnapshotMapper snapshotMapper = mock(CardSaleSnapshotMapper.class);
        OrderMapper orderMapper = mock(OrderMapper.class);
        OrdOrder paid = new OrdOrder();
        paid.setOrderNo("SO1");
        paid.setStatus(OrdOrder.PAID);
        paid.setPaidAt(1_797_000_000_123L);
        paid.setPayTradeNo("WX-TRADE-1");
        when(orderMapper.selectOne(any())).thenReturn(paid);
        when(snapshotMapper.markPaidOnce(anyString(), anyString(), anyString(), any(), any(), anyString()))
                .thenReturn(1, 0);
        var service = new CardSaleSnapshotServiceImpl(snapshotMapper, orderMapper,
                mock(ai.neargo.shop.trade.service.EntitlementSourceDecisionService.class));

        assertThat(service.recordPaidFromOrder("SO1")).isEqualTo(1);
        assertThat(service.recordPaidFromOrder("SO1")).isZero();

        LocalDateTime authoritativePaidAt = LocalDateTime.ofInstant(Instant.ofEpochMilli(paid.getPaidAt()), ZoneOffset.UTC);
        verify(snapshotMapper, times(2)).markPaidOnce(eq("MAIN"), eq("SO1"), eq("WX-TRADE-1"),
                eq(authoritativePaidAt), any(), eq("SYSTEM"));
    }
}
