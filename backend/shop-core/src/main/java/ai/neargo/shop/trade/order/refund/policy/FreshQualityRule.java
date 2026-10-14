package ai.neargo.shop.trade.order.refund.policy;

import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.trade.order.policy.AfterSaleRules;
import ai.neargo.shop.trade.order.policy.FulfillStage;

import java.util.Optional;

/** 生鲜已揽收 / 已签收仅受理质量类原因（包装既有 AfterSaleRules.applyGate）。 */
public class FreshQualityRule {

    public Optional<ErrorCode> check(FulfillStage stage, boolean fresh, String reasonCode) {
        return AfterSaleRules.applyGate(stage, fresh, reasonCode).allow()
                ? Optional.empty() : Optional.of(ErrorCode.AFTERSALE_FRESH_QUALITY_ONLY);
    }
}
