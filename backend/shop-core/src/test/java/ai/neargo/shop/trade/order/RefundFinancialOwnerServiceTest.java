package ai.neargo.shop.trade.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import ai.neargo.shop.spi.settle.*;
import ai.neargo.shop.trade.entity.*;
import ai.neargo.shop.trade.mapper.TradeMappers.IndustryOrderSourceMapper;
import ai.neargo.shop.trade.order.entity.OrdRefundExecution;
import ai.neargo.shop.trade.order.mapper.AfterSaleMapper;
import ai.neargo.shop.trade.order.mapper.StoreOrderMapper;
import ai.neargo.shop.trade.order.refund.service.impl.RefundFinancialOwnerServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class RefundFinancialOwnerServiceTest {
    private final IndustryOrderSourceMapper sources = mock(IndustryOrderSourceMapper.class);
    private final AfterSaleMapper cases = mock(AfterSaleMapper.class);
    private final StoreOrderMapper children = mock(StoreOrderMapper.class);
    private final OrdinaryPaymentPort remotePayments = mock(OrdinaryPaymentPort.class);
    private final OrdinaryRefundPort remoteRefunds = mock(OrdinaryRefundPort.class);
    private final SettlePort localFinancialLedger = mock(SettlePort.class);
    @SuppressWarnings("unchecked") private final ObjectProvider<OrdinaryPaymentPort> payments = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked") private final ObjectProvider<OrdinaryRefundPort> refunds = mock(ObjectProvider.class);
    private final RefundFinancialOwnerServiceImpl service = new RefundFinancialOwnerServiceImpl(
            sources, cases, children, payments, refunds, localFinancialLedger);

    @Test void sourceMoneyUsesOnlyRemoteOriginalAttemptWhileLegacyOwnerRemainsUntouched() {
        var child = new OrdSubOrder(); child.setTenantNo("MAIN"); child.setEntityNo("E1"); child.setStoreNo("ST1");
        child.setUserNo("U1"); child.setOrderNo("O1"); child.setSubOrderNo("S1");
        var sale = new OrdAfterSale(); sale.setAfterSaleNo("AS1"); sale.setOrderNo("O1"); sale.setSubOrderNo("S1");
        sale.setEntityNo("E1"); sale.setUserNo("U1"); sale.setSourceSystem("FOOD");
        var binding = new OrdIndustryOrderSource(); binding.setTenantNo("MAIN"); binding.setEntityNo("E1");
        binding.setStoreNo("ST1"); binding.setBuyerNo("U1"); binding.setOrderNo("O1"); binding.setSubOrderNo("S1");
        binding.setStatus("BOUND"); binding.setPaymentOutTradeNo("O1-2"); binding.setPaymentState("SUCCESS");
        when(sources.selectOne(any())).thenReturn(binding); when(children.selectOne(any())).thenReturn(child);
        when(cases.selectOne(any())).thenReturn(sale); when(payments.getObject()).thenReturn(remotePayments);
        when(refunds.getObject()).thenReturn(remoteRefunds);
        var parent = new OrdOrder(); parent.setOrderNo("O1"); parent.setPayAmount(100L); parent.setCurrency("USD");
        parent.setPayChannel("WECHAT"); parent.setPayTradeNo("TX1");
        var attempt = new OrdinaryPaymentPort.Attempt("REMOTE-P1", "O1-2", "O1", "U1", "E1", "WECHAT", "TX1",
                100L, "USD", "SUCCESS", "DOC1");
        when(remotePayments.query(new OrdinaryPaymentPort.Reference("O1","U1","O1-2")))
                .thenReturn(new OrdinaryPaymentPort.Lookup(true, attempt));
        assertThat(service.origin(sale, child, parent).paymentNo()).isEqualTo("REMOTE-P1");
        var row = new OrdRefundExecution(); row.setExecutionNo("RF1"); row.setAfterSaleNo("AS1"); row.setOrderNo("O1");
        row.setSubOrderNo("S1"); row.setOriginPaymentNo("REMOTE-P1"); row.setAmountMinor(50L); row.setCurrency("USD"); row.setReason("return");
        var command = new OrdinaryRefundPort.Execution("O1","U1","O1-2",
                new RefundRequest("RF1","AS1","S1","REMOTE-P1",50,"USD","return"));
        var unknown = new RefundDispatchResult("RF1","REMOTE-R1",RefundDispatchResult.DispatchStatus.UNKNOWN,"UNKNOWN",true,null);
        when(remoteRefunds.request(command)).thenReturn(new OrdinaryRefundPort.Receipt(command,unknown));
        when(remoteRefunds.query(command)).thenReturn(new OrdinaryRefundPort.Receipt(command,unknown));
        assertThat(service.request(row)).isEqualTo(unknown); assertThat(service.query(row)).isEqualTo(unknown);
        verifyNoInteractions(localFinancialLedger);
        when(sources.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.request(row)).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(localFinancialLedger);
    }
}
