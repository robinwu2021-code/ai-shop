package ai.neargo.shop.merchant.port;

import ai.neargo.shop.merchant.service.PayoutAccountService;
import ai.neargo.shop.spi.user.PayoutAccountPort;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * {@link PayoutAccountPort} 实现：薄转调 {@link PayoutAccountService}。
 *
 * <p>薄得像没有 —— 它的价值不在于做了什么，而在于<b>资金侧只认这个接口</b>：
 * 收款账户将来换存储或加一层审批，改的是商家域这一侧，资金侧一行不动。
 */
@Component
public class PayoutAccountPortImpl implements PayoutAccountPort {

    private final PayoutAccountService accounts;

    public PayoutAccountPortImpl(PayoutAccountService accounts) {
        this.accounts = accounts;
    }

    @Override
    public Optional<PayoutAccountBrief> activeAccount(String entityNo) {
        return accounts.activeAccount(entityNo).map(a -> new PayoutAccountBrief(
                a.accountNo(), a.accountType(), a.accountName(),
                a.accountMasked(), a.bankName(), a.bankBranch()));
    }

    @Override
    public String decryptAccountNumber(String accountNo) {
        return accounts.decryptAccountNumber(accountNo);
    }
}
