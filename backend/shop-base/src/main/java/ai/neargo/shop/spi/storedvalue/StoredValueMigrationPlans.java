package ai.neargo.shop.spi.storedvalue;

import java.util.List;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.StoreAuthority;

/** Service-to-service migration evidence. Never bind these records from a public merchant request. */
public final class StoredValueMigrationPlans {
    private StoredValueMigrationPlans() { }
    public record Scope(StoreAuthority authority,String issuerNo) { }
    /** Minimal existing ledger identity; no balances, names or phone numbers. */
    public record Subject(String accountNo,String storeNo,String entityNo,String customerNo,String userNo) { }
    public record Probe(List<Scope> scopes,List<Subject> subjects) {
        public Probe { scopes=List.copyOf(scopes);subjects=List.copyOf(subjects); }
    }
    public record Identity(String customerNo,String userNo) { }
    public record Preparation(List<Scope> scopes,List<Identity> identities) {
        public Preparation { scopes=List.copyOf(scopes);identities=List.copyOf(identities); }
    }
    public record MaintenanceResult(String receiptNo,String manifestNo,String state,String inventoryHash,long issuerCount,long accountCount) { }
    public record Manifest(String manifestNo,String fingerprint,String authorityHash,long accountCount) { }
}
