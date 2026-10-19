package ai.neargo.shop.pay.entitlement.port;

import ai.neargo.shop.common.CursorPage;
import ai.neargo.shop.pay.entitlement.service.EntitlementQueryService;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort;
import org.springframework.stereotype.Component;

/** Read boundary projects only consumer-safe DTOs, never ORM rows or internal authorization snapshots. */
@Component
public class EntitlementQueryPortImpl implements EntitlementQueryPort {
    private final EntitlementQueryService service;
    public EntitlementQueryPortImpl(EntitlementQueryService service) { this.service = service; }
    @Override
    public CursorPage<EntitlementItem> mine(EntitlementContext c, AssetQuery q) {
        return service.mine(c, q);
    }
    @Override
    public EntitlementDetail detail(EntitlementContext c, DetailQuery q) {
        return service.detail(c, q);
    }
    @Override
    public UsageDetail usage(EntitlementContext c, UsageQuery q) {
        return service.usage(c, q);
    }
    @Override
    public AllowanceView allowances(EntitlementContext c, AllowanceQuery q) {
        return service.allowances(c, q);
    }
    @Override
    public ActivationQuote activationQuote(EntitlementContext c, DetailQuery q) {
        return service.activationQuote(c, q);
    }
}
