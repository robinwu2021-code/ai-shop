package ai.neargo.shop.paybridge.port;
import org.springframework.beans.factory.annotation.Autowired;


import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.paybridge.mapper.LegacyCouponFenceMapper;
import ai.neargo.shop.promotion.migration.LegacyCouponOriginRow;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceIdentity;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceKind;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceSystem;
import ai.neargo.shop.spi.entitlement.LegacyCouponIdentityPort;
import ai.neargo.shop.spi.entitlement.LegacyCouponSourceCommitPort;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** One fresh core RR snapshot for both origins. The optional decision reader is core-local, never a pay callback. */
@Component
public class LegacyCouponIdentityPortImpl implements LegacyCouponIdentityPort {
    private final LegacyCouponFenceMapper sources;
    @Autowired(required=false) private ai.neargo.shop.promotion.mapper.PmtPayCouponProjectionMapper issued;
    private final ObjectProvider<LegacyCouponSourceCommitPort> decisions;
    public LegacyCouponIdentityPortImpl(LegacyCouponFenceMapper sources,ObjectProvider<LegacyCouponSourceCommitPort> decisions) {
        this.sources=sources; this.decisions=decisions;
    }
    @Override public List<TemplateReference> committedTemplates(EntitlementContext ctx,SourceSystem system) {
        ContractChecks.present(ctx);
        return DataScopeContext.executeWithoutScope(()->{
            if(system==SourceSystem.PMT) return sources.committedPromoTemplates(ctx.tenantNo());
            if(system==SourceSystem.MKT_LEGACY) return sources.committedLegacyTemplates(ctx.tenantNo());
            throw BizException.of(ErrorCode.BAD_REQUEST);
        });
    }
    @Override public java.util.Optional<String> templateEntity(EntitlementContext ctx,SourceSystem system,String no) {
        ContractChecks.present(ctx);ContractChecks.id(no);
        return DataScopeContext.executeWithoutScope(()->{
            if(system==SourceSystem.PMT) {var root=sources.readPromo(ctx.tenantNo(),no);
                return root==null || Integer.valueOf(1).equals(root.getDeleted())?java.util.Optional.empty():java.util.Optional.ofNullable(root.getEntityNo());}
            if(system==SourceSystem.MKT_LEGACY) {var root=sources.readLegacy(ctx.tenantNo(),no);
                return root==null || Integer.valueOf(1).equals(root.getDeleted())?java.util.Optional.empty():java.util.Optional.ofNullable(root.getEntityNo());}
            throw BizException.of(ErrorCode.BAD_REQUEST);
        });
    }
    @Override
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,propagation=Propagation.REQUIRES_NEW)
    public List<Origin> ownedOrigins(EntitlementContext ctx,String user,String no) {
        ContractChecks.present(ctx); ContractChecks.id(user); ContractChecks.id(no);
        return DataScopeContext.executeWithoutScope(()->{
            var result=new ArrayList<Origin>();
            var pmt=sources.promoOrigin(ctx.tenantNo(),user,no);
            var legacy=sources.legacyOrigin(ctx.tenantNo(),user,no);
            if(issued!=null) {
                if(pmt==null) pmt=issued.promoOrigin(ctx.tenantNo(),user,no);
                if(legacy==null) legacy=issued.legacyOrigin(ctx.tenantNo(),user,no);
            }
            if(pmt!=null) result.add(origin(ctx,SourceSystem.PMT,pmt));
            if(legacy!=null) result.add(origin(ctx,SourceSystem.MKT_LEGACY,legacy));
            return List.copyOf(result);
        });
    }
    private Origin origin(EntitlementContext ctx,SourceSystem system,LegacyCouponOriginRow row) {
        boolean present=Boolean.TRUE.equals(row.getRootPresent());
        String state=present && row.getRootState()!=null?row.getRootState():"UNRESOLVED";
        String proof=null;
        if("COMMIT_DECIDED".equals(state)) {
            var reader=decisions.getIfAvailable();
            if(reader!=null) {
                var template=new SourceIdentity(system,SourceKind.TEMPLATE,row.getTemplateNo());
                var committed=reader.decided(ctx,template);
                if(committed==null) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
                if(committed.isPresent()) {
                    if(!template.equals(committed.get().template())
                            || !Objects.equals(row.getMigrationBatchNo(),committed.get().migrationBatchNo()))
                        throw BizException.of(ErrorCode.CONFLICT);
                    proof=committed.get().planHash();
                }
            }
        }
        boolean deleted=(row.getInstanceDeleted()!=null && row.getInstanceDeleted()!=0)
                || (row.getRootDeleted()!=null && row.getRootDeleted()!=0);
        boolean consistent=present && (system==SourceSystem.MKT_LEGACY
                || Objects.equals(row.getEntityNo(),row.getInstanceEntityNo()));
        return new Origin(new SourceIdentity(system,SourceKind.INSTANCE,row.getInstanceNo()),row.getTemplateNo(),
                row.getEntityNo(),row.getFunder(),state,row.getMigrationBatchNo(),proof,present,deleted,consistent);
    }
}
