package ai.neargo.shop.trade.numbering;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.numbering.*;
import ai.neargo.shop.numbering.store.DocumentNumberCoordinator;
import ai.neargo.shop.trade.dto.OrderVO;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class OrderDocumentNumbersTest {
    @Test void authorizedListUsesTwoBatchLookupsAndLeavesUnmappedHistoryEmpty() {
        var coordinator = mock(DocumentNumberCoordinator.class);
        when(coordinator.findAll(eq(DocumentType.TRADE_ORDER), anyCollection())).thenReturn(Map.of(source("TRADE_ORDER", "ROOT"), "OD20261020000001"));
        when(coordinator.findAll(eq(DocumentType.TRADE_SUBORDER), anyCollection())).thenReturn(Map.of(source("TRADE_SUBORDER", "CHILD"), "OS20261020000001"));
        var page = PageData.of(List.of(order("CHILD", "ROOT", null), order("OLD", "ROOT", null)), 2, 1, 10);
        var result = (PageData<?>) new OrderDocumentNumbers(coordinator).decorate(page);
        var child = (OrderVO) result.records().getFirst(); var history = (OrderVO) result.records().getLast();
        assertEquals("CHILD", child.orderNo()); assertEquals("ROOT", child.payOrderNo());
        assertEquals("OS20261020000001", child.documentNo()); assertEquals("OD20261020000001", child.payDocumentNo());
        assertNull(history.documentNo()); assertEquals(2, result.total());
        verify(coordinator).findAll(eq(DocumentType.TRADE_ORDER), anyCollection());
        verify(coordinator).findAll(eq(DocumentType.TRADE_SUBORDER), anyCollection()); verifyNoMoreInteractions(coordinator);
    }
    @Test void nestedMainAndDtoCopiesRetainBothNumbers() {
        var coordinator = mock(DocumentNumberCoordinator.class);
        when(coordinator.findAll(eq(DocumentType.TRADE_ORDER), anyCollection())).thenReturn(Map.of(source("TRADE_ORDER", "ROOT"), "OD20261020000001"));
        when(coordinator.findAll(eq(DocumentType.TRADE_SUBORDER), anyCollection())).thenReturn(Map.of(source("TRADE_SUBORDER", "CHILD"), "OS20261020000001"));
        var root = (OrderVO) new OrderDocumentNumbers(coordinator).decorate(order("ROOT", "ROOT", List.of(order("CHILD", "ROOT", null))));
        assertEquals("OD20261020000001", root.documentNo());
        assertEquals("OS20261020000001", root.subOrders().getFirst().documentNo());
        var copy = root.subOrders().getFirst().withPrepared(5L).withPaymentResolution(null).withLogisticsSync(null);
        assertEquals("OS20261020000001", copy.documentNo()); assertEquals("OD20261020000001", copy.payDocumentNo());
    }
    private DocumentSource source(String resource, String key) { return new DocumentSource("MAIN", "CORE", resource, key); }
    private OrderVO order(String no, String main, List<OrderVO> children) {
        return new OrderVO(no, main, "PAID", "EXPRESS", "M", "Shop", List.of(), null,
                null, null, null, null, 0, null, null, null, null, null, List.of(), children, null, false, null, 1);
    }
}
