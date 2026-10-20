package ai.neargo.shop.pay.migration.service.impl;
import ai.neargo.shop.common.*;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.policy.PayEntitlementFeatureGate;
import ai.neargo.shop.pay.entitlement.service.*;
import ai.neargo.shop.pay.mapper.entitlement.EntLegacyCouponIssueMapper;
import ai.neargo.shop.pay.migration.dto.LegacyCouponIssueViews.*;
import ai.neargo.shop.pay.migration.entity.EntLegacyCouponIssue;
import ai.neargo.shop.pay.migration.service.*;
import ai.neargo.shop.spi.entitlement.*;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
/** Fetch core audience before the pay capacity transaction. Recovery uses only the accepted native batch. */
@Service
public class LegacyCouponIssueServiceImpl implements LegacyCouponIssueService {
    private final ObjectProvider<LegacyCouponAudiencePort> audience;
    private final EntitlementCommandDeduplicator commands;
    private final LegacyCouponTemplateManagementService definitions;
    private final EntitlementCatalogService catalog;
    private final PayEntitlementFeatureGate features;
    private final LegacyCouponIssueWriter writer;
    private final EntLegacyCouponIssueMapper issues;
    private final EntitlementIssueBatchService batches;
    public LegacyCouponIssueServiceImpl(ObjectProvider<LegacyCouponAudiencePort> audience,EntitlementCommandDeduplicator commands,
            LegacyCouponTemplateManagementService definitions,EntitlementCatalogService catalog,PayEntitlementFeatureGate features,
            LegacyCouponIssueWriter writer,EntLegacyCouponIssueMapper issues,EntitlementIssueBatchService batches) {
        this.audience=audience;this.commands=commands;this.definitions=definitions;this.catalog=catalog;this.features=features;
        this.writer=writer;this.issues=issues;this.batches=batches;
    }
    public View issue(CatalogScope scope,Command command,String key) {
        outside(scope);ContractChecks.present(command);ContractChecks.id(key);
        var replay=commands.replay(scope,LegacyCouponIssueWriter.OPERATION,key,command,View.class);
        if(replay.isPresent()) return resume(scope,replay.get().issueNo(),1000);
        if(command.userKey()!=null && !scope.platformActor()) throw BizException.of(ErrorCode.FORBIDDEN);
        features.requireIssuance();var template=definitions.get(scope,command.reference());
        if(template.archivedAt()!=null || !"ACTIVE".equals(template.template().status()) || !template.template().issuePolicy().enabled()) throw BizException.of(ErrorCode.COUPON_NOT_ACTIVE);
        var version=catalog.getVersion(scope,template.template().currentVersionNo());
        if(!version.definition().allowedIssueModes().contains(command.mode())) throw BizException.of(ErrorCode.CONFLICT);
        var q=new LegacyCouponAudiencePort.Query(command.reference().source(),command.reference().batchNo(),command.reference().planHash(),
                scope.entityNo(),command.mode(),command.segmentNo(),command.audiences(),command.userKey(),command.count(),scope.platformActor());
        var owner=audience.getIfAvailable();if(owner==null) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        var source=owner.resolve(new EntitlementContext(scope.tenantNo(),scope.actorNo(),key,"0".repeat(64)),q);
        if(source==null || !q.equals(source.query()) || !scope.tenantNo().equals(source.tenantNo()) || !scope.actorNo().equals(source.actorNo())) throw BizException.of(ErrorCode.CONFLICT);
        var created=writer.prepare(scope,command,key,source,version);return resume(scope,created.issueNo(),1000);
    }
    public View get(CatalogScope scope,String no) {outside(scope);return writer.read(scope,owned(scope,no));}
    public View resume(CatalogScope scope,String no,int limit) {
        outside(scope);if(limit<1 || limit>1000) throw BizException.of(ErrorCode.BAD_REQUEST);
        var row=owned(scope,no);var current=writer.read(scope,row);if("COMPLETED".equals(current.status())) return current;
        for(int used=0;current.pending()>0 && used<limit;used+=100) {
            var before=current.pending();batches.resume(scope,row.getBatchNo(),Math.min(100,limit-used));current=writer.read(scope,row);
            if(current.pending()>=before) return current;
        }
        return current.pending()==0?writer.finish(scope,no):current;
    }
    public List<View> list(CatalogScope scope,String system,String no) {
        outside(scope);ContractChecks.id(system);ContractChecks.optionalId(no);
        return issues.selectList(Wrappers.<EntLegacyCouponIssue>lambdaQuery().eq(EntLegacyCouponIssue::getTenantNo,scope.tenantNo())
                .eq(EntLegacyCouponIssue::getEntityNo,scope.entityNo()).eq(EntLegacyCouponIssue::getSourceSystem,system)
                .eq(no!=null,EntLegacyCouponIssue::getSourceTemplateNo,no).orderByDesc(EntLegacyCouponIssue::getId))
                .stream().map(row->writer.read(scope,row)).toList();
    }
    private EntLegacyCouponIssue owned(CatalogScope scope,String no) {
        ContractChecks.id(no);var row=issues.selectOne(Wrappers.<EntLegacyCouponIssue>lambdaQuery().eq(EntLegacyCouponIssue::getTenantNo,scope.tenantNo())
                .eq(EntLegacyCouponIssue::getEntityNo,scope.entityNo()).eq(EntLegacyCouponIssue::getIssueNo,no));
        if(row==null) throw BizException.of(ErrorCode.NOT_FOUND);return row;
    }
    private static void outside(CatalogScope scope) {
        ContractChecks.present(scope);if(!"MAIN".equals(scope.tenantNo())) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        if(TransactionSynchronizationManager.isActualTransactionActive()) throw BizException.of(ErrorCode.CONFLICT);
    }
}
