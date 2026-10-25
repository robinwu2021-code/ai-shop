package ai.neargo.shop.trade.service;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort.Plan;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Command;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Credentials;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Delivery;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Party;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Snapshot;
import ai.neargo.shop.spi.trade.PricingSourceRef;

/**
 * Core source routing and fresh authorization; deliberately separate from final quote/asset
 * admission.
 */
public interface PricingSourceService {
    default Party party(Command command) {
        throw BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);
    }

    default Plan demandPlan(Command command, String orderNo, String subOrderNo) {
        return null;
    }

    default Delivery delivery(Command command) {
        throw BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);
    }

    Snapshot staff(Scope scope, PricingSourceRef source);

    Snapshot cancellationForStore(Command command, String key, String fulfillment);

    Snapshot customer(PricingSourceRef source, Credentials credentials);

    Snapshot confirmStaff(Scope scope, PricingSourceRef source, String expectedSnapshotHash);

    Snapshot confirmCustomer(
            PricingSourceRef source, Credentials credentials, String expectedSnapshotHash);
}
