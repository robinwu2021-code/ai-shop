package ai.neargo.shop.food.dto;

import java.time.Instant;
import java.util.List;

/** 现金交班单次提交（POS 需求 B14 / B15）：POS 是交班的权威，服务端存档、校验并供日结对账。 */
public final class CashShiftContracts {
    private CashShiftContracts() {
    }

    /**
     * 一次提交整张交班单。
     *
     * @param expectedMinor POS 计算的应有现金（备用金 + 现金收款 − 找零 ± 非销售现金）；服务端只存不重算
     * @param countedMinor  交班实盘
     * @param approvalRef   差异审批引用（POS 本机主管授权号）；差额不为 0 时必填
     * @param entries       非销售现金流水（≤200）
     * @param paymentRefs   挂到本班的外部记账号（现金收款 / 退款，≤1000）；一笔记账只能挂一个班
     */
    public record SubmitReq(String drawerNo, String ownerNo, String currency, Long openingMinor,
            Instant openedAt, Instant closedAt, Long countedMinor, Long expectedMinor,
            String acceptedBy, String approvalRef, List<EntryReq> entries, List<PaymentRefReq> paymentRefs) {
    }

    /** @param kind FLOAT_IN（补备用金，IN）/ EXPENSE（支出，OUT）/ DEPOSIT（存行，OUT）/ TRANSFER（调拨，IN 或 OUT） */
    public record EntryReq(String kind, String direction, Long amountMinor, String voucherRef, String approvalRef) {
    }

    /** @param kind PAY（现金收款）/ REFUND（现金退款） */
    public record PaymentRefReq(String recordNo, String kind, Instant occurredAt) {
    }

    public record CashShiftVO(String shiftNo, String storeNo, String drawerNo, String ownerNo, String currency,
            long openingMinor, String status, Instant openedAt, Instant closedAt, long countedMinor,
            long expectedMinor, long differenceMinor, long nonSalesInMinor, long nonSalesOutMinor,
            int entryCount, int paymentRefCount, String acceptedBy, String approvalRef) {
    }
}
