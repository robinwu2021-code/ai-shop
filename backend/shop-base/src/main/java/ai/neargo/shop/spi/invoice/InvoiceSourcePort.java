package ai.neargo.shop.spi.invoice;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * 来源所有者向发票域提供「已付清来源单」的冻结事实（ADR-063）。
 *
 * <p>每个 sourceType 只能有一个实现，重复注册启动失败。实现方负责：来源单属于该主体与门店、已付清、
 * 明细与总计取自付款当时冻结的数据（不按当前商品名、当前价格重建）。不满足时抛
 * {@code BizException(ErrorCode.INVOICE_SOURCE_NOT_READY)}，不返回空明细或零金额。
 */
public interface InvoiceSourcePort {

    Set<String> sourceTypes();

    InvoiceSource load(InvoicePort.Scope scope, String sourceType, String sourceNo);

    /**
     * @param industryCode 行业码（RETAIL / FOOD / BEAUTY …），只用于归类与报表，不参与金额
     * @param totalMinor 含税总计，等于各行 amountMinor 之和（可含负数的折扣行）
     */
    record InvoiceSource(
            String industryCode, String currency, Instant paidAt, long totalMinor, List<Line> lines) {
        public InvoiceSource {
            lines = List.copyOf(lines);
        }
    }

    /**
     * 一行发票明细。amountMinor 为该行含税金额；折扣行为负数。
     *
     * @param taxType TAXABLE（应税）/ ZERO（零税率）/ EXEMPT（免税），取自来源冻结的税类，不由发票域猜
     */
    record Line(
            String sourceLineRef,
            String name,
            BigDecimal quantity,
            String unit,
            long unitPriceMinor,
            long amountMinor,
            String taxType) {
        public static final String TAXABLE = "TAXABLE";
        public static final String ZERO = "ZERO";
        public static final String EXEMPT = "EXEMPT";
    }
}
