package ai.neargo.shop.trade.service;

import ai.neargo.shop.spi.trade.PricingFeeFacts.Definition;
import ai.neargo.shop.spi.trade.PricingFeeFacts.Rule;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;

public interface PricingFeeRuleService {
    record SaveReq(long expectedVersion, Definition definition, String status, String reason) {}

    Rule save(Scope scope, String actor, String ruleNo, SaveReq request, String key);

    Rule get(Scope scope, String ruleNo);
}
