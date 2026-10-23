package ai.neargo.shop.trade.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import ai.neargo.shop.spi.platform.PlatformSwitchPort;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.trade.entity.*;
import ai.neargo.shop.trade.order.command.OrderActor;
import ai.neargo.shop.trade.order.mapper.*;
import ai.neargo.shop.trade.order.refund.port.AfterSaleLineStore;
import ai.neargo.shop.trade.order.refund.service.*;
import ai.neargo.shop.trade.order.refund.service.impl.RefundPreparationServiceImpl;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

class RefundPreparationOutsideTransactionTest {
    @Test void actualOwnerReceiptFetchedBeforeLocksAndOnlyLocalFingerprintRevalidatedWhileLocked() {
        var cases=mock(AfterSaleMapper.class); var locks=mock(StatusCasMapper.class); var payments=mock(PaymentMapper.class);
        var children=mock(StoreOrderMapper.class); var intents=mock(CreateIntentMapper.class);
        var query=mock(RefundableQuery.class); var executions=mock(RefundExecutionStore.class);
        var lines=mock(AfterSaleLineStore.class); var owner=mock(RefundFinancialOwnerService.class);
        var evidence=mock(OriginalRefundEvidenceService.class); var legacy=mock(SettlePort.class);
        var manager=new AbstractPlatformTransactionManager() {
            @Override protected Object doGetTransaction() { return new Object(); }
            @Override protected void doBegin(Object transaction,TransactionDefinition definition) { }
            @Override protected void doCommit(DefaultTransactionStatus status) { }
            @Override protected void doRollback(DefaultTransactionStatus status) { }
        };
        var service=new RefundPreparationServiceImpl(cases,locks,payments,intents,query,executions,lines,owner,evidence,
                legacy,new ObjectMapper(),children,manager,mock(RefundObligationService.class),mock(PlatformSwitchPort.class),false);
        var as=new OrdAfterSale(); as.setAfterSaleNo("AS1"); as.setSubOrderNo("S1"); as.setOrderNo("O1");
        as.setStatus("REFUNDING"); as.setType("REFUND_ONLY"); as.setRefundMinor(100L);
        var child=new OrdSubOrder(); child.setSubOrderNo("S1"); child.setOrderNo("O1"); child.setUserNo("U1");
        child.setEntityNo("E1"); child.setStoreNo("ST1"); child.setPayAmount(100L); child.setFreightAmount(0L); child.setStatus("WAIT_FULFILL");
        var parent=new OrdOrder(); parent.setOrderNo("O1"); parent.setUserNo("U1"); parent.setStatus("PAID");
        parent.setPayAmount(100L); parent.setCurrency("USD"); parent.setPayChannel("WECHAT"); parent.setPayTradeNo("TX1");
        var item=new OrdItem(); item.setId(1L); item.setQty(1); item.setSkuNo("SKU1"); item.setPaidShareMinor(100L);
        when(cases.selectOne(any())).thenReturn(as); when(children.selectOne(any())).thenReturn(child);
        when(payments.selectOne(any())).thenReturn(parent); when(locks.openAfterSaleNo("S1")).thenReturn("AS1");
        when(lines.lines("AS1")).thenReturn(List.of()); when(query.lockItems(child)).thenReturn(List.of(item));
        when(evidence.freeze(eq(child),anyList(),any())).thenAnswer(call->call.getArgument(2));
        when(owner.originFingerprint(as,child,parent)).thenReturn("original-source-economics");
        var locked=new AtomicBoolean();
        when(locks.lockOrder("S1")).thenAnswer(call->{ assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue(); locked.set(true); return child; });
        when(owner.origin(as,child,parent)).thenAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); assertThat(locked.get()).isFalse();
            return new SettlePort.PaymentReceipt("REMOTE-P1","O1","U1","WECHAT","TX1",100,"USD","SUCCESS");
        });
        assertThat(service.prepare("AS1",new OrderActor("E1","ST1","STAFF1"))).isEqualTo("RF-AS1");
        verify(owner,times(1)).origin(as,child,parent);
        verify(owner,times(2)).originFingerprint(as,child,parent);
        verify(executions).insert(argThat(row->"REMOTE-P1".equals(row.getOriginPaymentNo())));
        verifyNoInteractions(legacy);
    }
}
