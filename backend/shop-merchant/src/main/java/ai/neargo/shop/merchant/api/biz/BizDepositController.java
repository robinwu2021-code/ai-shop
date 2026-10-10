package ai.neargo.shop.merchant.api.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.merchant.service.AdmissionService;
import ai.neargo.shop.merchant.service.AdmissionService.DepositVO;
import ai.neargo.shop.merchant.service.AdmissionService.TxnVO;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商家端 · 我的保证金与额度（落地清单 F-6）。
 *
 * <p><b>这个接口不是可选的</b>：没有它，商家上架时被 70008 拦下、下单时被 70010 拦下，
 * 却无处得知「应缴多少、已缴多少、额度是多少」，只能来问客服。
 * <b>准入规则一旦对商家不透明，拦截就变成了故障。</b>
 */
@Profile("api")
@RestController
@Validated
public class BizDepositController {

    private final AdmissionService admissionService;

    public BizDepositController(AdmissionService admissionService) {
        this.admissionService = admissionService;
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.FINANCE + "')")
    @GetMapping("/biz/deposit")
    public DepositVO deposit() {
        return admissionService.deposit(BizContext.requireMerchantNo());
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.FINANCE + "')")
    @GetMapping("/biz/deposit/txns")
    public List<TxnVO> txns() {
        return admissionService.txns(BizContext.requireMerchantNo());
    }
}
