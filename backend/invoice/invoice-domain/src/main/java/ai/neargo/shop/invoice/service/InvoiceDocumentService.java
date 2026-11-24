package ai.neargo.shop.invoice.service;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.print.api.dto.PrintExportDtos.ExportView;
import ai.neargo.shop.spi.invoice.InvoicePort.Scope;

public interface InvoiceDocumentService {
    PageData<ExportView> list(Scope scope, String invoiceNo, int page, int size);

    ExportView create(Scope scope, String invoiceNo, String key);
}
