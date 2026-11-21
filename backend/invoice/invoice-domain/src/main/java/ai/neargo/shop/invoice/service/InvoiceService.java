package ai.neargo.shop.invoice.service;

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

    InvoiceVO allowance(Scope scope, String invoiceNo, long amountMinor, String reason);
}
