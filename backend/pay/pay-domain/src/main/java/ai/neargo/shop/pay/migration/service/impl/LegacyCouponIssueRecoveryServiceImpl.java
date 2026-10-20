package ai.neargo.shop.pay.migration.service.impl;
import ai.neargo.shop.pay.migration.service.LegacyCouponIssueRecoveryService;
import ai.neargo.shop.pay.migration.service.LegacyCouponIssueService;
import ai.neargo.shop.common.*;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.mapper.entitlement.EntLegacyCouponIssueMapper;
import ai.neargo.shop.pay.migration.entity.EntLegacyCouponIssue;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
@Service("legacyCouponIssueRecoveryService")
public class LegacyCouponIssueRecoveryServiceImpl implements LegacyCouponIssueRecoveryService {
    private final EntLegacyCouponIssueMapper issues;
    private final LegacyCouponIssueService issuer;
    public LegacyCouponIssueRecoveryServiceImpl(EntLegacyCouponIssueMapper issues,LegacyCouponIssueService issuer) {this.issues=issues;this.issuer=issuer;}
    
    /** Never reselects recipients or creates another batch; a bounded resume finishes only accepted native grants. */
    public Result recover(String tenant,long afterId,int limit) {
        if(!"MAIN".equals(tenant)) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        if(afterId<0 || limit<1 || limit>100 || TransactionSynchronizationManager.isActualTransactionActive()) throw BizException.of(ErrorCode.BAD_REQUEST);
        var rows=issues.selectList(Wrappers.<EntLegacyCouponIssue>lambdaQuery().eq(EntLegacyCouponIssue::getTenantNo,tenant)
                .eq(EntLegacyCouponIssue::getStatus,"PREPARED").gt(EntLegacyCouponIssue::getId,afterId)
                .orderByAsc(EntLegacyCouponIssue::getId).last("LIMIT "+(limit+1)));
        boolean more=rows.size()>limit;if(more) rows=rows.subList(0,limit);
        int completed=0,deferred=0,failed=0;
        for(var row:rows) {
            try {var result=issuer.resume(new CatalogScope(tenant,row.getEntityNo(),row.getActorNo(),false),row.getIssueNo(),100);
                if("COMPLETED".equals(result.status())) completed++;else deferred++;
            } catch(RuntimeException unknown) {failed++;}
        }
        return new Result(rows.size(),completed,deferred,failed,more?rows.getLast().getId():0);
    }
}
