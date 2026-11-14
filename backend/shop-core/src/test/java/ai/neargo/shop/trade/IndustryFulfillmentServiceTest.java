package ai.neargo.shop.trade;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.spi.inventory.InventoryReservationPort;
import ai.neargo.shop.spi.trade.IndustryFulfillmentPort.Reference;
import ai.neargo.shop.spi.trade.IndustryFulfillmentPort.VerifyPickup;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;
import ai.neargo.shop.spi.trade.PricingSourceRef;
import ai.neargo.shop.spi.user.MerchantScopeFactsPort;
import ai.neargo.shop.trade.entity.OrdIndustryFulfillment;
import ai.neargo.shop.trade.entity.OrdIndustryOrderSource;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.IndustryFulfillmentMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.IndustryOrderSourceMapper;
import ai.neargo.shop.trade.order.entity.OrdCreateIntent;
import ai.neargo.shop.trade.order.machine.order.OrderContext;
import ai.neargo.shop.trade.order.mapper.CreateIntentMapper;
import ai.neargo.shop.trade.order.mapper.OrderItemMapper;
import ai.neargo.shop.trade.order.mapper.PaymentMapper;
import ai.neargo.shop.trade.order.mapper.StatusCasMapper;
import ai.neargo.shop.trade.order.mapper.StoreOrderMapper;
import ai.neargo.shop.trade.order.transition.TradeStatusWriter;
import ai.neargo.shop.trade.service.impl.IndustryFulfillmentServiceImpl;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import tools.jackson.databind.ObjectMapper;

