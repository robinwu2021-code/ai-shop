package ai.neargo.shop.pay.migration.dto;
import ai.neargo.shop.pay.entitlement.dto.*;
import ai.neargo.shop.spi.entitlement.*;
public final class LegacyCouponTemplateViews {
    private LegacyCouponTemplateViews() { }
    public record Reference(EntitlementOwnerRegistryPort.SourceIdentity source,String targetNo,String batchNo,String planHash) {
        public Reference {
            ContractChecks.present(source);ContractChecks.id(targetNo);ContractChecks.id(batchNo);ContractChecks.hash(planHash);
            ContractChecks.require(source.sourceKind()==EntitlementOwnerRegistryPort.SourceKind.TEMPLATE
                    && source.sourceSystem()!=EntitlementOwnerRegistryPort.SourceSystem.PAY);
        }
    }
    public record DefinitionChange(EntitlementDefinition definition,Long limitCount,Long budgetMinor,int perUserLimit) {
        public DefinitionChange {
            ContractChecks.present(definition);ContractChecks.require(perUserLimit>=1 && (limitCount==null || limitCount>=0)
                    && (budgetMinor==null || budgetMinor>0));
        }
    }
    public record Snapshot(ManagedTemplateView template,EntitlementDefinition definition,long usedTimes,long spentMinor,long actorHeldCount,long actorIssuedCount,java.time.Instant archivedAt,long usedCouponCount) {
        public Snapshot(ManagedTemplateView template,EntitlementDefinition definition,long usedTimes,long spentMinor) {this(template,definition,usedTimes,spentMinor,0,0,null,usedTimes);}
    }
}
