package ai.neargo.shop.pay.storedvalue.migration;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.StoreAuthority;
import ai.neargo.shop.spi.storedvalue.StoredValueMigrationPlans;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import static ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository.number;
import static ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository.text;



/** Stable business API; implementation keeps the original transaction and owner checks. */
public interface StoredValueMigrationManifestService {
    public record Evidence(StoredValueIssuerMigrationService.Batch batch,List<StoreAuthority> authorities) {
        public Evidence { authorities=List.copyOf(authorities); }
        public Evidence canonical() {
            if(batch==null||!"MAIN".equals(batch.tenantNo())||batch.stores().isEmpty()||batch.stores().size()>100
                    ||authorities.size()!=batch.stores().size())throw BizException.of(ErrorCode.BAD_REQUEST);
            ContractChecks.id(batch.actorNo());
            var seen=new HashSet<String>();
            for(var store:batch.stores()) {
                ContractChecks.id(store.storeNo());ContractChecks.id(store.entityNo());ContractChecks.id(store.issuerNo());
                if(!seen.add(store.storeNo()))throw BizException.of(ErrorCode.BAD_REQUEST);
                var scope=authorities.stream().filter(a->store.storeNo().equals(a.storeNo())).toList();
                if(scope.size()!=1||!batch.tenantNo().equals(scope.getFirst().tenantNo())||!batch.actorNo().equals(scope.getFirst().actorNo())
                        ||!store.entityNo().equals(scope.getFirst().entityNo()))throw BizException.of(ErrorCode.FORBIDDEN);
            }
            var customers=new HashSet<String>();
            for(var person:batch.identities()) {
                ContractChecks.id(person.customerNo());ContractChecks.optionalId(person.userNo());
                if(!customers.add(person.customerNo()))throw BizException.of(ErrorCode.BAD_REQUEST);
            }
            var ordered=new StoredValueIssuerMigrationService.Batch(batch.tenantNo(),batch.actorNo(),
                    batch.stores().stream().sorted(Comparator.comparing(StoredValueIssuerMigrationService.StoreMapping::issuerNo)).toList(),
                    batch.identities().stream().sorted(Comparator.comparing(StoredValueIssuerMigrationService.IdentityEvidence::customerNo)).toList());
            return new Evidence(ordered,authorities.stream().sorted(Comparator.comparing(StoreAuthority::storeNo)).toList());
        }
        public void verifyPayScope(IssuerRepository db,boolean lock) {
            for(var store:batch.stores()) {
                var scope=authorities.stream().filter(a->store.storeNo().equals(a.storeNo())).findFirst().orElseThrow();
                var access=db.one("SELECT * FROM sv_issuer_store_access WHERE tenant_no=? AND issuer_no=? AND store_no=? AND deleted=0"+(lock?" FOR UPDATE":""),batch.tenantNo(),store.issuerNo(),store.storeNo());
                if(access==null||!scope.entityNo().equals(text(access,"operating_entity_no"))||!scope.storeNo().equals(text(access,"source_binding_no"))
                        ||scope.storeRevision()!=number(access,"source_revision")||!Set.of("PREPARING","ACTIVE").contains(text(access,"status")))
                    throw BizException.of(ErrorCode.CONFLICT);
            }
        }
    }

    public record Manifest(String manifestNo,String fingerprint,String authorityHash,long accountCount) { }

    public record SavedManifest(Manifest manifest,Evidence evidence,StoredValueIssuerMigrationService.Report report) { }

    public record Applied(String manifestNo,String state,StoredValueIssuerMigrationService.MappingResult mapping) { }

    public record Fenced(String manifestNo,String state,long issuerCount) { }

    StoredValueMigrationPlans.Probe probe(List<StoreAuthority> input);

    StoredValueMigrationPlans.Manifest prepareTrusted(StoredValueMigrationPlans.Preparation input,String key);

    Evidence trusted(StoredValueMigrationPlans.Preparation input);

    Manifest prepare(Evidence input,String key);

    Manifest find(Evidence input,String manifestNo);

    Fenced fence(Evidence input,String manifestNo,String key);

    Applied apply(Evidence input,String manifestNo,String key);

    void verifyMappedFacts(Evidence evidence,List<StoredValueIssuerMigrationService.AccountMapping> expected);

    <T>T command(Evidence evidence,String operation,String key,String payload,String resource,Class<T> type,Supplier<T> work);
}
