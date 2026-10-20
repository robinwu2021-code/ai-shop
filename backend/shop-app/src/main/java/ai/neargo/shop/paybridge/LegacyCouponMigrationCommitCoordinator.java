package ai.neargo.shop.paybridge;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.payclient.EntitlementMigrationAppService;
import ai.neargo.shop.promotion.migration.LegacyCouponSourceCommitService;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Pay plan lookup finishes before entering the atomic core source transaction. No target import occurs here. */
@Service
public class LegacyCouponMigrationCommitCoordinator {
    private final EntitlementMigrationAppService plans;
    private final LegacyCouponSourceCommitService source;
    public LegacyCouponMigrationCommitCoordinator(EntitlementMigrationAppService plans,LegacyCouponSourceCommitService source) {
        this.plans=plans;this.source=source;
    }
    public LegacyCouponSourceCommitService.Decision commit(CatalogScope scope,String planNo,String reason,String key) {
        if(scope==null || !scope.platformActor()) throw BizException.of(ErrorCode.FORBIDDEN);
        if(TransactionSynchronizationManager.isActualTransactionActive()) throw BizException.of(ErrorCode.CONFLICT);
        var plan=plans.preparedPlan(scope,planNo);
        if(!scope.tenantNo().equals(plan.tenantNo()) || !scope.entityNo().equals(plan.entityNo())) throw BizException.of(ErrorCode.CONFLICT);
        return source.commit(new EntitlementContext(scope.tenantNo(),scope.actorNo(),key,plan.planHash()),plan,reason,key);
    }
}
