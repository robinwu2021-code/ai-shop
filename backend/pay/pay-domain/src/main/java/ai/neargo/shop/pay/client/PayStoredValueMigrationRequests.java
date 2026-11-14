package ai.neargo.shop.pay.client;

import java.util.List;
import ai.neargo.shop.spi.storedvalue.StoredValueMigrationReadiness.Proof;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.StoreAuthority;
import ai.neargo.shop.spi.storedvalue.StoredValueMigrationPlans.Preparation;

public final class PayStoredValueMigrationRequests {
    public record Operator(String action,String operator,String approval,String commandNo,ai.neargo.shop.spi.storedvalue.StoredValueMigrationReadiness.Proof proof,String expectedApprovalHash) {
        public Operator(String action,String operator,String approval,String commandNo,Proof proof){this(action,operator,approval,commandNo,proof,null);}
    }
    private PayStoredValueMigrationRequests() { }
    public record Pause(Preparation input,String manifestNo,String key,Proof proof) { }
    public record Apply(Preparation input,String manifestNo,String pauseReceiptNo,String key,Proof proof) { }
    public record Probe(List<StoreAuthority> scopes) { public Probe { scopes=List.copyOf(scopes); } }
    public record Prepare(Preparation input,String key) { }
}
