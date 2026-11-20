package ai.neargo.shop.paybridge.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.*;
import ai.neargo.shop.spi.entitlement.LegacyCouponIdentityPort;
import ai.neargo.shop.spi.entitlement.LegacyCouponIdentityPort.Origin;
import java.util.Comparator;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Exact route metadata only. The checkout coordinator must still verify holder, rule, source and financial decisions. */
@Component
public class LegacyCouponRouteResolver {
    private final LegacyCouponIdentityPort identities;
    private final EntitlementOwnerRegistryPort owners;
    public LegacyCouponRouteResolver(LegacyCouponIdentityPort identities,EntitlementOwnerRegistryPort owners) {
        this.identities=identities; this.owners=owners;
    }
    public Route resolve(EntitlementContext ctx,String user,String no) {
        ContractChecks.present(ctx); ContractChecks.id(user); ContractChecks.id(no);
        var origins=identities.ownedOrigins(ctx,user,no);
        if(origins==null) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        var visible=origins.stream().filter(o -> !o.deleted()).toList();
        for(var origin:visible) {
            if(!origin.rootPresent() || !no.equals(origin.source().sourceNo()) || "UNRESOLVED".equals(origin.rootState()))
                conflict();
        }
        if(visible.isEmpty()) {
            if(!origins.isEmpty()) throw BizException.of(ErrorCode.NOT_FOUND);
            // An authoritative native registration is required; absence of an old row is not ownership proof.
            requirePayTenant(ctx);
            outsideTransaction();
            var source=new SourceIdentity(SourceSystem.PAY,SourceKind.INSTANCE,no);
            var owner=owners.resolve(ctx,source);
            if(owner==null || !source.equals(owner.source()) || owner.ownerSystem()!=OwnerSystem.PAY
                    || owner.status()!=MigrationState.CUTOVER || !no.equals(owner.targetNo())) conflict();
            return new Route(OwnerSystem.PAY,source,owner.targetNo(),null,owner.migrationBatchNo(),owner.cutoverWatermark());
        }
        boolean ordinary=visible.stream().allMatch(o -> Set.of("OPEN","ABORT_DECIDED").contains(o.rootState()));
        if(ordinary) {
            var chosen=visible.stream().min(Comparator.comparingInt(o -> o.source().sourceSystem()==SourceSystem.PMT?0:1))
                    .orElseThrow();
            return new Route(OwnerSystem.CORE_LEGACY,chosen.source(),null,chosen.entityNo(),chosen.migrationBatchNo(),null);
        }
        if(visible.stream().anyMatch(o -> Set.of("DRAINING","FROZEN").contains(o.rootState())))
            throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        if(visible.stream().anyMatch(o -> !"COMMIT_DECIDED".equals(o.rootState()) || !o.entityConsistent()
                || !"MERCHANT".equals(o.funder()) || o.entityNo()==null || o.migrationBatchNo()==null
                || o.cutoverProofHash()==null)) conflict();
        requirePayTenant(ctx);
        outsideTransaction();
        var chosen=visible.stream().min(Comparator.comparingInt(o -> o.source().sourceSystem()==SourceSystem.PMT?0:1))
                .orElseThrow();
        String target=null;
        for(var origin:visible) {
            if(!chosen.entityNo().equals(origin.entityNo()) || !chosen.migrationBatchNo().equals(origin.migrationBatchNo())
                    || !chosen.cutoverProofHash().equals(origin.cutoverProofHash())) conflict();
            var owner=owners.resolve(ctx,origin.source());
            if(owner==null || !origin.source().equals(owner.source()) || owner.ownerSystem()!=OwnerSystem.PAY
                    || owner.status()!=MigrationState.CUTOVER || !origin.migrationBatchNo().equals(owner.migrationBatchNo())
                    || !origin.cutoverProofHash().equals(owner.cutoverWatermark())) conflict();
            if(target!=null && !target.equals(owner.targetNo())) conflict();
            target=owner.targetNo();
        }
        return new Route(OwnerSystem.PAY,chosen.source(),target,chosen.entityNo(),chosen.migrationBatchNo(),chosen.cutoverProofHash());
    }
    private static void requirePayTenant(EntitlementContext ctx) {
        if(!"MAIN".equals(ctx.tenantNo())) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
    }
    private static void outsideTransaction() {
        if(TransactionSynchronizationManager.isActualTransactionActive()) conflict();
    }
    private static void conflict() { throw BizException.of(ErrorCode.CONFLICT); }
    public record Route(OwnerSystem ownerSystem,SourceIdentity source,String targetNo,String entityNo,
                        String migrationBatchNo,String cutoverProofHash) {}
}
