package ai.neargo.shop.payclient.impl;

import ai.neargo.shop.payclient.StoredValueMigrationAppService;
import ai.neargo.shop.payclient.RemoteEntitlementCalls;
import ai.neargo.shop.pay.client.*;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.StoreAuthority;
import ai.neargo.shop.spi.storedvalue.StoredValueMigrationPlans.*;
import java.util.List;
import ai.neargo.shop.spi.storedvalue.StoredValueMigrationReadiness.Proof;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Service
@ConditionalOnProperty(name="shop.pay.deployment",havingValue="standalone",matchIfMissing=false)
public class RemoteStoredValueMigrationAppService implements StoredValueMigrationAppService {
    private final PayInternalApi target;
    public RemoteStoredValueMigrationAppService(PayInternalApi target){this.target=target;}
    public MaintenanceResult pause(Preparation input,String manifestNo,String key,Proof proof){return RemoteEntitlementCalls.call(()->target.svMigrationPause(new PayStoredValueMigrationRequests.Pause(input,manifestNo,key,proof)));}
    public MaintenanceResult apply(Preparation input,String manifestNo,String pauseReceiptNo,String key,Proof proof){return RemoteEntitlementCalls.call(()->target.svMigrationApply(new PayStoredValueMigrationRequests.Apply(input,manifestNo,pauseReceiptNo,key,proof)));}
    public Probe probe(List<StoreAuthority> scopes){return RemoteEntitlementCalls.call(()->target.svMigrationProbe(new PayStoredValueMigrationRequests.Probe(scopes)));}
    public Manifest prepare(Preparation input,String key){return RemoteEntitlementCalls.call(()->target.svMigrationPrepare(new PayStoredValueMigrationRequests.Prepare(input,key)));}
}
