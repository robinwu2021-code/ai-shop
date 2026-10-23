package ai.neargo.shop.trade.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.entitlement.EntitlementCapabilityPort;
import ai.neargo.shop.spi.storedvalue.StoredValueCapabilityPort;
import ai.neargo.shop.spi.trade.PricingFundingCapabilities;
import ai.neargo.shop.spi.trade.PricingFundingCapabilities.Family;
import ai.neargo.shop.spi.trade.PricingFundingCapabilities.Scope;
import ai.neargo.shop.spi.trade.PricingFundingCapabilities.Snapshot;
import ai.neargo.shop.spi.trade.PricingFundingCapabilities.Status;
import ai.neargo.shop.trade.service.PricingFundingCapabilityService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Missing, ambiguous or old providers fail closed; no cached capability grants or transaction-held remote calls. */
@Service
public class PricingFundingCapabilityServiceImpl implements PricingFundingCapabilityService {
    private final ObjectProvider<EntitlementCapabilityPort> coupons;
    private final ObjectProvider<StoredValueCapabilityPort> stored;
    public PricingFundingCapabilityServiceImpl(ObjectProvider<EntitlementCapabilityPort> coupons,
            ObjectProvider<StoredValueCapabilityPort> stored) { this.coupons = coupons; this.stored = stored; }
    @Override public Snapshot inspect(Family family, Scope scope) {
        if (family == null || scope == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw blocked();
        Snapshot value;
        try {
            if (family == Family.ORDER_COUPON) {
                var owners = coupons.orderedStream().limit(2).toList();
                if (owners.size() != 1) throw blocked();
                value = owners.getFirst().pricing(scope);
            } else {
                var owners = stored.orderedStream().limit(2).toList();
                if (owners.size() != 1) throw blocked();
                value = owners.getFirst().pricing(scope);
            }
        } catch (UnsupportedOperationException unsupported) { throw blocked(); }
        try { PricingFundingCapabilities.validate(value, family, scope); }
        catch (RuntimeException invalid) { throw blocked(); }
        return value;
    }
    @Override public Snapshot require(Family family, Scope scope) {
        var value = inspect(family, scope);
        if (value.status() != Status.SUPPORTED) throw blocked();
        return value;
    }
    @Override public Snapshot confirm(Family family, Scope scope, String expectedSnapshotHash) {
        if (expectedSnapshotHash == null || !expectedSnapshotHash.matches("[0-9a-f]{64}")) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        var current = inspect(family, scope);
        if (!current.snapshotHash().equals(expectedSnapshotHash)) throw BizException.of(ErrorCode.CONFLICT);
        if (current.status() != Status.SUPPORTED) throw blocked();
        return current;
    }
    private static BizException blocked() { return BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED); }
}
