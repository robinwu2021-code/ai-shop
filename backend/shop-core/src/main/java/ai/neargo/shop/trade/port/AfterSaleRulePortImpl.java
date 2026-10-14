package ai.neargo.shop.trade.port;

import ai.neargo.shop.spi.trade.AfterSaleRulePort;
import ai.neargo.shop.trade.entity.OrdAfterSale;
import ai.neargo.shop.trade.service.AfterSaleRuleService;
import org.springframework.stereotype.Component;

/** 转发给 {@link AfterSaleRuleService} —— 规则只有一份，这里不重判。 */
@Component
public class AfterSaleRulePortImpl implements AfterSaleRulePort {

    private final AfterSaleRuleService ruleService;

    public AfterSaleRulePortImpl(AfterSaleRuleService ruleService) {
        this.ruleService = ruleService;
    }

    @Override
    public boolean instantRefundCovers(long amountMinor) {
        // placedAt 传 null：还没有订单，「下单 N 小时内」那一条在这里不适用（见 Port 的注释）
        return ruleService.instantEligible(OrdAfterSale.REFUND_ONLY, amountMinor, null);
    }
}
