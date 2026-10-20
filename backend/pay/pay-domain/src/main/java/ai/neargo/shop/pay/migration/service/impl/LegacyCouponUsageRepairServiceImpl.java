package ai.neargo.shop.pay.migration.service.impl;
import ai.neargo.shop.pay.migration.service.LegacyCouponUsageRepairService;
import ai.neargo.shop.pay.migration.service.LegacyCouponUseWriter;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.mapper.entitlement.EntLegacyCouponOperationMapper;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Bounded, restartable read-projection repair. Never posts assets/ledger or historical notifications. */
@Service("legacyCouponUsageRepairService")
public class LegacyCouponUsageRepairServiceImpl implements LegacyCouponUsageRepairService {
    private final EntLegacyCouponOperationMapper operations;
    private final LegacyCouponUseWriter writer;
    public LegacyCouponUsageRepairServiceImpl(EntLegacyCouponOperationMapper operations,LegacyCouponUseWriter writer) {
        this.operations=operations;this.writer=writer;
    }
    public Batch repair(EntitlementContext context,long afterId,int limit) {
        ContractChecks.present(context);ContractChecks.require(afterId>=0 && limit>=1 && limit<=100);
        if(!"MAIN".equals(context.tenantNo())) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Usage repair must start outside transactions");
        var rows=operations.missingUsage(context.tenantNo(),afterId,limit);
        int repaired=0,failed=0;
        for(var row:rows) {
            try { if(writer.repairUsage(context,row.getOperationNo())) repaired++; }
            catch(RuntimeException invalid) { failed++; }
        }
        return new Batch(rows.size(),repaired,failed,rows.size()==limit?rows.getLast().getId():0);
    }
    
}
