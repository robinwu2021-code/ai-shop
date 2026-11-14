package ai.neargo.shop.pay.funds;

import ai.neargo.shop.pay.ledger.LedgerAccount;
import org.springframework.stereotype.Component;

/** AGGREGATED：钱进平台在渠道的余额，同时欠商户一笔待结 */
@Component
public class AggregatedFundsPathStrategy extends AbstractFundsPathStrategy {

    @Override
    public String fundsPath() {
        return "AGGREGATED";
    }

    @Override
    protected LedgerAccount collectedInto() {
        return LedgerAccount.PLATFORM_CHANNEL;
    }

    @Override
    protected LedgerAccount owedTo() {
        return LedgerAccount.MERCHANT_PAYABLE;
    }
}
