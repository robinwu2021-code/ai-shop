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
    public OperatorEvidence operatorSources(String operator,String approval,Proof proof){return RemoteEntitlementCalls.call(()->target.svMigrationSources(new PayStoredValueMigrationRequests.Operator(null,operator,approval,null,proof)));}
    public ExecutionCommand execute(String action,String operator,String approval,Proof proof,String expectedApprovalHash){return RemoteEntitlementCalls.call(()->target.svMigrationOperator(new PayStoredValueMigrationRequests.Operator(action,operator,approval,null,proof,expectedApprovalHash)));}
    public ExecutionCommand command(String operator,String approval,String no,Proof proof){return RemoteEntitlementCalls.call(()->target.svMigrationCommand(new PayStoredValueMigrationRequests.Operator(null,operator,approval,no,proof)));}
    public Probe probe(List<StoreAuthority> scopes){return RemoteEntitlementCalls.call(()->target.svMigrationProbe(new PayStoredValueMigrationRequests.Probe(scopes)));}
    public Manifest prepare(Preparation input,String key){return RemoteEntitlementCalls.call(()->target.svMigrationPrepare(new PayStoredValueMigrationRequests.Prepare(input,key)));}
}
