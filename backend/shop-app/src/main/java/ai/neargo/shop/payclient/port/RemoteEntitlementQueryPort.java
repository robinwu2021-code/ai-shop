package ai.neargo.shop.payclient.port;

import ai.neargo.shop.pay.client.PayInternalApi;
import ai.neargo.shop.pay.client.PayEntitlementRequests;
import ai.neargo.shop.payclient.RemoteEntitlementCalls;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ai.neargo.shop.common.CursorPage;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort;

@Component
@ConditionalOnProperty(name = "shop.pay.deployment", havingValue = "standalone")
public class RemoteEntitlementQueryPort implements EntitlementQueryPort {
    private final PayInternalApi pay;
    public RemoteEntitlementQueryPort(PayInternalApi pay) { this.pay = pay; }
    @Override public CursorPage<EntitlementItem> mine(EntitlementContext c, AssetQuery q) {
        return RemoteEntitlementCalls.call(() -> pay.entMine(new PayEntitlementRequests.Mine(c, q)));
    }
    @Override public EntitlementDetail detail(EntitlementContext c, DetailQuery q) {
        return RemoteEntitlementCalls.call(() -> pay.entDetail(new PayEntitlementRequests.Detail(c, q)));
    }
    @Override public PageData<UsageItem> merchantUsage(EntitlementContext c, BizUsageQuery q) {
        return RemoteEntitlementCalls.call(() -> pay.entMerchantUsage(new PayEntitlementRequests.MerchantUsage(c, q)));
    }
    @Override public UsageDetail usage(EntitlementContext c, UsageQuery q) {
        return RemoteEntitlementCalls.call(() -> pay.entUsage(new PayEntitlementRequests.Usage(c, q)));
    }
    @Override public CursorPage<UsageItem> history(EntitlementContext c,UsageHistoryQuery q) {
        return RemoteEntitlementCalls.call(()->pay.entHistory(new PayEntitlementRequests.UsageHistory(c,q)));
    }
    @Override public AllowanceView allowances(EntitlementContext c, AllowanceQuery q) {
        return RemoteEntitlementCalls.call(() -> pay.entAllowances(new PayEntitlementRequests.Allowances(c, q)));
    }
    @Override public ActivationQuote activationQuote(EntitlementContext c, DetailQuery q) {
        return RemoteEntitlementCalls.call(() -> pay.entActivation(new PayEntitlementRequests.Activation(c, q)));
    }
}
