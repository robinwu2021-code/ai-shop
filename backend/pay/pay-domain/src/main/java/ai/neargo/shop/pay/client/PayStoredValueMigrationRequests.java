package ai.neargo.shop.pay.client;

import java.util.List;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.StoreAuthority;
import ai.neargo.shop.spi.storedvalue.StoredValueMigrationPlans.Preparation;

public final class PayStoredValueMigrationRequests {
    private PayStoredValueMigrationRequests() { }
    public record Probe(List<StoreAuthority> scopes) { public Probe { scopes=List.copyOf(scopes); } }
    public record Prepare(Preparation input,String key) { }
}
