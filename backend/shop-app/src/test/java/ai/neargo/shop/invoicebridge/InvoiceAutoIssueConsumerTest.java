package ai.neargo.shop.invoicebridge;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.spi.invoice.InvoicePort;
import ai.neargo.shop.spi.trade.OrderEvents;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.SubOrderMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** 付款成功 → 自动开票：每张已付子单各开各的，「开不了」跳过，其他失败交回 outbox 重试（TDD-发票模块 §2.5）。 */
class InvoiceAutoIssueConsumerTest {

    final InvoicePort invoices = mock(InvoicePort.class);
    final SubOrderMapper subs = mock(SubOrderMapper.class);
    final JsonMapper json = JsonMapper.builder().build();
    final InvoiceAutoIssueConsumer consumer = new InvoiceAutoIssueConsumer(invoices, subs, mock(OrderMapper.class), json);

    @Test
    void everyPaidSubOrderIsOfferedAndUnissuableOnesAreSkipped() {
        when(subs.selectList(any())).thenReturn(List.of(sub("SUB1", OrdSubOrder.WAIT_FULFILL),
                sub("SUB2", OrdSubOrder.CANCELLED), sub("SUB3", OrdSubOrder.COMPLETED)));
        when(invoices.issueIfAutomatic(any(), eq("SUB_ORDER"), eq("SUB1")))
                .thenThrow(BizException.of(ErrorCode.INVOICE_SOURCE_NOT_READY));
        when(invoices.issueIfAutomatic(any(), eq("SUB_ORDER"), eq("SUB3"))).thenReturn(Optional.empty());
        consumer.consume(event("SO1"));
        verify(invoices).issueIfAutomatic(new InvoicePort.Scope("MAIN", "E1", "S1", "SYSTEM"), "SUB_ORDER", "SUB3");
        verify(invoices, never()).issueIfAutomatic(any(), any(), eq("SUB2"));
    }

    @Test
    void retryableFailuresGoBackToTheOutbox() {
        when(subs.selectList(any())).thenReturn(List.of(sub("SUB1", OrdSubOrder.COMPLETED)));
        var exhausted = BizException.of(ErrorCode.INVOICE_NUMBER_EXHAUSTED);
        when(invoices.issueIfAutomatic(any(), any(), any())).thenThrow(exhausted);
        assertSame(exhausted, assertThrows(BizException.class, () -> consumer.consume(event("SO1"))));
    }

    private SysOutbox event(String orderNo) {
        var e = new SysOutbox();
        e.setEventType("ORDER_PAID");
        e.setAggregateId(orderNo);
        e.setPayload(json.writeValueAsString(new OrderEvents.OrderPaid(orderNo, "U1", 1_000, "CASH")));
        return e;
    }

    private static OrdSubOrder sub(String no, String status) {
        var s = new OrdSubOrder();
        s.setSubOrderNo(no);
        s.setStatus(status);
        s.setTenantNo("MAIN");
        s.setEntityNo("E1");
        s.setStoreNo("S1");
        return s;
    }
}
