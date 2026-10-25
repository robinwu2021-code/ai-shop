package ai.neargo.shop.payclient.impl;

import ai.neargo.shop.payclient.StoredValueMigrationAppService;
import ai.neargo.shop.spi.platform.AuditLogPort;
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
    private final ai.neargo.shop.pay.storedvalue.migration.StoredValueMigrationOperator operator;
    private final AuditLogPort auditLogPort;
    private final StoredValueMigrationManifestService target;
    private final StoredValueMigrationExecutionService execution;
    public StoredValueMigrationAppServiceImpl(StoredValueMigrationManifestService target,StoredValueMigrationExecutionService execution,ai.neargo.shop.pay.storedvalue.migration.StoredValueMigrationOperator operator,AuditLogPort auditLogPort){this.auditLogPort=auditLogPort;this.operator=operator;this.target=target;this.execution=execution;}
    public MaintenanceResult pause(Preparation input,String manifestNo,String key,Proof proof){return execution.pause(input,manifestNo,key,proof);}
    public MaintenanceResult apply(Preparation input,String manifestNo,String pauseReceiptNo,String key,Proof proof){return execution.apply(input,manifestNo,pauseReceiptNo,key,proof);}
    public OperatorEvidence operatorSources(String actor,String approval,Proof proof){
        var result=operator.sources(actor,approval,proof);
        auditLogPort.record("SV_MIGRATION_OPERATOR_SOURCES",approval,"operator="+actor+", approval="+approval);
        return result;
    }
    public ExecutionCommand execute(String action,String actor,String approval,Proof proof,String expectedApprovalHash){
        var result=operator.execute(action,actor,approval,proof,expectedApprovalHash);
        auditLogPort.record("SV_MIGRATION_OPERATOR_EXECUTE",approval,
                "operator="+actor+", action="+action+", command="+result.commandNo()+", state="+result.state(),true);
        return result;
    }
    public ExecutionCommand command(String actor,String approval,String no,Proof proof){
        var result=operator.command(actor,approval,no,proof);
        auditLogPort.record("SV_MIGRATION_OPERATOR_COMMAND",no,"operator="+actor+", approval="+approval);
        return result;
    }
    public Probe probe(List<StoreAuthority> scopes){return target.probe(scopes);}
    public Manifest prepare(Preparation input,String key){return target.prepareTrusted(input,key);}
}
