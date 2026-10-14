package ai.neargo.shop.merchant.api.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.merchant.service.PayoutAccountService;
import ai.neargo.shop.merchant.service.PayoutAccountService.PayoutAccountVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商家端 · 我的收款账户（ADR-011 自营供应商模式）。
 *
 * <p>自营模式下平台按账期把货款转到这张卡。<b>换卡是提交一张新的、等运营核</b>，
 * 不是原地改账号 —— 原地改会让「上一期打给谁」这个问题失去答案。
 *
 * <p><b>提交上来的账号是明文，出去的只有掩码</b>：{@link PayoutAccountVO} 里
 * 压根没有明文字段，所以这条链上不存在「不小心回传了账号」这种可能。
 */
@Profile("api")
@RestController
@Validated
public class BizPayoutAccountController {

    private final PayoutAccountService payoutAccounts;

    public BizPayoutAccountController(PayoutAccountService payoutAccounts) {
        this.payoutAccounts = payoutAccounts;
    }

    /** 我的收款账户（含历史）。倒序，账号只有掩码。 */
    @PreAuthorize("@perm.canBiz('" + BizPerms.FINANCE + "')")
    @GetMapping("/biz/payout-account")
    public List<PayoutAccountVO> mine() {
        return payoutAccounts.myAccounts(BizContext.requireMerchantNo());
    }

    /**
     * 提交收款账户。落库即待审，<b>不能立刻收钱</b>。
     *
     * <p>两道校验在 service 层：户名必须等于营业执照主体名（三流一致）、
     * 不能已有在审的账户。放在这里的话，将来多一个入口（运营代录）就会漏掉。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.FINANCE + "')")
    @PostMapping("/biz/payout-account")
    public PayoutAccountVO submit(@Valid @RequestBody SubmitReq req) {
        return payoutAccounts.submit(BizContext.requireMerchantNo(),
                new PayoutAccountService.SubmitCommand(req.accountType(), req.accountName(),
                        req.accountNumber(), req.bankName(), req.bankBranch()));
    }

    /**
     * @param accountName   户名。<b>必须与营业执照主体名一致</b>，否则这笔支出税上站不住
     * @param accountNumber 银行账号<b>明文</b>。服务层加密后即丢弃，不回传、不进日志
     */
    public record SubmitReq(@NotBlank String accountType,
                            @NotBlank String accountName,
                            @NotBlank String accountNumber,
                            String bankName,
                            String bankBranch) {
    }
}
