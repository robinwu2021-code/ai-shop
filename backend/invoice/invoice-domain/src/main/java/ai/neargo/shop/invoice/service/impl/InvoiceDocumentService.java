package ai.neargo.shop.invoice.service.impl;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.invoice.entity.IvcInvoice;
import ai.neargo.shop.invoice.mapper.InvoiceMappers.InvoiceMapper;
import ai.neargo.shop.invoice.regime.tw.TwBuyerRules;
import ai.neargo.shop.print.api.ExportCommand;
import ai.neargo.shop.print.api.ExportSource;
import ai.neargo.shop.print.api.ExportSourceType;
import ai.neargo.shop.print.api.PdfOutputProfile;
import ai.neargo.shop.print.api.PrintActor;
import ai.neargo.shop.print.api.PrintExportFacade;
import ai.neargo.shop.print.api.PrintFailure;
import ai.neargo.shop.print.api.PrintScope;
import ai.neargo.shop.print.api.dto.PrintExportDtos.ExportQuery;
import ai.neargo.shop.print.api.dto.PrintExportDtos.ExportView;
import ai.neargo.shop.spi.invoice.InvoicePort.Scope;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class InvoiceDocumentService {
    private static final long FROZEN_INVOICE_REVISION = 1;

    private final InvoiceMapper invoices;
    private final PrintExportFacade exports;

    public InvoiceDocumentService(InvoiceMapper invoices, PrintExportFacade exports) {
        this.invoices = invoices;
        this.exports = exports;
    }

    public PageData<ExportView> list(Scope scope, String invoiceNo, int page, int size) {
        var source = sourceType(scopedInvoice(invoices, scope, invoiceNo, false));
        try {
            return exports.page(new ExportQuery(scope.storeNo(), source.name(), invoiceNo, null, page, size),
                    actor(scope, null));
        } catch (PrintFailure failure) {
            throw exportFailure(failure);
        }
    }

    public ExportView create(Scope scope, String invoiceNo, String key) {
        var invoice = scopedInvoice(invoices, scope, invoiceNo, false);
        if (!"ISSUED".equals(invoice.getStatus())) throw BizException.of(ErrorCode.INVOICE_STATE_INVALID);
        var source = new ExportSource(sourceType(invoice), invoiceNo, FROZEN_INVOICE_REVISION);
        var command = new ExportCommand(new PrintScope(scope.tenantNo(), scope.entityNo(), scope.storeNo()),
                source, PdfOutputProfile.ORIGINAL_ROLL);
        var actor = actor(scope, key);
        try {
            var operation = exports.create(command, actor);
            return exports.get(operation.targetNo(), actor);
        } catch (PrintFailure failure) {
            throw exportFailure(failure);
        }
    }

    static IvcInvoice scopedInvoice(InvoiceMapper invoices, Scope scope, String invoiceNo, boolean lock) {
        var invoice = invoices.selectOne(new QueryWrapper<IvcInvoice>().eq("tenant_no", scope.tenantNo())
                .eq("entity_no", scope.entityNo()).eq("store_no", scope.storeNo()).eq("invoice_no", invoiceNo)
                .last(lock, "FOR UPDATE"));
        if (invoice == null) throw BizException.of(ErrorCode.INVOICE_NOT_FOUND);
        return invoice;
    }

    static ExportSourceType sourceType(IvcInvoice invoice) {
        if ("Y".equals(invoice.getPrintMark())) return ExportSourceType.INVOICE_PROOF;
        if ("N".equals(invoice.getPrintMark()) && TwBuyerRules.MOBILE.equals(invoice.getCarrierType())
                && !StringUtils.hasText(invoice.getDonateCode())) return ExportSourceType.INVOICE_DETAIL;
        throw BizException.of(ErrorCode.INVOICE_STATE_INVALID);
    }

    private static PrintActor actor(Scope scope, String key) {
        return new PrintActor(scope.tenantNo(), scope.entityNo(), scope.storeNo(), "MERCHANT", scope.actorNo(), key,
                Set.of("SOURCE_INVOICE_READ", BizPerms.PRINT_EXPORT));
    }

    static BizException exportFailure(PrintFailure failure) {
        String key = failure.key().toLowerCase(Locale.ROOT);
        if (key.contains("forbidden")) return BizException.of(ErrorCode.FORBIDDEN);
        if (key.contains("notfound")) return BizException.of(ErrorCode.NOT_FOUND);
        if (key.contains("conflict")) return BizException.of(ErrorCode.PRINT_CONFLICT);
        if (key.contains("unavailable") || key.contains("notconfigured")) {
            return BizException.of(ErrorCode.PRINT_UNAVAILABLE);
        }
        return BizException.of(ErrorCode.PRINT_BLOCKED);
    }
}
