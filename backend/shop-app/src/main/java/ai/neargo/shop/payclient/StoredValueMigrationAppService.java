package ai.neargo.shop.payclient;

import java.util.List;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.StoreAuthority;
import ai.neargo.shop.spi.storedvalue.StoredValueMigrationPlans.*;

/** Trusted Core-to-Pay audit preparation only. No financial cutover/activation. */
public interface StoredValueMigrationAppService {
    Probe probe(List<StoreAuthority> scopes);
    Manifest prepare(Preparation input,String key);
}
