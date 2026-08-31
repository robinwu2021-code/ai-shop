package ai.neargo.shop.pay.service;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.pay.dto.FinanceVOs.SettleInvoiceVO;

/**
 * 商家结算发票（矩阵 P-12.2.4 结算凭证/发票）。
 *
 * <p><b>不要和另外两张票混</b>：{@code StlPurchaseInvoice} 是供应商开给平台的进项票，
 * {@code ord_invoice_request} 是平台开给消费者的销项票。这里是平台开给商家的结算凭证。
 */
public interface SettleInvoiceService {

    /** @param keyword 匹配单号 / 商家名 / 抬头 */
    PageData<SettleInvoiceVO> list(String status, String keyword, long page, long size);

    /**
     * 开票。三道校验，每一道防的都是<b>虚开</b>：
     * 只有 {@code PENDING} 能开（重复开票 = 重复虚开）·
     * 企业抬头必须有税号 · 金额不得超过该周期已结算金额。
     *
     * @param serialNo 发票流水号，必填 —— 没有流水号的「已开票」等于没开
     */
    SettleInvoiceVO issue(String invoiceNo, String serialNo, String operatorNo);

    /** 驳回。{@code reason} 必填 —— 商家要知道是抬头错了还是金额不符。 */
    SettleInvoiceVO reject(String invoiceNo, String reason, String operatorNo);
}
