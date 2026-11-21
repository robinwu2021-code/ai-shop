package ai.neargo.shop.invoice.api.biz;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.invoice.dto.InvoiceDtos.NumberRangeReq;
import ai.neargo.shop.invoice.dto.InvoiceDtos.NumberRangeVO;
import ai.neargo.shop.invoice.dto.InvoiceDtos.RegimeProfileReq;
import ai.neargo.shop.invoice.dto.InvoiceDtos.RegimeProfileVO;
import ai.neargo.shop.invoice.service.InvoiceSetupService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * B 端发票设置：开通税制、登记字轨号段。主体级配置，仅持 {@code biz:invoice:setup} 的人可改。
 *
 * <p>QR 加密密钥只能写不能读：响应里只回「是否已配置」，密钥本身进 secret 模块。
 */
@Profile("api")
@RestController
@Validated
public class BizInvoiceSetupController {

    private final InvoiceSetupService setup;

    public BizInvoiceSetupController(InvoiceSetupService setup) {
        this.setup = setup;
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.INVOICE_SETUP + "')")
    @GetMapping("/biz/invoice-setup/profile")
    public RegimeProfileVO profile() {
        return setup.profile(BizInvoiceScope.current()).orElse(null);
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.INVOICE_SETUP + "')")
    @PutMapping("/biz/invoice-setup/profile")
    public RegimeProfileVO saveProfile(@Valid @RequestBody RegimeProfileReq req) {
        return setup.saveProfile(BizInvoiceScope.current(), req);
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.INVOICE_SETUP + "')")
    @GetMapping("/biz/invoice-setup/ranges")
    public List<NumberRangeVO> ranges(@RequestParam(required = false) String periodCode) {
        return setup.ranges(BizInvoiceScope.current(), periodCode);
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.INVOICE_SETUP + "')")
    @PostMapping("/biz/invoice-setup/ranges")
    public NumberRangeVO addRange(@Valid @RequestBody NumberRangeReq req) {
        return setup.addRange(BizInvoiceScope.current(), req);
    }
}
