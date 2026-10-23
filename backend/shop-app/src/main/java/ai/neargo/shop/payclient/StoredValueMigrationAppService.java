package ai.neargo.shop.payclient;

import java.util.List;
import ai.neargo.shop.spi.storedvalue.StoredValueMigrationReadiness.Proof;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.StoreAuthority;
import ai.neargo.shop.spi.storedvalue.StoredValueMigrationPlans.*;

/** Trusted Core-to-Pay audit and paused metadata maintenance. No key DDL or activation. */
public interface StoredValueMigrationAppService {
    MaintenanceResult pause(Preparation input,String manifestNo,String key,Proof proof);
    MaintenanceResult apply(Preparation input,String manifestNo,String pauseReceiptNo,String key,Proof proof);
    Probe probe(List<StoreAuthority> scopes);
    Manifest prepare(Preparation input,String key);
}
