package ai.neargo.shop.pay.migration.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.entity.EntTemplate;
import ai.neargo.shop.pay.entitlement.entity.EntInstance;
import ai.neargo.shop.pay.entitlement.entity.EntUsage;
import ai.neargo.shop.pay.entitlement.service.EntitlementRecords;
import ai.neargo.shop.pay.mapper.entitlement.EntOwnerRegistryMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntTemplateMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntInstanceMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntUsageMapper;
import ai.neargo.shop.pay.migration.entity.EntOwnerRegistry;
import ai.neargo.shop.pay.migration.service.EntitlementOwnerRegistryService;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceIdentity;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceSystem;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceKind;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.OwnerRef;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.OwnerSystem;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.MigrationState;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Missing mappings and broken targets are explicit errors, never evidence that another writer should be tried. */
@Service
public class EntitlementOwnerRegistryServiceImpl implements EntitlementOwnerRegistryService {
    private final EntOwnerRegistryMapper owners;
    private final EntTemplateMapper templates;
    private final EntInstanceMapper instances;
    private final EntUsageMapper usages;
    public EntitlementOwnerRegistryServiceImpl(EntOwnerRegistryMapper owners,EntTemplateMapper templates,
            EntInstanceMapper instances,EntUsageMapper usages) {
        this.owners=owners; this.templates=templates; this.instances=instances; this.usages=usages;
    }
    @Override
    @Transactional("payTxManager")
    public OwnerRef registerLegacy(CatalogScope scope,SourceIdentity source,String batch) {
        if(scope==null || !scope.platformActor()) throw BizException.of(ErrorCode.FORBIDDEN);
        supported(scope.tenantNo()); ContractChecks.present(source); ContractChecks.id(batch);
        if(source.sourceSystem()==SourceSystem.PAY) throw BizException.of(ErrorCode.BAD_REQUEST);
        var ctx=new EntitlementContext(scope.tenantNo(),scope.actorNo(),batch,
                EntitlementRecords.digest(source.sourceSystem()+":"+source.sourceKind()+":"+source.sourceNo()));
        var candidate=new EntOwnerRegistry(); EntitlementRecords.audit(candidate,ctx);
        candidate.setSourceSystem(source.sourceSystem().name()); candidate.setSourceKind(source.sourceKind().name());
        candidate.setSourceNo(source.sourceNo()); candidate.setMigrationBatchNo(batch);
        owners.claimLegacy(candidate);
        var row=owners.lock(scope.tenantNo(),source.sourceSystem().name(),source.sourceKind().name(),source.sourceNo());
        if(row==null || !batch.equals(row.getMigrationBatchNo())) throw BizException.of(ErrorCode.CONFLICT);
        requireTarget(row);
        return view(row);
    }
    @Override public OwnerRef resolve(EntitlementContext ctx,SourceIdentity source) {
        ContractChecks.present(ctx); supported(ctx.tenantNo()); ContractChecks.present(source);
        var row=owners.selectOne(Wrappers.<EntOwnerRegistry>lambdaQuery()
                .eq(EntOwnerRegistry::getTenantNo,ctx.tenantNo())
                .eq(EntOwnerRegistry::getSourceSystem,source.sourceSystem().name())
                .eq(EntOwnerRegistry::getSourceKind,source.sourceKind().name())
                .eq(EntOwnerRegistry::getSourceNo,source.sourceNo()));
        if(row==null) throw BizException.of(ErrorCode.NOT_FOUND);
        requireTarget(row);
        return view(row);
    }
    private void requireTarget(EntOwnerRegistry row) {
        if(!"PAY".equals(row.getOwnerSystem())) return;
        if(row.getTargetNo()==null) throw BizException.of(ErrorCode.CONFLICT);
        long count=switch(SourceKind.valueOf(row.getSourceKind())) {
            case TEMPLATE -> templates.selectCount(Wrappers.<EntTemplate>lambdaQuery()
                    .eq(EntTemplate::getTenantNo,row.getTenantNo()).eq(EntTemplate::getTemplateNo,row.getTargetNo()));
            case INSTANCE -> instances.selectCount(Wrappers.<EntInstance>lambdaQuery()
                    .eq(EntInstance::getTenantNo,row.getTenantNo()).eq(EntInstance::getInstanceNo,row.getTargetNo()));
            case USAGE -> usages.selectCount(Wrappers.<EntUsage>lambdaQuery()
                    .eq(EntUsage::getTenantNo,row.getTenantNo()).eq(EntUsage::getUsageNo,row.getTargetNo()));
        };
        if(count!=1) throw BizException.of(ErrorCode.CONFLICT);
    }
    private static OwnerRef view(EntOwnerRegistry row) {
        return new OwnerRef(new SourceIdentity(SourceSystem.valueOf(row.getSourceSystem()),
                SourceKind.valueOf(row.getSourceKind()),row.getSourceNo()),row.getTargetNo(),
                OwnerSystem.valueOf(row.getOwnerSystem()),MigrationState.valueOf(row.getStatus()),
                row.getMigrationBatchNo(),row.getCutoverWatermark(),row.getVersion());
    }
    private static void supported(String tenant) {
        if(!"MAIN".equals(tenant)) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
    }
}
