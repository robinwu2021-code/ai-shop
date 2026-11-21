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

    InvoiceVO get(Scope scope, String invoiceNo);

    PageData<InvoiceVO> list(Scope scope, InvoiceQuery query);

    InvoiceVO voidInvoice(Scope scope, String invoiceNo, String reason);

    InvoiceVO allowance(Scope scope, String invoiceNo, long amountMinor, String reason);
}
