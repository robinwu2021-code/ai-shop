package ai.neargo.shop.merchant.port;

import ai.neargo.shop.merchant.service.DebtService;
import ai.neargo.shop.spi.user.MerchantDebtPort;
import org.springframework.stereotype.Component;

/** {@link MerchantDebtPort} 的薄转发：规则（幂等、非正数不记）都在 {@link DebtService} 里，这里不重复 */
@Component
public class MerchantDebtPortImpl implements MerchantDebtPort {

    private final DebtService debtService;

    public MerchantDebtPortImpl(DebtService debtService) {
        this.debtService = debtService;
    }

    @Override
    public long incur(String entityNo, long amountMinor, String sourceType, String sourceNo, String reason) {
        return debtService.incur(entityNo, amountMinor, sourceType, sourceNo, reason);
    }
}
