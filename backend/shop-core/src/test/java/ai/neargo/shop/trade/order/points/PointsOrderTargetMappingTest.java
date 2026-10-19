package ai.neargo.shop.trade.order.points;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.spi.user.MerchantQueryPort.PaymentRoute;
import ai.neargo.shop.trade.order.points.OrderPointsTargetFactory.PricedLine;

import org.junit.jupiter.api.Test;

import java.util.*;

class PointsOrderTargetMappingTest {
    @Test
    void pre_payment_target_keeps_an_unassigned_merchant_payment_number() {
        route("ST1");
        assertThat(
                        factory.build(
                                        "S",
                                        "M",
                                        "ST1",
                                        "TEST",
                                        null,
                                        0,
                                        0,
                                        List.of(new PricedLine("I", "M", "ST1", "ST1", 100)))
                                .payment()
                                .outTradeNo())
                .isNull();
    }

    @Test
    void aggregated_route_is_a_valid_native_funds_path() {
        route("ST1");
        when(merchants.paymentRouteOf("M", "ST1", "TEST"))
                .thenReturn(
                        Optional.of(
                                new PaymentRoute(
                                        "M", "ST1", "PM", "TEST", "SM", "V1", "AGGREGATED")));
        assertThat(
                        factory.build(
                                        "S",
                                        "M",
                                        "ST1",
                                        "TEST",
                                        "O",
                                        0,
                                        0,
                                        List.of(new PricedLine("I", "M", "ST1", "ST1", 100)))
                                .target()
                                .path())
                .isEqualTo("AGGREGATED");
    }

    MerchantQueryPort merchants = mock(MerchantQueryPort.class);
    OrderPointsTargetFactory factory = new OrderPointsTargetFactory(merchants);

    void route(String store) {
        when(merchants.entityOfStores(List.of(store))).thenReturn(Map.of(store, "M"));
        when(merchants.paymentRouteOf("M", store, "TEST"))
                .thenReturn(
                        Optional.of(
                                new PaymentRoute("M", store, "PM", "TEST", "SM", "V1", "DIRECT")));
    }

    @Test
    void same_entity_stays_separate_and_coupon_excludes_freight() {
        route("ST1");
        route("ST2");
        var first =
                factory.build(
                        "S1",
                        "M",
                        "ST1",
                        "TEST",
                        "O1",
                        100,
                        50,
                        List.of(
                                new PricedLine("I1", "M", "ST1", "ST1", 333),
                                new PricedLine("I2", "M", "ST1", "ST1", 667)));
        var second =
                factory.build(
                        "S2",
                        "M",
                        "ST2",
                        "TEST",
                        "O1",
                        0,
                        0,
                        List.of(new PricedLine("I3", "M", "ST2", "ST2", 100)));
        assertThat(first.target().storeNo()).isEqualTo("ST1");
        assertThat(second.target().storeNo()).isEqualTo("ST2");
        assertThat(first.target().goodsMinor()).isEqualTo(900);
        assertThat(first.target().items())
                .extracting(i -> i.goodsMinor())
                .containsExactly(300L, 600L);
        assertThat(first.payment().orderTotalMinor()).isEqualTo(950);
        assertThat(first.payment().outTradeNo()).isEqualTo("O1");
        assertThat(first.target().tradeNo()).isNull();
        assertThat(first.payment().tradeNo()).isNull();
    }

    @Test
    void participating_target_requires_explicit_consistent_ownership() {
        route("ST1");
        assertThatThrownBy(
                        () ->
                                factory.build(
                                        "S",
                                        "M",
                                        null,
                                        "TEST",
                                        "O",
                                        0,
                                        0,
                                        List.of(new PricedLine("I", "M", null, null, 100))))
                .hasMessageContaining("STORE");
        assertThatThrownBy(
                        () ->
                                factory.build(
                                        "S",
                                        "M",
                                        "ST1",
                                        "TEST",
                                        "O",
                                        0,
                                        0,
                                        List.of(new PricedLine("I", "M", "ST2", "ST1", 100))))
                .hasMessageContaining("OWNERSHIP");
        assertThatThrownBy(
                        () ->
                                factory.build(
                                        "S",
                                        "M",
                                        "ST1",
                                        "TEST",
                                        "O",
                                        0,
                                        0,
                                        List.of(new PricedLine("I", "M", "ST1", "ST2", 100))))
                .hasMessageContaining("OWNERSHIP");
        when(merchants.paymentRouteOf("M", "ST1", "TEST"))
                .thenReturn(
                        Optional.of(
                                new PaymentRoute("M", "ST2", "PM", "TEST", "SM", "V1", "DIRECT")));
        assertThatThrownBy(
                        () ->
                                factory.build(
                                        "S",
                                        "M",
                                        "ST1",
                                        "TEST",
                                        "O",
                                        0,
                                        0,
                                        List.of(new PricedLine("I", "M", "ST1", "ST1", 100))))
                .hasMessageContaining("ROUTE");
    }

    @Test
    void rejects_invalid_amounts_and_duplicate_line_identifiers() {
        route("ST1");
        var line = new PricedLine("I", "M", "ST1", "ST1", 100);
        assertThatThrownBy(() -> factory.build("S", "M", "ST1", "TEST", "O", 101, 0, List.of(line)))
                .hasMessageContaining("AMOUNT");
        assertThatThrownBy(
                        () ->
                                factory.build(
                                        "S", "M", "ST1", "TEST", "O", 0, 0, List.of(line, line)))
                .hasMessageContaining("LINE");
    }
}
