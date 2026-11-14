package ai.neargo.shop.payclient.impl;

import ai.neargo.shop.payclient.StoredValueMigrationAppService;
import ai.neargo.shop.spi.platform.AuditLogPort;
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
    private final AuditLogPort auditLogPort;
    private final PayInternalApi target;
    public RemoteStoredValueMigrationAppService(PayInternalApi target,AuditLogPort auditLogPort){this.auditLogPort=auditLogPort;this.target=target;}
    public MaintenanceResult pause(Preparation input,String manifestNo,String key,Proof proof){return RemoteEntitlementCalls.call(()->target.svMigrationPause(new PayStoredValueMigrationRequests.Pause(input,manifestNo,key,proof)));}
    public MaintenanceResult apply(Preparation input,String manifestNo,String pauseReceiptNo,String key,Proof proof){return RemoteEntitlementCalls.call(()->target.svMigrationApply(new PayStoredValueMigrationRequests.Apply(input,manifestNo,pauseReceiptNo,key,proof)));}
    public OperatorEvidence operatorSources(String operator,String approval,Proof proof){
        var result=RemoteEntitlementCalls.call(()->target.svMigrationSources(new PayStoredValueMigrationRequests.Operator(null,operator,approval,null,proof)));
        auditLogPort.record("SV_MIGRATION_OPERATOR_SOURCES",approval,"operator="+operator+", approval="+approval);
        return result;
    }
    public ExecutionCommand execute(String action,String operator,String approval,Proof proof,String expectedApprovalHash){
        var result=RemoteEntitlementCalls.call(()->target.svMigrationOperator(new PayStoredValueMigrationRequests.Operator(action,operator,approval,null,proof,expectedApprovalHash)));
        auditLogPort.record("SV_MIGRATION_OPERATOR_EXECUTE",approval,
                "operator="+operator+", action="+action+", command="+result.commandNo()+", state="+result.state(),true);
        return result;
    }
    public ExecutionCommand command(String operator,String approval,String no,Proof proof){
        var result=RemoteEntitlementCalls.call(()->target.svMigrationCommand(new PayStoredValueMigrationRequests.Operator(null,operator,approval,no,proof)));
        auditLogPort.record("SV_MIGRATION_OPERATOR_COMMAND",no,"operator="+operator+", approval="+approval);
        return result;
    }
    public Probe probe(List<StoreAuthority> scopes){return RemoteEntitlementCalls.call(()->target.svMigrationProbe(new PayStoredValueMigrationRequests.Probe(scopes)));}
    public Manifest prepare(Preparation input,String key){return RemoteEntitlementCalls.call(()->target.svMigrationPrepare(new PayStoredValueMigrationRequests.Prepare(input,key)));}
}
