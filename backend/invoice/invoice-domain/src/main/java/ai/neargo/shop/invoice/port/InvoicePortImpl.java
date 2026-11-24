package ai.neargo.shop.invoice.port;

import ai.neargo.shop.invoice.service.InvoiceService;
import ai.neargo.shop.invoice.service.InvoiceStoreSetupService;
import ai.neargo.shop.spi.invoice.InvoicePort;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** 行业开票入口的同进程实现；拆服务那天换成远程实现，行业侧不动。 */
@Component
public class InvoicePortImpl implements InvoicePort {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(InvoicePortImpl.class);

    private final InvoiceService invoices;
    private final InvoiceStoreSetupService stores;
    private final ai.neargo.shop.invoice.service.InvoiceTransmissionService transmissions;
    private final boolean syncUpload;

    public InvoicePortImpl(InvoiceService invoices, InvoiceStoreSetupService stores,
            ai.neargo.shop.invoice.service.InvoiceTransmissionService transmissions,
            @org.springframework.beans.factory.annotation.Value("${shop.invoice.transmission.sync:false}") boolean syncUpload) {
        this.invoices = invoices;
        this.stores = stores;
        this.transmissions = transmissions;
        this.syncUpload = syncUpload;
    }

    @Override
    public void uploadNow(Scope scope, String invoiceNo) {
        if (!syncUpload) return;
        try {
            transmissions.transmitNow(scope, invoiceNo);
        } catch (RuntimeException e) {
            log.warn("INVOICE_SYNC_UPLOAD_FAILED invoice={} error={}", invoiceNo, e.toString());
        }
    }

    @Override
    public InvoiceReceipt issue(Scope scope, String sourceType, String sourceNo, Buyer buyer) {
        return invoices.issue(scope, sourceType, sourceNo, buyer);
    }

    @Override
    public Optional<InvoiceReceipt> activeOf(Scope scope, String sourceType, String sourceNo) {
        return invoices.activeOf(scope, sourceType, sourceNo);
    }

    @Override
    public void rememberBuyer(Scope scope, String sourceType, String sourceNo, Buyer buyer) {
        invoices.rememberBuyer(scope, sourceType, sourceNo, buyer);
    }

    @Override
    public Optional<Buyer> rememberedBuyer(Scope scope, String sourceType, String sourceNo) {
        return invoices.rememberedBuyer(scope, sourceType, sourceNo);
    }

    @Override
    public Optional<InvoiceReceipt> issueIfAutomatic(Scope scope, String sourceType, String sourceNo) {
        return invoices.issueIfAutomatic(scope, sourceType, sourceNo);
    }

    @Override
    public StoreSettings storeSettings(Scope scope) {
        var vo = stores.settings(scope);
        return new StoreSettings(vo.defaultTaxType(), vo.invoiceTiming());
    }

    @Override
    public Optional<StoreItem> storeItem(Scope scope, String storeItemNo) {
        return Optional.ofNullable(stores.item(scope, storeItemNo))
                .map(i -> new StoreItem(i.storeItemNo(), i.name(), i.taxType(), i.enabled()));
    }
}
