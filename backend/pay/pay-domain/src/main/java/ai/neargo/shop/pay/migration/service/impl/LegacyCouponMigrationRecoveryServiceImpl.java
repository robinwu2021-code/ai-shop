package ai.neargo.shop.pay.migration.service.impl;
import ai.neargo.shop.pay.migration.service.LegacyCouponMigrationImportService;
import ai.neargo.shop.pay.migration.service.LegacyCouponMigrationRecoveryService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.service.EntitlementRecords;
import ai.neargo.shop.pay.mapper.entitlement.EntMigrationPlanMapper;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
/** Advances only previously committed sources. An undecided or unavailable source stays pending. */
@Service("legacyCouponMigrationRecoveryService")
public class LegacyCouponMigrationRecoveryServiceImpl implements LegacyCouponMigrationRecoveryService {
    private final EntMigrationPlanMapper plans;
    private final LegacyCouponMigrationImportService imports;
    public LegacyCouponMigrationRecoveryServiceImpl(EntMigrationPlanMapper plans,LegacyCouponMigrationImportService imports) {
        this.plans=plans;this.imports=imports;
    }
    public Batch recover(String tenant,long afterId,int limit) {
        ContractChecks.require("MAIN".equals(tenant) && afterId>=0 && limit>=1 && limit<=100);
        if(TransactionSynchronizationManager.isActualTransactionActive()) throw BizException.of(ErrorCode.CONFLICT);
        var rows=plans.pendingImport(tenant,afterId,limit);
        int completed=0,deferred=0,failed=0;
        for(var row:rows) {
            try {
                String key="MR"+EntitlementRecords.digest(tenant+":"+row.getPlanNo()).substring(0,62);
                imports.importPlan(new CatalogScope(tenant,row.getEntityNo(),"MIGRATION_RECOVERY",true),row.getPlanNo(),"恢复原源提交的固定目标迁入",key);
                completed++;
            } catch(BizException pending) {
                if(pending.errorCode()==ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE) deferred++;else failed++;
            } catch(RuntimeException failure) { failed++; }
        }
        return new Batch(rows.size(),completed,deferred,failed,rows.size()==limit?rows.getLast().getId():0);
    }

}
