package ai.neargo.shop.spi.storedvalue;

import ai.neargo.shop.spi.entitlement.ContractChecks;
import java.util.List;

/** Trusted Core facts, never accepted verbatim from a public request. Wallet references bind this execution scope. */
public final class StoredValueWallets {
    private StoredValueWallets() { }
    public record ExecutionScope(String tenantNo,String actorNo,String managementNo,String entityNo,String storeNo,
            long storeRevision,String brandNo,String brandBindingNo,long brandBindingRevision,String currency,long brandRevision,@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT) int coreFinanceProtocol) {
        public ExecutionScope(String tenantNo,String actorNo,String managementNo,String entityNo,String storeNo,long storeRevision,String brandNo,String brandBindingNo,long brandBindingRevision,String currency,long brandRevision){this(tenantNo,actorNo,managementNo,entityNo,storeNo,storeRevision,brandNo,brandBindingNo,brandBindingRevision,currency,brandRevision,0);}
        public ExecutionScope(String tenantNo,String actorNo,String managementNo,String entityNo,String storeNo,long storeRevision,String brandNo,String brandBindingNo,long brandBindingRevision,String currency){this(tenantNo,actorNo,managementNo,entityNo,storeNo,storeRevision,brandNo,brandBindingNo,brandBindingRevision,currency,brandNo==null?-1:0);}
        public ExecutionScope {
            for(String id:List.of(tenantNo,actorNo,entityNo,storeNo))ContractChecks.id(id);
            ContractChecks.currency(currency);ContractChecks.require(storeRevision>=0&&coreFinanceProtocol>=0);ContractChecks.optionalId(managementNo);
            ContractChecks.optionalId(brandNo);ContractChecks.optionalId(brandBindingNo);
            ContractChecks.require(brandNo==null?brandBindingNo==null&&brandBindingRevision==-1&&brandRevision==-1:managementNo!=null&&brandBindingNo!=null&&brandBindingRevision>=0&&brandRevision>=0);
        }
    }
    /** A canonical platform person already resolved from a scoped member selection, not a phone number. */
    public record Customer(String customerNo,String userNo) {
        public Customer {ContractChecks.id(customerNo);ContractChecks.optionalId(userNo);}
        @Override public String toString(){return "Customer[REDACTED]";}
    }
    public record Balance(long balanceMinor,long heldMinor,long availableMinor) {
        public Balance {ContractChecks.require(balanceMinor>=0&&heldMinor>=0&&heldMinor<=balanceMinor&&availableMinor==balanceMinor-heldMinor);}
    }
    public record AvailableAccount(String scopeType,String issuerRef,String name,String currency,String accountRef,
            Balance account,boolean canRecharge,boolean canRedeem,String unavailableReason) { }
    public record Query(ExecutionScope scope,Customer customer) {public Query{ContractChecks.present(scope);ContractChecks.present(customer);}}
    /** Internal immutable selection. Account number/issuer must stay bound through quote and consumer consent. */
    public record Selected(String issuerNo,String issuerVersionNo,String scopeType,String scopeNo,String accountNo,
            String customerNo,String currency,long assetRevision,String issuerName) { }
    public record Choice(Query query,String issuerRef,String accountRef){public Choice{ContractChecks.present(query);ContractChecks.require(issuerRef!=null&&accountRef!=null);}}
    public record Proof(ExecutionScope scope,Selected selected,String userNo){
        public Proof{ContractChecks.present(scope);ContractChecks.present(selected);ContractChecks.id(userNo);ContractChecks.require(scope.currency().equals(selected.currency()));}
        @Override public String toString(){return "Proof[REDACTED]";}
    }
}
