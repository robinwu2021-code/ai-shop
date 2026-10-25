package ai.neargo.shop.trade.service;

import ai.neargo.shop.spi.trade.IndustryFulfillmentPort;
import ai.neargo.shop.spi.trade.PricingFeeFacts.Definition;
import ai.neargo.shop.spi.trade.PricingFeeFacts.Plan;
import ai.neargo.shop.spi.trade.PricingFeeFacts.Rule;
import ai.neargo.shop.spi.trade.PricingFeeScopePort.AdjustmentAuthority;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Snapshot;

import java.util.List;

/**
 * 收费规则：配置读写，以及下单/改单时的收费计划与人工调整授权。
 *
 * <p>跨域调用方经 {@code trade.port.PricingFeeRulePortImpl} 进来（{@code PricingFeeRulePort} /
 * {@code PricingFeeScopePort}），那里只做委托；业务实现与事务边界都在 {@code PricingFeeRuleServiceImpl}。
 */
public interface PricingFeeRuleService {
    record SaveReq(long expectedVersion, Definition definition, String status, String reason) {}

    Rule save(Scope scope, String actor, String ruleNo, SaveReq request, String key);

    Rule get(Scope scope, String ruleNo);

    Plan resolve(Snapshot source);

    void applyPlan(Scope scope, String actor, String orderNo, String subOrderNo, String quoteNo, Plan plan);

    void reconcileOnAmendment(IndustryFulfillmentPort.Reference reference, String amendmentNo, String sourceHash);

    IndustryFulfillmentPort.Reference originalReference(Scope scope, String applicationNo);

    List<AdjustmentAuthority> pendingAdjustments(Scope scope, String scopeNo);

    AdjustmentAuthority adjustment(Scope scope, String scopeNo, String applicationNo, String sourceHash);

    AdjustmentAuthority authorizeAdjustment(Scope scope, String applicationNo, long targetFeeMinor, String sourceHash);

    void completeAdjustment(
            Scope scope, String applicationNo, long targetFeeMinor, String sourceHash, String actor, String key);
}
