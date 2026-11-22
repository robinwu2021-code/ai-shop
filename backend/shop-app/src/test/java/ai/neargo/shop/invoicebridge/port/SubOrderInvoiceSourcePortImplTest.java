package ai.neargo.shop.invoicebridge.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.invoice.InvoicePort.Scope;
import ai.neargo.shop.spi.invoice.InvoiceSourcePort.Line;
import ai.neargo.shop.trade.entity.OrdIndustryOrderSource;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdPriceQuoteLine;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers.IndustryOrderSourceMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderItemMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.PriceQuoteLineMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.SubOrderMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** 已付子单 → 发票来源的金额组成与课税别（PRD AC5、AC13，TDD-发票模块 §2.4）。 */
class SubOrderInvoiceSourcePortImplTest {

    static final Scope SCOPE = new Scope("T1", "E1", "S1", "U1");

    SubOrderMapper subs = mock(SubOrderMapper.class);
    OrderMapper orders = mock(OrderMapper.class);
    OrderItemMapper items = mock(OrderItemMapper.class);
    IndustryOrderSourceMapper bindings = mock(IndustryOrderSourceMapper.class);
    PriceQuoteLineMapper quoteLines = mock(PriceQuoteLineMapper.class);
    SubOrderInvoiceSourcePortImpl port =
            new SubOrderInvoiceSourcePortImpl(subs, orders, items, bindings, quoteLines, JsonMapper.builder().build());
    OrdSubOrder sub = new OrdSubOrder();

    @BeforeEach
    void paidRetailOrder() {
        sub.setSubOrderNo("SUB1");
        sub.setOrderNo("SO1");
        sub.setStatus(OrdSubOrder.COMPLETED);
        sub.setFreightAmount(6_000L);
        sub.setDiscountAmount(1_000L);
        sub.setPointsDeductMinor(2_000L);
        sub.setWeighAdjustMinor(-500L);
        sub.setPayAmount(30_000L - 1_000 - 2_000 - 500 + 6_000);
        when(subs.selectOne(any())).thenReturn(sub);
        var order = new OrdOrder();
        order.setCurrency("TWD");
        order.setPaidAt(1_763_700_000_000L);
        when(orders.selectOne(any())).thenReturn(order);
        when(items.selectList(any())).thenReturn(List.of(item(11L, "牛肉麵", 20_000L), item(12L, "滷味", 10_000L)));
    }

    @Test
    void retailOrderBecomesItemsFreightDiscountPointsAndWeighLinesThatSumToThePayment() {
        var source = port.load(SCOPE, "SUB_ORDER", "SUB1");
        assertEquals("RETAIL", source.industryCode());
        assertEquals(List.of("牛肉麵", "滷味", "運費", "折扣", "點數折抵", "秤重調整"),
                source.lines().stream().map(Line::name).toList());
        assertEquals(source.totalMinor(), source.lines().stream().mapToLong(Line::amountMinor).sum());
        assertEquals(List.of(Line.TAXABLE), source.lines().stream().map(Line::taxType).distinct().toList());
    }

    @Test
    void foodOrderTakesIndustryAndTaxClassFromTheFrozenQuote() {
        sub.setDiscountAmount(0L);
        sub.setPointsDeductMinor(0L);
        sub.setWeighAdjustMinor(0L);
        sub.setPayAmount(36_000L);
        when(bindings.selectOne(any())).thenReturn(binding());
        when(quoteLines.selectList(any())).thenReturn(List.of(quote("L1", "STANDARD"), quote("L2", "EXEMPT")));
        var source = port.load(SCOPE, "SUB_ORDER", "SUB1");
        assertEquals("FOOD", source.industryCode());
        assertEquals(List.of(Line.TAXABLE, Line.EXEMPT, Line.TAXABLE),
                source.lines().stream().map(Line::taxType).toList(), "items + freight");
    }

    @Test
    void mixedTaxWithWholeOrderDiscountOrUnknownTaxClassIsNotInvoiced() {
        when(bindings.selectOne(any())).thenReturn(binding());
        when(quoteLines.selectList(any())).thenReturn(List.of(quote("L1", "STANDARD"), quote("L2", "ZERO_RATED")));
        notReady();
        sub.setDiscountAmount(0L);
        sub.setPointsDeductMinor(0L);
        sub.setWeighAdjustMinor(0L);
        when(quoteLines.selectList(any())).thenReturn(List.of(quote("L1", "STANDARD"), quote("L2", "OUT_OF_SCOPE")));
        notReady();
    }

    @Test
    void unpaidOrPartlyRefundedOrdersAreNotInvoiced() {
        sub.setRefundedMinor(1_000L);
        notReady();
        sub.setRefundedMinor(0L);
        sub.setStatus(OrdSubOrder.WAIT_PAY);
        notReady();
    }

    private void notReady() {
        var e = assertThrows(BizException.class, () -> port.load(SCOPE, "SUB_ORDER", "SUB1"));
        assertEquals(ErrorCode.INVOICE_SOURCE_NOT_READY, e.errorCode());
    }

    private static OrdItem item(long id, String title, long amount) {
        var i = new OrdItem();
        i.setId(id);
        i.setTitle(title);
        i.setQty(1);
        i.setPrice(amount);
        i.setAmount(amount);
        return i;
    }

    private static OrdIndustryOrderSource binding() {
        var b = new OrdIndustryOrderSource();
        b.setSourceSystem("FOOD");
        b.setQuoteNo("Q1");
        b.setAllocationsJson("{\"allocations\":[{\"sourceLineNo\":\"L1\",\"orderItemId\":11},"
                + "{\"sourceLineNo\":\"L2\",\"orderItemId\":12}],\"evidence\":[]}");
        return b;
    }

    private static OrdPriceQuoteLine quote(String sourceLineNo, String taxClass) {
        var q = new OrdPriceQuoteLine();
        q.setSourceLineNo(sourceLineNo);
        q.setTaxClass(taxClass);
        return q;
    }
}
