package ai.neargo.shop.spi.storedvalue;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.*;
import ai.neargo.shop.spi.storedvalue.StoredValueWallets.ExecutionScope;
import java.util.List;
public final class StoredValueBrandActivation {
    private StoredValueBrandActivation() { }
    public record Prepare(String activationNo,AuthorizedScope authority,View issuer,long sourceRevision,List<ExecutionScope> stores){
        public Prepare{ContractChecks.id(activationNo);ContractChecks.present(authority);ContractChecks.present(issuer);ContractChecks.require("BRAND".equals(authority.scopeType())&&"BRAND".equals(issuer.scopeType())&&authority.scopeNo().equals(issuer.scopeNo())&&sourceRevision>=0);stores=List.copyOf(stores);ContractChecks.require(stores.size()<=1000&&stores.stream().map(ExecutionScope::storeNo).distinct().count()==stores.size());}
    }
    public record Capability(int protocol,boolean ready,List<String> blockers){public Capability{blockers=List.copyOf(blockers);}}
    public record Ack(String activationNo,String issuerNo,String issuerVersionNo,long sourceRevision,int acceptedStores,String state) { }
}
