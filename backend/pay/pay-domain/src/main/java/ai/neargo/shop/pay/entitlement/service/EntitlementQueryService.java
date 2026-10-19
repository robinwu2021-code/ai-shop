package ai.neargo.shop.pay.entitlement.service;

import ai.neargo.shop.common.CursorPage;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort.AssetQuery;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort.DetailQuery;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort.UsageQuery;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort.AllowanceQuery;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort.EntitlementItem;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort.EntitlementDetail;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort.UsageDetail;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort.AllowanceView;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort.ActivationQuote;

/** Read projections have no activation, balance change or event side effects. */
public interface EntitlementQueryService {
    CursorPage<EntitlementItem> mine(EntitlementContext context, AssetQuery query);
    EntitlementDetail detail(EntitlementContext context, DetailQuery query);
    UsageDetail usage(EntitlementContext context, UsageQuery query);
    AllowanceView allowances(EntitlementContext context, AllowanceQuery query);
    ActivationQuote activationQuote(EntitlementContext context, DetailQuery query);
}
