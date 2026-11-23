package ai.neargo.shop.invoicebridge.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.invoice.InvoicePort.Scope;
import ai.neargo.shop.spi.invoice.InvoiceSourcePort.Line;
import ai.neargo.shop.trade.entity.OrdOfflineRecord;
import ai.neargo.shop.trade.mapper.OfflineRecordMappers.RecordMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** App 外收款记录 → 发票来源 {@code OFFLINE_RECORD}（TDD-发票模块 §24，AC-P1..P3）。 */
class OfflineRecordInvoiceSourcePortImplTest {

    static final Scope SCOPE = new Scope("MAIN", "E1", "S1", "U1");

    RecordMapper records = mock(RecordMapper.class);
    OfflineRecordInvoiceSourcePortImpl port = new OfflineRecordInvoiceSourcePortImpl(records);
    OrdOfflineRecord r = new OrdOfflineRecord();

    @BeforeEach
    void recordedCash() {
        r.setRecordNo("OFR1");
        r.setEntityNo("E1");
        r.setStoreNo("S1");
        r.setAmountMinor(35_000L);
        r.setCurrency("TWD");
        r.setNote("早餐 3 份");
        r.setReceivedAt(LocalDateTime.of(2026, 11, 23, 1, 2, 3));
        r.setStatus(OrdOfflineRecord.RECORDED);
        when(records.selectOne(any())).thenReturn(r);
    }

    @Test
    void acP1RecordedCashBecomesOneTaxableLineForTheReceivedAmount() {
        assertEquals(Set.of("OFFLINE_RECORD"), port.sourceTypes());
        var source = port.load(SCOPE, "OFFLINE_RECORD", "OFR1");
        assertEquals("TWD", source.currency());
        assertEquals(35_000L, source.totalMinor());
        assertEquals(Instant.parse("2026-11-23T01:02:03Z"), source.paidAt());
        assertNull(source.buyerUserNo());
        assertEquals(List.of(new Line("OFR1", "早餐 3 份", BigDecimal.ONE, null, 35_000L, 35_000L, Line.TAXABLE)),
                source.lines());

        r.setNote("  ");
        assertEquals("收款", port.load(SCOPE, "OFFLINE_RECORD", "OFR1").lines().getFirst().name());
    }

    @Test
    void acP1QueryIsNarrowedToTheScopeTenantEntityAndStore() {
        port.load(SCOPE, "OFFLINE_RECORD", "OFR1");
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<QueryWrapper<OrdOfflineRecord>> q = (ArgumentCaptor) ArgumentCaptor.forClass(QueryWrapper.class);
        verify(records).selectOne(q.capture());
        String sql = q.getValue().getSqlSegment();
        for (var col : List.of("tenant_no", "entity_no", "store_no", "record_no")) assertTrue(sql.contains(col), sql);
        assertTrue(q.getValue().getParamNameValuePairs().values().containsAll(List.of("MAIN", "E1", "S1", "OFR1")));
    }

    @Test
    void acP2VoidedMissingOrOtherStoreRecordIsNotReady() {
        r.setStatus(OrdOfflineRecord.VOIDED);
        notReady(SCOPE);
        r.setStatus(OrdOfflineRecord.RECORDED);
        r.setAmountMinor(0L);
        notReady(SCOPE);
        when(records.selectOne(any())).thenReturn(null); // 别店的单：按门店收窄后查不到
        notReady(new Scope("MAIN", "E1", "S2", "U1"));
    }

    private void notReady(Scope scope) {
        var e = assertThrows(BizException.class, () -> port.load(scope, "OFFLINE_RECORD", "OFR1"));
        assertEquals(ErrorCode.INVOICE_SOURCE_NOT_READY, e.errorCode());
    }
}
