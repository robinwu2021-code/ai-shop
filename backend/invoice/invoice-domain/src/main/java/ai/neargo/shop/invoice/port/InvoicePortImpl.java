package ai.neargo.shop.invoice.port;

import ai.neargo.shop.invoice.service.InvoiceService;
import ai.neargo.shop.invoice.service.InvoiceStoreSetupService;
import ai.neargo.shop.spi.invoice.InvoicePort;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** 行业开票入口的同进程实现；拆服务那天换成远程实现，行业侧不动。 */
@Component
public class InvoicePortImpl implements InvoicePort {

    private final InvoiceService invoices;
    private final InvoiceStoreSetupService stores;

    public InvoicePortImpl(InvoiceService invoices, InvoiceStoreSetupService stores) {
        this.invoices = invoices;
        this.stores = stores;
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
