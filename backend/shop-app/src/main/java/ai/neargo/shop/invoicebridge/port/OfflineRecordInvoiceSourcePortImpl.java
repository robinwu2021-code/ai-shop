package ai.neargo.shop.invoicebridge.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.invoice.InvoicePort.Scope;
import ai.neargo.shop.spi.invoice.InvoiceSourcePort;
import ai.neargo.shop.trade.entity.OrdOfflineRecord;
import ai.neargo.shop.trade.mapper.OfflineRecordMappers.RecordMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * App 外收款记录 → 发票来源（来源类型 {@code OFFLINE_RECORD}，TDD-发票模块 §24）。手持 POS 输金额收现金、
 * 外部刷卡、转账记的那一笔不变成核心订单，只在 {@code ord_offline_record}；这里把它作为一行应税明细交给发票域。
 *
 * <p>只认 {@code RECORDED}（作废的不开）；金额与实收时间取当前值 —— 更正改的是同一笔收款的事实，开票前更正以更正后为准，
 * 开票后更正不回写发票（与子单开票后退款同理，应作废重开或开折让）。主体与门店已由发票域收窄后传入，这里显式按它们查。
 */
@Component
public class OfflineRecordInvoiceSourcePortImpl implements InvoiceSourcePort {

    static final String SOURCE_TYPE = "OFFLINE_RECORD";

    private final RecordMapper records;

    public OfflineRecordInvoiceSourcePortImpl(RecordMapper records) {
        this.records = records;
    }

    @Override
    public Set<String> sourceTypes() {
        return Set.of(SOURCE_TYPE);
    }

    @Override
    public InvoiceSource load(Scope scope, String sourceType, String recordNo) {
        return DataScopeContext.executeWithoutScope(() -> read(scope, recordNo));
    }

    private InvoiceSource read(Scope scope, String recordNo) {
        var r = records.selectOne(new QueryWrapper<OrdOfflineRecord>().eq("tenant_no", scope.tenantNo())
                .eq("entity_no", scope.entityNo()).eq("store_no", scope.storeNo()).eq("record_no", recordNo));
        if (r == null || !OrdOfflineRecord.RECORDED.equals(r.getStatus()) || r.getAmountMinor() == null
                || r.getAmountMinor() <= 0 || r.getReceivedAt() == null) {
            throw BizException.of(ErrorCode.INVOICE_SOURCE_NOT_READY);
        }
        long amount = r.getAmountMinor();
        String name = lineName(r.getNote());
        return new InvoiceSource("RETAIL", r.getCurrency(), r.getReceivedAt().toInstant(ZoneOffset.UTC), amount,
                List.of(new Line(recordNo, name, BigDecimal.ONE, null, amount, amount, Line.TAXABLE)), null);
    }

    /** 备注作明细名，空则「收款」；备注最长 400 字，发票明细名最长 256 字（{@code ivc_invoice_line.name}），超出截断。 */
    static String lineName(String note) {
        if (note == null || note.isBlank()) return "收款";
        String s = note.strip();
        return s.codePointCount(0, s.length()) <= 256 ? s : s.substring(0, s.offsetByCodePoints(0, 256));
    }
}
