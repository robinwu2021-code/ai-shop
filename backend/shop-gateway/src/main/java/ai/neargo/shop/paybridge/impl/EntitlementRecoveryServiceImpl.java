package ai.neargo.shop.paybridge.impl;

import ai.neargo.shop.paybridge.EntitlementRecoveryService;
import ai.neargo.shop.paybridge.StoredValueRechargeCoordinator;
import ai.neargo.shop.paybridge.EntitlementOrderCoordinator;
import ai.neargo.shop.paybridge.ActivityBenefitCoordinator;
import ai.neargo.shop.trade.service.LegacyCouponCheckoutJournal;
import ai.neargo.shop.trade.service.LegacyCouponOrderCreationCoordinator;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.trade.dto.EntitlementRecoveryWork.Kind;
import ai.neargo.shop.trade.service.EntitlementRecoveryScanService;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

/** Recovery never invents decisions or keys: coordinators reload the original durable command and identity. */
@Service
@Transactional(propagation=Propagation.NOT_SUPPORTED)
public class EntitlementRecoveryServiceImpl implements EntitlementRecoveryService {
    private static final Set<String> COMPLETE=Set.of("COMMITTED","RELEASED","CREDITED","CANCELLED","ABORTED","REVERSED");
    private final EntitlementRecoveryScanService scans;
    private final LegacyCouponCheckoutJournal legacyJournal;
    private final ai.neargo.shop.paybridge.LegacyCouponHistoricalReturnCoordinator historical;
    private final LegacyCouponOrderCreationCoordinator legacy;
    private final StoredValueRechargeCoordinator recharge;
    private final EntitlementOrderCoordinator uses;
    private final ActivityBenefitCoordinator benefits;
    public EntitlementRecoveryServiceImpl(EntitlementRecoveryScanService scans,StoredValueRechargeCoordinator recharge,
            EntitlementOrderCoordinator uses,ActivityBenefitCoordinator benefits,LegacyCouponCheckoutJournal legacyJournal,
            LegacyCouponOrderCreationCoordinator legacy,ai.neargo.shop.paybridge.LegacyCouponHistoricalReturnCoordinator historical) {
        this.scans=scans; this.recharge=recharge; this.uses=uses; this.benefits=benefits;this.legacyJournal=legacyJournal;this.legacy=legacy;this.historical=historical;
    }
    @Override public Result recover(String tenant,Kind kind,long afterId,int limit) {
        var page=scans.scan(tenant,kind,afterId,limit);
        int completed=0,deferred=0,failed=0;
        for(var row:page.records()) {
            try {
                var ctx=new EntitlementContext(tenant,row.actorNo(),"ent-recovery-"+kind.name(),"0".repeat(64));
                String status=switch(kind) {
                    case HISTORICAL_COUPON_RETURN -> {historical.complete(ctx,row.entityNo(),row.resourceNo());yield "REVERSED";}
                    case LEGACY_COUPON -> legacyJournal.decideForRecovery(ctx,row.resourceNo())
                            ?legacy.recover(ctx,row.resourceNo()).status().name():"PENDING";
                    case RECHARGE -> recharge.recover(ctx,row.entityNo(),row.resourceNo()).status();
                    case SERVICE_USE,ORDER_USE -> uses.recover(ctx,row.entityNo(),row.resourceNo()).status().name();
                    case ACTIVITY_BENEFIT -> benefits.recover(ctx,row.resourceNo()).status();
                };
                if(COMPLETE.contains(status)) completed++; else deferred++;
            } catch(RuntimeException unknown) {
                // No cancellation, financial write or raw error logging on this path; original work stays pending.
                failed++;
            }
        }
        return new Result(page.records().size(),completed,deferred,failed,page.nextId());
    }
}
