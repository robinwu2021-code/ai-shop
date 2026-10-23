package ai.neargo.shop.payclient.impl;

import ai.neargo.shop.payclient.StoredValueMigrationAppService;
import ai.neargo.shop.pay.storedvalue.migration.StoredValueMigrationManifestService;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.StoreAuthority;
import ai.neargo.shop.spi.storedvalue.StoredValueMigrationPlans.*;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Service
@ConditionalOnProperty(name="shop.pay.deployment",havingValue="embedded",matchIfMissing=true)
public class StoredValueMigrationAppServiceImpl implements StoredValueMigrationAppService {
    private final StoredValueMigrationManifestService target;
    public StoredValueMigrationAppServiceImpl(StoredValueMigrationManifestService target){this.target=target;}
    public Probe probe(List<StoreAuthority> scopes){return target.probe(scopes);}
    public Manifest prepare(Preparation input,String key){return target.prepareTrusted(input,key);}
}
