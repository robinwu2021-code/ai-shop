package ai.neargo.shop.pay.dto;

import java.util.List;

/**
 * 采购进项票（自营）。供应商开给平台，平台据此列支成本。
 *
 * @param titleMatched 开票方名称是否与供应商主体名一致。**三流一致的机器可判部分**
 * @param settleNos    这张票覆盖的结算单
 */
public record PurchaseInvoiceVO(String invoiceNo,
                                String entityNo,
                                String period,
                                String invoiceCode,
                                String invoiceNumber,
                                String invoiceType,
                                String titleName,
                                String titleTaxNo,
                                long amountMinor,
                                long taxAmountMinor,
                                int taxRate,
                                Long invoiceDate,
                                String imageUrl,
                                String status,
                                String rejectReason,
                                boolean titleMatched,
                                List<String> settleNos, String documentNo, java.util.List<String> settleDocumentNos) {
    public PurchaseInvoiceVO(String invoiceNo, String entityNo, String period, String invoiceCode, String invoiceNumber, String invoiceType, String titleName, String titleTaxNo, long amountMinor, long taxAmountMinor, int taxRate, Long invoiceDate, String imageUrl, String status, String rejectReason, boolean titleMatched, List<String> settleNos, String documentNo) { this(invoiceNo, entityNo, period, invoiceCode, invoiceNumber, invoiceType, titleName, titleTaxNo, amountMinor, taxAmountMinor, taxRate, invoiceDate, imageUrl, status, rejectReason, titleMatched, settleNos, documentNo, java.util.List.of()); }
    public PurchaseInvoiceVO withSettleDocumentNos(java.util.List<String> settleDocumentNos) { return new PurchaseInvoiceVO(invoiceNo, entityNo, period, invoiceCode, invoiceNumber, invoiceType, titleName, titleTaxNo, amountMinor, taxAmountMinor, taxRate, invoiceDate, imageUrl, status, rejectReason, titleMatched, settleNos, documentNo, settleDocumentNos); }

        public PurchaseInvoiceVO(String invoiceNo, String entityNo, String period, String invoiceCode, String invoiceNumber, String invoiceType, String titleName, String titleTaxNo, long amountMinor, long taxAmountMinor, int taxRate, Long invoiceDate, String imageUrl, String status, String rejectReason, boolean titleMatched, List<String> settleNos) { this(invoiceNo, entityNo, period, invoiceCode, invoiceNumber, invoiceType, titleName, titleTaxNo, amountMinor, taxAmountMinor, taxRate, invoiceDate, imageUrl, status, rejectReason, titleMatched, settleNos, null); }
        public PurchaseInvoiceVO withDocumentNo(String documentNo) { return new PurchaseInvoiceVO(invoiceNo, entityNo, period, invoiceCode, invoiceNumber, invoiceType, titleName, titleTaxNo, amountMinor, taxAmountMinor, taxRate, invoiceDate, imageUrl, status, rejectReason, titleMatched, settleNos, documentNo, settleDocumentNos); }

}
