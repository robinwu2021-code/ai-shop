package ai.neargo.shop.trade.entitlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementGrantPort.GrantCommand;
import ai.neargo.shop.spi.entitlement.SourceDecisionPort;
import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entitlement.entity.OrdCardSaleSnapshot;
import ai.neargo.shop.trade.mapper.TradeMappers.CardSaleSnapshotMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderMapper;
import ai.neargo.shop.trade.port.CardSaleGrantSourcePortImpl;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CardSaleGrantSourceTest {
    private final CardSaleSnapshotMapper snapshots = mock(CardSaleSnapshotMapper.class);
    private final OrderMapper orders = mock(OrderMapper.class);
    private final SourceDecisionPort decisions = mock(SourceDecisionPort.class);
    private final CardSaleGrantSourcePortImpl source = new CardSaleGrantSourcePortImpl(snapshots, orders, decisions);
    private final EntitlementContext ctx = new EntitlementContext("MAIN", "OP1", "R1", "a".repeat(64));
    private final SourceRef ref = new SourceRef("CORE", "PAID_CARD_ORDER", "O1", "OL1", 1, 1, 0);
    private final Instant at = Instant.parse("2026-10-19T01:00:00Z");
    private OrdCardSaleSnapshot row;
    private OrdOrder order;
    private GrantCommand command(String user, String fact, String hash) {
        return new GrantCommand("G1", ref, null, "V1", user, "M1", 1, hash, fact);
    }
    @BeforeEach void frozenPaidSource() {
        row = new OrdCardSaleSnapshot(); row.setTenantNo("MAIN"); row.setSourceKey(ref.grantKey());
        row.setOrderNo("O1"); row.setOrderLineNo("OL1"); row.setUnitIndex(1); row.setEntitlementIndex(1);
        row.setUserNo("U1"); row.setEntityNo("M1"); row.setStoreNo("S1"); row.setCurrency("CNY");
        row.setTemplateVersionNo("V1"); row.setSnapshotHash("a".repeat(64)); row.setUnitPriceMinor(1000L);
        row.setPaidFactNo("PAY1"); row.setPaidAt(LocalDateTime.ofInstant(at, ZoneOffset.UTC));
        order = new OrdOrder(); order.setTenantNo("MAIN"); order.setOrderNo("O1"); order.setUserNo("U1");
        order.setStatus(OrdOrder.PAID); order.setCurrency("CNY"); order.setPayAmount(1000L);
        order.setPaidAt(at.toEpochMilli()); order.setPayTradeNo("PAY1"); order.setPayChannel("WECHAT");
        when(snapshots.findBySource("MAIN", ref.grantKey())).thenReturn(row);
        when(orders.selectOne(any())).thenReturn(order);
        when(decisions.query(ctx, ref)).thenReturn(new SourceDecisionPort.SourceDecision(ref,
                SourceDecisionPort.State.UNKNOWN, null, null, null, null, null, null, null));
    }
    @Test void exactFrozenCommandReturnsOriginalPaidTimeAndStableDecision() {
        var command = command("U1", "PAY1", "a".repeat(64));
        var result = source.resolve(ctx, command);
        assertThat(result.binding()).isEqualTo(command);
        assertThat(result.paidAt()).isEqualTo(at);
        var decision = source.decision(ctx, ref);
        assertThat(decision.status()).isEqualTo(SourceDecisionPort.State.COMMITTED);
        assertThat(decision.decision().occurredAt()).isEqualTo(at);
        assertThat(source.decision(ctx, ref)).isEqualTo(decision);
    }
    @Test void recipientPaymentOrBenefitSubstitutionNeverBecomesSourceProof() {
        for (var forged : List.of(command("OTHER", "PAY1", "a".repeat(64)),
                command("U1", "OTHER", "a".repeat(64)), command("U1", "PAY1", "b".repeat(64)))) {
            assertThatThrownBy(() -> source.resolve(ctx, forged)).isInstanceOf(BizException.class);
        }
    }
    @Test void unconfirmedPaymentStaysPendingAndCannotAuthorizeGrant() {
        row.setPaidAt(null); row.setPaidFactNo(null);
        assertThat(source.decision(ctx, ref).status()).isEqualTo(SourceDecisionPort.State.PENDING);
        assertThatThrownBy(() -> source.resolve(ctx, command("U1", "PAY1", "a".repeat(64))))
                .isInstanceOf(BizException.class);
    }
    @Test void freePointsStoredValueAndUnknownChannelsAreNotCashReceipts() {
        for (String channel : List.of("FREE", "POINTS", "STORED_VALUE", "UNKNOWN")) {
            order.setPayChannel(channel);
            assertThatThrownBy(() -> source.resolve(ctx, command("U1", "PAY1", "a".repeat(64))))
                    .isInstanceOf(BizException.class);
        }
    }
    @Test void receiptTimeAndOwnerMustMatchTheAuthoritativeOrder() {
        order.setPaidAt(at.plusSeconds(1).toEpochMilli());
        assertThatThrownBy(() -> source.decision(ctx, ref)).isInstanceOf(BizException.class);
        order.setPaidAt(at.toEpochMilli()); order.setUserNo("OTHER");
        assertThatThrownBy(() -> source.decision(ctx, ref)).isInstanceOf(BizException.class);
        order.setUserNo("U1"); order.setPayTradeNo("OTHER");
        assertThatThrownBy(() -> source.decision(ctx, ref)).isInstanceOf(BizException.class);
    }
}
