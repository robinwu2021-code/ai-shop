package ai.neargo.shop.invoice.api.biz;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.invoice.dto.InvoiceDtos.NumberRangeReq;
import ai.neargo.shop.invoice.dto.InvoiceDtos.NumberRangeVO;
import ai.neargo.shop.invoice.dto.InvoiceDtos.ProviderRangeReq;
import ai.neargo.shop.invoice.dto.InvoiceDtos.ProviderOptionsVO;
import ai.neargo.shop.invoice.dto.InvoiceDtos.ProviderVerificationVO;
import ai.neargo.shop.invoice.dto.InvoiceDtos.RegimeProfileReq;
import ai.neargo.shop.invoice.dto.InvoiceDtos.RegimeProfileVO;
import ai.neargo.shop.invoice.service.InvoiceSetupService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
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
 * <p>QR 加密密钥与加值中心账号密码只能写不能读：响应里只回「是否已配置」，本身进 secret 模块。
 * 加值中心（鲸跃）模式下号段由服务商配给：先核验账号，再按期取回号段。
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

    /** 某市场商家可选的开票供应商（默认的排第一）；不传市场取系统默认市场。 */
    @PreAuthorize("@perm.canBiz('" + BizPerms.INVOICE_SETUP + "')")
    @GetMapping("/biz/invoice-setup/providers")
    public ProviderOptionsVO providers(@RequestParam(required = false) @Size(max = 16) String regimeCode) {
        return setup.providerOptions(regimeCode);
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

    @PreAuthorize("@perm.canBiz('" + BizPerms.INVOICE_SETUP + "')")
    @PostMapping("/biz/invoice-setup/provider-verification")
    public ProviderVerificationVO verifyProvider() {
        return setup.verifyProvider(BizInvoiceScope.current());
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.INVOICE_SETUP + "')")
    @PostMapping("/biz/invoice-setup/provider-ranges")
    public NumberRangeVO fetchProviderRange(@Valid @RequestBody(required = false) ProviderRangeReq req) {
        return setup.fetchProviderRange(BizInvoiceScope.current(), req == null ? null : req.periodCode());
    }
}
