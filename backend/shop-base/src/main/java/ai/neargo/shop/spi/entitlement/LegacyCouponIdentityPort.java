package ai.neargo.shop.spi.entitlement;

import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceIdentity;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceKind;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceSystem;
import java.util.List;
import java.util.Set;

/** Core-owned legacy origin facts. Missing registry entries never determine a financial writer. */
public interface LegacyCouponIdentityPort {
    record TemplateReference(String entityNo,String templateNo) { }
    default List<TemplateReference> committedTemplates(EntitlementContext context,SourceSystem system) {return List.of();}
    default java.util.Optional<String> templateEntity(EntitlementContext context,SourceSystem system,String templateNo) {return java.util.Optional.empty();}
    List<Origin> ownedOrigins(EntitlementContext context,String userNo,String originalInstanceNo);

    /** No bearer code or consumer identity is returned; the source query already binds the requested holder. */
    record Origin(SourceIdentity source,String templateNo,String entityNo,String funder,String rootState,
                  String migrationBatchNo,String cutoverProofHash,boolean rootPresent,boolean deleted,
                  boolean entityConsistent) {
        public Origin {
            ContractChecks.present(source); ContractChecks.id(templateNo); ContractChecks.optionalId(entityNo);
            ContractChecks.optionalId(migrationBatchNo);
            ContractChecks.require(source.sourceKind()==SourceKind.INSTANCE && source.sourceSystem()!=SourceSystem.PAY);
            ContractChecks.require(rootState!=null && Set.of("OPEN","DRAINING","FROZEN","COMMIT_DECIDED",
                    "ABORT_DECIDED","UNRESOLVED").contains(rootState));
            if(cutoverProofHash!=null) {
                ContractChecks.hash(cutoverProofHash);
                ContractChecks.require(rootPresent && migrationBatchNo!=null && "COMMIT_DECIDED".equals(rootState));
            }
        }
    }
}
