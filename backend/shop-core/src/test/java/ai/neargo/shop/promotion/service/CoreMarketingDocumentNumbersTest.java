package ai.neargo.shop.promotion.service;

import ai.neargo.shop.numbering.CoreMarketingDocumentNumbers;
import ai.neargo.shop.numbering.*;
import ai.neargo.shop.numbering.store.DocumentNumberCoordinator;
import ai.neargo.shop.promotion.entity.PmtCoupon;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CoreMarketingDocumentNumbersTest {
    @Test void batchReadReturnsOnlyPersistedNumbersAndLeavesHistoricalRowsEmpty() {
        var coordinator = mock(DocumentNumberCoordinator.class);
        var numbers = new CoreMarketingDocumentNumbers(coordinator);
        PmtCoupon first = row("same");
        PmtCoupon history = row("old");
        var source = new DocumentSource("MAIN", "PMT", "pmt_coupon", "same");
        when(coordinator.findAll(eq(DocumentType.MARKETING_COUPON_TEMPLATE), anyCollection()))
                .thenReturn(Map.of(source, "PC20261020000001"));
        var result = numbers.load(DocumentType.MARKETING_COUPON_TEMPLATE, "PMT", "pmt_coupon",
                List.of(first, history), PmtCoupon::getCouponNo, PmtCoupon::setDocumentNo);
        assertEquals("PC20261020000001", result.getFirst().getDocumentNo());
        assertNull(result.getLast().getDocumentNo());
        verify(coordinator, times(1)).findAll(eq(DocumentType.MARKETING_COUPON_TEMPLATE), anyCollection());
        verifyNoMoreInteractions(coordinator);
    }
    @Test void successfulInsertBindsAfterPersistenceAndKeepsInternalKey() {
        var coordinator = mock(DocumentNumberCoordinator.class);
        var numbers = new CoreMarketingDocumentNumbers(coordinator);
        var coupon = row("internal-key");
        var stage = new AtomicInteger();
        var source = new DocumentSource("MAIN", "MKT_LEGACY", "mkt_coupon", "internal-key");
        when(coordinator.bind(DocumentType.MARKETING_COUPON_TEMPLATE, source)).thenAnswer(call -> {
            assertEquals(1, stage.get());
            return "PC20261020000002";
        });
        int changed = numbers.bind(DocumentType.MARKETING_COUPON_TEMPLATE, "MKT_LEGACY", "mkt_coupon",
                coupon, coupon.getCouponNo(), r -> { stage.incrementAndGet(); return 1; }, PmtCoupon::setDocumentNo);
        assertEquals(1, changed);
        assertEquals("internal-key", coupon.getCouponNo());
        assertEquals("PC20261020000002", coupon.getDocumentNo());
    }
    @Test void rejectedInsertNeverCreatesMapping() {
        var coordinator = mock(DocumentNumberCoordinator.class);
        var numbers = new CoreMarketingDocumentNumbers(coordinator);
        var coupon = row("rejected");
        assertEquals(0, numbers.bind(DocumentType.MARKETING_COUPON_TEMPLATE, "PMT", "pmt_coupon",
                coupon, coupon.getCouponNo(), r -> 0, PmtCoupon::setDocumentNo));
        assertNull(coupon.getDocumentNo());
        verifyNoInteractions(coordinator);
    }
    private PmtCoupon row(String key) {
        var row = new PmtCoupon(); row.setTenantNo("MAIN"); row.setCouponNo(key); return row;
    }
}
