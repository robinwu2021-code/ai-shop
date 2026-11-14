package ai.neargo.shop.pay.funds;

import ai.neargo.shop.pay.ledger.LedgerAccount;
import org.springframework.stereotype.Component;

/** DIRECT：钱进商户自己的渠道账户。平台只记备查账（平台外），不碰平台余额 */
@Component
public class DirectFundsPathStrategy extends AbstractFundsPathStrategy {

    @Override
    public String fundsPath() {
        return "DIRECT";
    }

    @Override
    protected LedgerAccount collectedInto() {
        return LedgerAccount.MERCHANT_CHANNEL;
    }

    @Override
    protected LedgerAccount owedTo() {
        return LedgerAccount.MERCHANT_SALES;
    }
}
