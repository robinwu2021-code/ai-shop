package ai.neargo.shop.invoice.service;

import java.util.List;
import ai.neargo.shop.invoice.dto.InvoiceDtos;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.invoice.dto.InvoiceDtos.InvoiceQuery;
import ai.neargo.shop.invoice.dto.InvoiceDtos.InvoiceVO;
import ai.neargo.shop.spi.invoice.InvoicePort.Buyer;
import ai.neargo.shop.spi.invoice.InvoicePort.InvoiceReceipt;
import ai.neargo.shop.spi.invoice.InvoicePort.Scope;
import java.util.Optional;

/** 发票的开立、作废、折让与查询（TDD-发票模块 §2.5）。所有方法按 scope 的主体与门店收窄。 */
public interface InvoiceService {

    /** 同来源已有有效发票则原样返回，否则取号开立。 */
    InvoiceReceipt issue(Scope scope, String sourceType, String sourceNo, Buyer buyer);

    Optional<InvoiceReceipt> activeOf(Scope scope, String sourceType, String sourceNo);

    /** 付款前预存买受人；按主体开通的税制校验。 */
    void rememberBuyer(Scope scope, String sourceType, String sourceNo, Buyer buyer);

    /** 主体开通且打开自动开立时，按预存买受人开立；否则返回空。 */
    Optional<InvoiceReceipt> issueIfAutomatic(Scope scope, String sourceType, String sourceNo);

    InvoiceVO get(Scope scope, String invoiceNo);

    /** C 端「我的发票」：按平台买家查，跨主体；也是会员载具的法定查询入口。 */
    PageData<InvoiceVO> listForBuyer(String tenantNo, String buyerUserNo, int page, int size);

    InvoiceVO getForBuyer(String tenantNo, String buyerUserNo, String invoiceNo);

    PageData<InvoiceVO> list(Scope scope, InvoiceQuery query);

    InvoiceVO voidInvoice(Scope scope, String invoiceNo, String reason);

    /** 运营端：跨主体查发票（租户内）。 */
    /** @param entityNos 运营会话可见的主体；null = 不限，空集 = 一个都看不到 */
    PageData<InvoiceVO> listForOps(String tenantNo, java.util.Set<String> entityNos, InvoiceDtos.OpsInvoiceQuery query);

    /** 运营端：按发票单号取开票范围（主体 + 门店），供运营代为处置时组 Scope。 */
    Scope scopeOf(String tenantNo, String invoiceNo, String actorNo);

    /** 本店这张发票可模拟打印的打印件：应印的证明联、交易明细、未作废的折让证明单；作废的发票不出证明联与明细。 */
    List<InvoiceDtos.InvoicePrintFactVO> printFacts(Scope scope, String invoiceNo);

    InvoiceVO allowance(Scope scope, String invoiceNo, long amountMinor, String reason);

    /** 作废一张本期折让；累计折让回退，生成 ALLOWANCE_VOID 上传任务。 */
    InvoiceVO voidAllowance(Scope scope, String invoiceNo, String allowanceNo, String reason);
}
