package ai.neargo.shop.trade;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.spi.trade.ShipmentTraceQueryPort;
import ai.neargo.shop.spi.user.UserQueryPort;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.order.mapper.AfterSaleMapper;
import ai.neargo.shop.trade.order.mapper.OrderItemMapper;
import ai.neargo.shop.trade.order.mapper.OrderStatusLogMapper;
import ai.neargo.shop.trade.order.mapper.PaymentMapper;
import ai.neargo.shop.trade.order.mapper.ShipmentMapper;
import ai.neargo.shop.trade.order.mapper.StatusCasMapper;
import ai.neargo.shop.trade.order.mapper.StoreOrderMapper;
import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.spi.logistics.LogisticsPort;
import ai.neargo.shop.trade.api.biz.BizOrderController;
import ai.neargo.shop.trade.service.FulfillmentChangeService;
import java.util.Set;
import ai.neargo.shop.trade.order.service.receipt.MybatisReceiptReadStore;
import ai.neargo.shop.trade.order.transition.TradeStatusWriter;
import ai.neargo.shop.trade.port.FulfillmentQueryPortImpl;
import ai.neargo.shop.trade.service.OrderService;
import ai.neargo.shop.trade.service.impl.MerchantOrderServiceImpl;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** Generic B merchant and pickup paths must not substitute for source-aware physical verification. */
class GenericIndustryFulfillmentGuardTest {
    private final StoreOrderMapper subs=mock(StoreOrderMapper.class);
    private final TradeStatusWriter writer=mock(TradeStatusWriter.class);
    private final OutboxEventBus bus=mock(OutboxEventBus.class);
    private OrdSubOrder child() {
        var sub=new OrdSubOrder(); sub.setTenantNo("MAIN"); sub.setEntityNo("E"); sub.setStoreNo("S");
        sub.setOrderNo("O"); sub.setSubOrderNo("SUB"); sub.setStatus(OrdSubOrder.FULFILLING);
        sub.setFulfillment(OrdSubOrder.STORE_PICKUP); sub.setInventoryLifecycleVersion(3);
        sub.setRequireBuyerConfirm(1); return sub;
    }
    private MerchantOrderServiceImpl merchant() {
        var service=new MerchantOrderServiceImpl(subs,mock(OrderItemMapper.class),mock(PaymentMapper.class),
                mock(OrderService.class),mock(OrderStatusLogMapper.class),mock(UserQueryPort.class),
                mock(AfterSaleMapper.class),mock(ShipmentTraceQueryPort.class));
        service.setStatusWriter(writer); return service;
    }
    @Test void bizDeliveredCannotCompleteUnpaidSourceOwnedPickupEvenWhenBuyerConfirmationRequired() {
        when(subs.selectOne(any())).thenReturn(child());
        when(subs.countIndustrySources("MAIN","E","S","O","SUB")).thenReturn(1L);
        var controller=new BizOrderController(merchant(),mock(LogisticsPort.class),mock(FulfillmentChangeService.class));
        BizContext.set(new BizContext("E",Set.of(),Set.of()).withStore("S"));
        try {
            assertThatThrownBy(() -> controller.delivered("SUB")).isInstanceOf(BizException.class);
        } finally {
            BizContext.clear();
        }
        verify(subs).countIndustrySources("MAIN","E","S","O","SUB"); verifyNoInteractions(writer,bus);
    }
    @Test void genericPickupVerificationCannotCompleteOrDegradeSourceOwnedOrder() {
        when(subs.selectOne(any())).thenReturn(child());
        when(subs.countIndustrySources("MAIN","E","S","O","SUB")).thenReturn(1L);
        var service=new FulfillmentQueryPortImpl(subs,mock(OrderItemMapper.class),mock(OrderStatusLogMapper.class),
                mock(UserQueryPort.class),bus); service.setStatusWriter(writer);
        assertThatThrownBy(() -> service.complete("SUB","actor","已核销")).isInstanceOf(BizException.class);
        verifyNoInteractions(writer,bus);
    }
    @Test void legacyPickupWithoutSourceKeepsExistingCompletionBehavior() {
        var sub=child(); sub.setInventoryLifecycleVersion(2); sub.setRequireBuyerConfirm(0);
        when(subs.selectOne(any())).thenReturn(sub);
        var service=new FulfillmentQueryPortImpl(subs,mock(OrderItemMapper.class),mock(OrderStatusLogMapper.class),
                mock(UserQueryPort.class),bus); service.setStatusWriter(writer);
        assertThat(service.complete("SUB","actor","已核销")).isTrue();
        verify(bus).publish(any());
    }
    @Test void sourceBuyerConfirmationNeedsOriginalReceiptButLegacyPaidContractIsUnchanged() {
        var payments=mock(PaymentMapper.class); var parent=new OrdOrder(); parent.setStatus(OrdOrder.PAID);
        when(payments.selectOne(any())).thenReturn(parent);
        var read=new MybatisReceiptReadStore(mock(StatusCasMapper.class),mock(ShipmentMapper.class),
                mock(OrderItemMapper.class),payments,mock(OrderStatusLogMapper.class),mock(AfterSaleMapper.class),subs);
        var sub=child();
        assertThat(read.parentPaid(sub)).isTrue(); // legacy PAID without paidAt or a source binding
        when(subs.countIndustrySources("MAIN","E","S","O","SUB")).thenReturn(1L);
        assertThat(read.parentPaid(sub)).isFalse();
        when(subs.countIndustryFulfillmentReceipts("MAIN","E","S","O","SUB")).thenReturn(1L);
        assertThat(read.parentPaid(sub)).isTrue(); // VERIFIED/BUYER_CONFIRM_PENDING allows original buyer flow
        parent.setStatus(OrdOrder.WAIT_PAY);
        assertThat(read.parentPaid(sub)).isFalse(); // physical receipt never proves payment
    }
}
