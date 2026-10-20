package ai.neargo.shop.spi.entitlement;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Service-authenticated, non-sensitive header of a fixed pay plan. Source owner must recheck every frozen source. */
public record LegacyCouponMigrationPlan(int protocolVersion,String tenantNo,String planNo,String entityNo,
        String migrationBatchNo,String planHash,String sourceBundleHash,List<Source> sources) {
    public LegacyCouponMigrationPlan {
        ContractChecks.require(protocolVersion==1);ContractChecks.id(tenantNo);ContractChecks.id(planNo);
        ContractChecks.id(entityNo);ContractChecks.id(migrationBatchNo);ContractChecks.hash(planHash);ContractChecks.hash(sourceBundleHash);
        sources=List.copyOf(ContractChecks.present(sources));ContractChecks.require(!sources.isEmpty() && sources.size()<=2);
        var stages=new HashSet<String>();var systems=new HashSet<String>();
        for(var source:sources) ContractChecks.require(stages.add(source.stageNo()) && systems.add(source.sourceSystem())
                && migrationBatchNo.equals(source.batchNo()));
        if(sources.size()==2) ContractChecks.require("PMT".equals(sources.getFirst().sourceSystem())
                && "MKT_LEGACY".equals(sources.getLast().sourceSystem())
                && sources.getFirst().sourceTemplateNo().equals(sources.getLast().sourceTemplateNo()));
    }
    /** Field order matches the encrypted target plan's frozen source references. */
    public record Source(String stageNo,String sourceSystem,String sourceTemplateNo,String batchNo,
                         long frozenRevision,String watermark,String contentHash) {
        public Source {
            ContractChecks.id(stageNo);ContractChecks.id(sourceTemplateNo);ContractChecks.id(batchNo);
            ContractChecks.require(Set.of("PMT","MKT_LEGACY").contains(sourceSystem) && frozenRevision>=0);
            ContractChecks.hash(watermark);ContractChecks.hash(contentHash);
        }
        public EntitlementOwnerRegistryPort.SourceIdentity identity() {
            return new EntitlementOwnerRegistryPort.SourceIdentity(EntitlementOwnerRegistryPort.SourceSystem.valueOf(sourceSystem),
                    EntitlementOwnerRegistryPort.SourceKind.TEMPLATE,sourceTemplateNo);
        }
    }
}
