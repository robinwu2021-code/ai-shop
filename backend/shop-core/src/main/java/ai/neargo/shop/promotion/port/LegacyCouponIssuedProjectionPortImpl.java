package ai.neargo.shop.promotion.port;
import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.*;
import ai.neargo.shop.promotion.mapper.*;
import ai.neargo.shop.promotion.migration.PmtPayCouponProjection;
import ai.neargo.shop.promotion.service.ActivityCommandDeduplicator;
import ai.neargo.shop.spi.entitlement.*;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;
@Component
public class LegacyCouponIssuedProjectionPortImpl implements LegacyCouponIssuedProjectionPort {
    private final PmtPayCouponProjectionMapper projections;
    private final LegacyCouponSourceCommitMapper decisions;
    private final ObjectMapper json;
    public LegacyCouponIssuedProjectionPortImpl(PmtPayCouponProjectionMapper projections,LegacyCouponSourceCommitMapper decisions,ObjectMapper json) {
        this.projections=projections;this.decisions=decisions;this.json=json;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void record(EntitlementContext ctx,List<Right> rights) {
        check(ctx);ContractChecks.require(rights!=null && rights.size()<=1000);
        DataScopeContext.executeWithoutScope(()->{
            for(var r:rights.stream().sorted(Comparator.comparing(Right::instanceNo)).toList()) {
                var source=decisions.findSource(ctx.tenantNo(),r.sourceSystem(),r.sourceTemplateNo());
                if(source==null || !r.entityNo().equals(source.getEntityNo()) || !r.migrationBatchNo().equals(source.getMigrationBatchNo())
                        || !r.planHash().equals(source.getPlanHash())) conflict();
                String hash=ActivityCommandDeduplicator.hash(json.writeValueAsString(r));
                if(projections.originalPromo(ctx.tenantNo(),r.instanceNo())!=0 || projections.originalLegacy(ctx.tenantNo(),r.instanceNo())!=0) conflict();
                var row=new PmtPayCouponProjection();row.setTenantNo(ctx.tenantNo());row.setVersion(0L);row.setDeleted(0);
                var now=LocalDateTime.ofInstant(Instant.ofEpochMilli(System.currentTimeMillis()),ZoneOffset.UTC);
                row.setCreatedAt(now);row.setUpdatedAt(now);row.setCreatedBy(ctx.actorNo());row.setUpdatedBy(ctx.actorNo());
                row.setInstanceNo(r.instanceNo());row.setUserNo(r.userNo());row.setEntityNo(r.entityNo());row.setSourceSystem(r.sourceSystem());
                row.setSourceTemplateNo(r.sourceTemplateNo());row.setTargetTemplateNo(r.targetTemplateNo());row.setTemplateVersionNo(r.templateVersionNo());
                row.setMigrationBatchNo(r.migrationBatchNo());row.setPlanHash(r.planHash());row.setIssueNo(r.issueNo());row.setGrantNo(r.grantNo());row.setActorNo(r.actorNo());
                row.setFactHash(hash);row.setOccurredAt(LocalDateTime.ofInstant(r.grantedAt(),ZoneOffset.UTC));projections.claim(row);
                var accepted=projections.lock(ctx.tenantNo(),r.instanceNo());if(accepted==null || !hash.equals(accepted.getFactHash())) conflict();
            }
        });
    }
    public List<Right> owned(EntitlementContext ctx,String user) {
        check(ctx);ContractChecks.id(user);
        return DataScopeContext.executeWithoutScope(()->projections.selectList(Wrappers.<PmtPayCouponProjection>lambdaQuery()
                .eq(PmtPayCouponProjection::getTenantNo,ctx.tenantNo()).eq(PmtPayCouponProjection::getUserNo,user).orderByDesc(PmtPayCouponProjection::getId))
                .stream().map(r->new Right(r.getInstanceNo(),r.getUserNo(),r.getEntityNo(),r.getSourceSystem(),r.getSourceTemplateNo(),r.getTargetTemplateNo(),
                        r.getTemplateVersionNo(),r.getMigrationBatchNo(),r.getPlanHash(),r.getIssueNo(),r.getGrantNo(),r.getActorNo(),r.getOccurredAt().toInstant(ZoneOffset.UTC))).toList());
    }
    private static void check(EntitlementContext ctx) {ContractChecks.present(ctx);if(!"MAIN".equals(ctx.tenantNo())) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);}
    private static void conflict() {throw BizException.of(ErrorCode.CONFLICT);}
}
