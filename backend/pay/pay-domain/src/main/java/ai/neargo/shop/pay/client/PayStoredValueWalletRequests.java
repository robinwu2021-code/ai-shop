package ai.neargo.shop.pay.client;
import ai.neargo.shop.spi.storedvalue.StoredValueWallets.Query;
import ai.neargo.shop.spi.entitlement.ContractChecks;
public final class PayStoredValueWalletRequests {
    private PayStoredValueWalletRequests() { }
    public record Records(Select selection,String type,java.time.Instant from,java.time.Instant to,String cursor,int size){public Records{ContractChecks.present(selection);ContractChecks.require(size>0&&size<=100);}}
    public record Select(Query query,String issuerRef,String accountRef){public Select{ContractChecks.present(query);ContractChecks.require(issuerRef!=null&&accountRef!=null);}}
}