/** Isolated owner-boundary tests; actual JDBC crash/race acceptance remains integration validation. */
class IndustryFulfillmentServiceTest {
    private OrdSubOrder fixtureSub;
    private final IndustryFulfillmentMapper journal=mock(IndustryFulfillmentMapper.class);
    private final IndustryOrderSourceMapper sources=mock(IndustryOrderSourceMapper.class);
    private final PaymentMapper orders=mock(PaymentMapper.class);
    private final StoreOrderMapper subs=mock(StoreOrderMapper.class);
    private final OrderItemMapper items=mock(OrderItemMapper.class);
    private final CreateIntentMapper intents=mock(CreateIntentMapper.class);
    private final MerchantScopeFactsPort owners=mock(MerchantScopeFactsPort.class);
    private final InventoryReservationPort inventory=mock(InventoryReservationPort.class);
    private final TradeStatusWriter writer=mock(TradeStatusWriter.class);
    private final StatusCasMapper locks=mock(StatusCasMapper.class);
    private final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
    private final Reference reference=new Reference(new Scope("MAIN","E","S"),
            new PricingSourceRef("FOOD","CART","C",1,null,null),"O","SUB");
    @SuppressWarnings("unchecked") private IndustryFulfillmentServiceImpl service() {
        var providers=(ObjectProvider<InventoryReservationPort>)mock(ObjectProvider.class);
        when(providers.orderedStream()).thenAnswer(i -> Stream.of(inventory));
        when(manager.getTransaction(any(TransactionDefinition.class))).thenReturn(new SimpleTransactionStatus());
        when(owners.entityActive("MAIN","E")).thenReturn(true);
        when(owners.storesOwned("MAIN","E",Set.of("S"))).thenReturn(true);
        return new IndustryFulfillmentServiceImpl(journal,sources,orders,subs,items,intents,owners,providers,
                writer,mock(OutboxEventBus.class),new ObjectMapper(),manager,locks);
    }
    @Test void wrongStoreCannotReachOriginalStockOwner() {
        var service=service(); when(owners.storesOwned("MAIN","E",Set.of("S"))).thenReturn(false);
        assertThatThrownBy(() -> service.verifyPickup(new VerifyPickup(reference,"actor",0,"123456"),"key"))
                .isInstanceOf(BizException.class);
        verify(inventory,never()).issue(any(),anyString()); verify(sources,never()).selectOne(any());
    }
    @Test void sourceVersionMismatchCannotReachInventory() {
        var service=service(); fixture(false); var source=source(); source.setSourceVersion(2L);
        when(sources.selectOne(any())).thenReturn(source);
        assertThatThrownBy(() -> service.verifyPickup(new VerifyPickup(reference,"actor",0,"123456"),"key"))
                .isInstanceOf(BizException.class);
        verify(inventory,never()).issue(any(),anyString());
    }
    @Test void wrongOriginalCodeDoesNotPrepareOrIssue() {
        var service=service(); fixture(false);
        assertThatThrownBy(() -> service.verifyPickup(new VerifyPickup(reference,"actor",0,"999999"),"key"))
                .isInstanceOf(BizException.class);
        verify(journal,never()).insert(any(OrdIndustryFulfillment.class));
        verify(inventory,never()).issue(any(),anyString());
    }
    @Test void originalAfterSaleSlotBlocksIssue() {
        var service=service(); fixture(false); when(locks.openAfterSaleNo("SUB")).thenReturn("AS");
        assertThatThrownBy(() -> service.verifyPickup(new VerifyPickup(reference,"actor",0,"123456"),"key"))
                .isInstanceOf(BizException.class);
        verify(inventory,never()).issue(any(),anyString());
    }
    @Test void legacyPickupRejectsAnotherOperationWhileBuyerConfirmationPending() {
        var service=service(); fixture(false);
        var pending=new OrdIndustryFulfillment(); pending.setStatus("BUYER_CONFIRM_PENDING");
        when(journal.selectList(any())).thenReturn(List.of(pending));
        assertThatThrownBy(() -> service.verifyPickup(new VerifyPickup(reference,"actor",0,"123456"),"new-key"))
                .isInstanceOf(BizException.class);
        verify(journal,never()).insert(any(OrdIndustryFulfillment.class));
        verify(inventory,never()).issue(any(),anyString());
    }
    @Test void originalAbsentCodeIsExplicitReadinessInsteadOfFabrication() {
        var service=service(); fixture(true);
        var receipt=service.query(reference,"actor","key");
        assertThat(receipt.status()).isEqualTo("NOT_READY_CODE"); assertThat(receipt.pending()).isTrue();
        verify(subs,never()).updateById(any(OrdSubOrder.class)); verify(inventory,never()).issue(any(),anyString());
    }
    @Test void acceptedUnpaidPickupIssuesExactOriginalReferenceWithoutClaimingPayment() {
        var service=service(); fixture(false); wireSuccessfulIssue(false);
        var receipt=service.verifyPickup(new VerifyPickup(reference,"actor",0,"123456"),"key");
        assertThat(receipt.status()).isEqualTo("VERIFIED");
        assertThat(receipt.inventoryState()).isEqualTo("ISSUED_UNPAID");
        assertThat(receipt.pending()).isFalse();
        verify(inventory).issue(new InventoryReservationPort.Scope("MAIN","E","O"),"SUB");
        verify(inventory,never()).allocatePaid(any()); verify(orders,never()).updateById(any(OrdOrder.class));
    }
    @Test void degradedPickupPreservesBuyerConfirmationInsteadOfClaimingCompleted() {
        var service=service(); fixture(false); wireSuccessfulIssue(true);
        var receipt=service.verifyPickup(new VerifyPickup(reference,"actor",0,"123456"),"key");
        assertThat(receipt.status()).isEqualTo("BUYER_CONFIRM_PENDING");
        assertThat(receipt.subOrderStatus()).isEqualTo("FULFILLING");
        assertThat(receipt.requireBuyerConfirm()).isTrue();
        verify(inventory,never()).allocatePaid(any());
    }
    private void wireSuccessfulIssue(boolean buyerConfirm) {
        fixtureSub.setRequireBuyerConfirm(buyerConfirm?1:0);
        var holder=new AtomicReference<OrdIndustryFulfillment>();
        when(journal.selectOne(any())).thenAnswer(i -> holder.get());
        when(journal.insert(any(OrdIndustryFulfillment.class))).thenAnswer(i -> {
            OrdIndustryFulfillment row=i.getArgument(0); row.setId(1L); holder.set(row); return 1;
        });
        when(journal.updateById(any(OrdIndustryFulfillment.class))).thenReturn(1);
        var scope=new InventoryReservationPort.Scope("MAIN","E","O");
        var lines=List.of(new InventoryReservationPort.ReservedLine("SKU",1,"SUB"));
        when(inventory.query(scope)).thenReturn(new InventoryReservationPort.Snapshot(scope,"RES",
                InventoryReservationPort.State.ACCEPTED_UNPAID,3,false,lines));
        when(inventory.issue(scope,"SUB")).thenReturn(new InventoryReservationPort.Snapshot(scope,"RES",
                InventoryReservationPort.State.ISSUED_UNPAID,3,false,lines));
        doAnswer(i -> {
            OrdSubOrder sub=i.getArgument(0);
            OrderContext context=i.getArgument(5);
            sub.setStatus(context.requireBuyerConfirm()?"FULFILLING":"COMPLETED"); sub.setVersion(1L); return null;
        }).when(writer).order(any(),any(),any(),anyString(),anyString(),any());
    }
    private void fixture(boolean absentCode) {
        when(sources.selectOne(any())).thenReturn(source());
        var root=new OrdOrder(); root.setOrderNo("O"); root.setUserNo("buyer"); root.setStatus("WAIT_PAY");
        when(orders.selectOne(any())).thenReturn(root);
        var sub=new OrdSubOrder(); fixtureSub=sub; sub.setSubOrderNo("SUB"); sub.setOrderNo("O"); sub.setEntityNo("E");
        sub.setStoreNo("S"); sub.setUserNo("buyer"); sub.setFulfillment("STORE_PICKUP");
        sub.setInventoryLifecycleVersion(3); sub.setStatus("WAIT_FULFILL"); sub.setVersion(0L);
        sub.setVerifyCode(absentCode?null:"123456"); when(subs.selectOne(any())).thenReturn(sub);
        var line=new OrdItem(); line.setSkuNo("SKU"); line.setQty(1); when(items.selectList(any())).thenReturn(List.of(line));
        var intent=new OrdCreateIntent(); intent.setPayNo("O"); intent.setUserNo("buyer"); intent.setStatus("COMMITTED"); intent.setStockAuthority("INDUSTRY_INVENTORY_V2");
        intent.setStockNeedsJson("[{\"skuNo\":\"SKU\",\"qty\":1,\"storeNo\":\"S\","
                + "\"fulfillmentRef\":\"SUB\",\"lifecycleVersion\":3}]");
        when(intents.selectOne(any())).thenReturn(intent);
    }
    private static OrdIndustryOrderSource source() {
        var source=new OrdIndustryOrderSource(); source.setTenantNo("MAIN"); source.setEntityNo("E");
        source.setStoreNo("S"); source.setOrderNo("O"); source.setSubOrderNo("SUB"); source.setSourceSystem("FOOD"); source.setSourceType("CART");
        source.setSourceNo("C"); source.setSourceVersion(1L); source.setSourceHash("a".repeat(64));
        source.setBuyerNo("buyer"); source.setOrderMode("POSTPAY"); source.setStatus("ACCEPTED"); return source;
    }
}
