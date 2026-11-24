package ai.neargo.shop.invoice.api.biz;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.invoice.dto.InvoiceDtos.StoreItemReq;
import ai.neargo.shop.invoice.dto.InvoiceDtos.StoreItemVO;
import ai.neargo.shop.invoice.dto.InvoiceDtos.StoreSettingsReq;
import ai.neargo.shop.invoice.dto.InvoiceDtos.StoreSettingsVO;
import ai.neargo.shop.invoice.service.InvoiceStoreSetupService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 门店开票设定与常用品项（TDD-统一订单与开票资料 AC6 / AC11，手持机 I01）。门店级，门店取 {@code X-Store-No}。
 *
 * <p><b>读用 {@code biz:invoice:read}、写用 {@code biz:invoice:setup}</b>：收银员要读默认税别与常用品项
 * 才能在输入金额时快选（店员有读码），改设定只给店长 —— 与税制、号段同一个设置码。
 */
@Profile("api")
@RestController
@Validated
public class BizInvoiceStoreSetupController {

    private final InvoiceStoreSetupService setup;

    public BizInvoiceStoreSetupController(InvoiceStoreSetupService setup) {
        this.setup = setup;
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.INVOICE_READ + "')")
    @GetMapping("/biz/invoice-setup/store-settings")
    public StoreSettingsVO settings() {
        return setup.settings(BizInvoiceScope.current());
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.INVOICE_SETUP + "')")
    @PutMapping("/biz/invoice-setup/store-settings")
    public StoreSettingsVO saveSettings(@Valid @RequestBody StoreSettingsReq req) {
        return setup.saveSettings(BizInvoiceScope.current(), req);
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.INVOICE_READ + "')")
    @GetMapping("/biz/invoice-setup/store-items")
    public List<StoreItemVO> items() {
        return setup.items(BizInvoiceScope.current());
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.INVOICE_SETUP + "')")
    @PostMapping("/biz/invoice-setup/store-items")
    public StoreItemVO addItem(@Valid @RequestBody StoreItemReq req) {
        return setup.addItem(BizInvoiceScope.current(), req);
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.INVOICE_SETUP + "')")
    @PutMapping("/biz/invoice-setup/store-items/{storeItemNo}")
    public StoreItemVO updateItem(@PathVariable @Size(max = 32) String storeItemNo,
            @Valid @RequestBody StoreItemReq req) {
        return setup.updateItem(BizInvoiceScope.current(), storeItemNo, req);
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.INVOICE_SETUP + "')")
    @DeleteMapping("/biz/invoice-setup/store-items/{storeItemNo}")
    public void deleteItem(@PathVariable @Size(max = 32) String storeItemNo) {
        setup.deleteItem(BizInvoiceScope.current(), storeItemNo);
    }
}
