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
    private final ai.neargo.shop.pay.storedvalue.migration.StoredValueMigrationOperator operator;
    private final StoredValueMigrationManifestService target;
    private final StoredValueMigrationExecutionService execution;
    public StoredValueMigrationAppServiceImpl(StoredValueMigrationManifestService target,StoredValueMigrationExecutionService execution,ai.neargo.shop.pay.storedvalue.migration.StoredValueMigrationOperator operator){this.operator=operator;this.target=target;this.execution=execution;}
    public MaintenanceResult pause(Preparation input,String manifestNo,String key,Proof proof){return execution.pause(input,manifestNo,key,proof);}
    public MaintenanceResult apply(Preparation input,String manifestNo,String pauseReceiptNo,String key,Proof proof){return execution.apply(input,manifestNo,pauseReceiptNo,key,proof);}
    public OperatorEvidence operatorSources(String actor,String approval,Proof proof){return operator.sources(actor,approval,proof);}
    public ExecutionCommand execute(String action,String actor,String approval,Proof proof,String expectedApprovalHash){return operator.execute(action,actor,approval,proof,expectedApprovalHash);}
    public ExecutionCommand command(String actor,String approval,String no,Proof proof){return operator.command(actor,approval,no,proof);}
    public Probe probe(List<StoreAuthority> scopes){return target.probe(scopes);}
    public Manifest prepare(Preparation input,String key){return target.prepareTrusted(input,key);}
}
