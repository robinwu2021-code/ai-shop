package ai.neargo.shop.payclient;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.pay.dto.FinanceVOs.SettleInvoiceVO;

/**
 * 平台端 · 给商家开结算发票。
 *
 * <p>两个写动作都是<b>重要留痕</b>（{@code important=true}）：
 * 开票是对外出具凭证，驳回的原因要原样回到商家 B 端 ——
 * 事后查不到是谁开的、为什么驳的，这两件事都会变成扯不清的账。
 */
public interface OpsSettleInvoiceAppService {

    /** 返回 PageData：运营端列表页按 {records,total} 渲染 */
    PageData<SettleInvoiceVO> list(String status, String keyword, long page, long size);

    /**
     * 开票。三道校验防的都是虚开。
     *
     * @param serialNo 发票流水号，必填 —— 没有它的「已开票」等于没开
     */
    SettleInvoiceVO issue(String invoiceNo, String serialNo);

    /** 驳回。原因必填，<b>原样回商家 B 端</b> */
    SettleInvoiceVO reject(String invoiceNo, String reason);
}
