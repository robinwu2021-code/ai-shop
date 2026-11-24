package ai.neargo.shop.invoice.service;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.invoice.dto.InvoiceApplicationDtos.ApplicationView;
import ai.neargo.shop.invoice.dto.InvoiceApplicationDtos.ChangeApplicationStateReq;
import ai.neargo.shop.invoice.dto.InvoiceApplicationDtos.EventView;
import ai.neargo.shop.invoice.dto.InvoiceApplicationDtos.IssueApplicationReq;
import ai.neargo.shop.invoice.dto.InvoiceApplicationDtos.ReviseApplicationReq;
import ai.neargo.shop.invoice.dto.InvoiceApplicationDtos.RevisionView;
import ai.neargo.shop.invoice.dto.InvoiceApplicationDtos.SubmitApplicationReq;
import ai.neargo.shop.spi.invoice.InvoicePort.InvoiceReceipt;
import ai.neargo.shop.spi.invoice.InvoicePort.Scope;
import java.util.List;

public interface InvoiceApplicationService {

    ApplicationView submit(Scope scope, SubmitApplicationReq request, String key);

    ApplicationView get(Scope scope, String applicationNo);

    PageData<ApplicationView> list(Scope scope, String sourceType, String sourceNo, String status, int page, int size);

    List<RevisionView> revisions(Scope scope, String applicationNo);

    List<EventView> events(Scope scope, String applicationNo);

    ApplicationView revise(Scope scope, String applicationNo, ReviseApplicationReq request, String key);

    ApplicationView reject(Scope scope, String applicationNo, ChangeApplicationStateReq request, String key);

    ApplicationView cancel(Scope scope, String applicationNo, ChangeApplicationStateReq request, String key);

    InvoiceReceipt issue(Scope scope, String applicationNo, IssueApplicationReq request, String key);
}
