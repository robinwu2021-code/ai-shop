package ai.neargo.shop.payclient.impl;

import ai.neargo.shop.payclient.StoredValueMigrationAppService;
import ai.neargo.shop.pay.storedvalue.migration.StoredValueMigrationManifestService;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.StoreAuthority;
import ai.neargo.shop.spi.storedvalue.StoredValueMigrationPlans.*;
import java.util.List;
import ai.neargo.shop.spi.storedvalue.StoredValueMigrationReadiness.Proof;
import ai.neargo.shop.pay.storedvalue.migration.StoredValueMigrationExecutionService;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Service
@ConditionalOnProperty(name="shop.pay.deployment",havingValue="embedded",matchIfMissing=true)
public class StoredValueMigrationAppServiceImpl implements StoredValueMigrationAppService {
    private final StoredValueMigrationManifestService target;
    private final StoredValueMigrationExecutionService execution;
    public StoredValueMigrationAppServiceImpl(StoredValueMigrationManifestService target,StoredValueMigrationExecutionService execution){this.target=target;this.execution=execution;}
    public MaintenanceResult pause(Preparation input,String manifestNo,String key,Proof proof){return execution.pause(input,manifestNo,key,proof);}
    public MaintenanceResult apply(Preparation input,String manifestNo,String pauseReceiptNo,String key,Proof proof){return execution.apply(input,manifestNo,pauseReceiptNo,key,proof);}
    public Probe probe(List<StoreAuthority> scopes){return target.probe(scopes);}
    public Manifest prepare(Preparation input,String key){return target.prepareTrusted(input,key);}
}
