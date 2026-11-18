package ai.neargo.shop.paybridge;

import ai.neargo.shop.common.*;
import ai.neargo.shop.merchant.brand.ManagementModels.Actor;
import ai.neargo.shop.payclient.StoredValueMigrationAppService;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.storedvalue.StoredValueMigrationPlans.*;
import ai.neargo.shop.spi.storedvalue.StoredValueMigrationReadiness.Proof;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Internal owner-authorized maintenance, no public request can supply authority facts or deployment proof. */
@Service
public class StoredValueMigrationMaintenanceService {
    private final StoredValueMigrationCoordinator core;
    private final StoredValueMigrationReadinessService readiness;
    private final StoredValueMigrationAppService pay;
    private final boolean enabled;
    public StoredValueMigrationMaintenanceService(StoredValueMigrationCoordinator core,StoredValueMigrationReadinessService readiness,StoredValueMigrationAppService pay,
            @Value("${shop.pay.stored-value.migration.execution-enabled:false}") boolean enabled){this.core=core;this.readiness=readiness;this.pay=pay;this.enabled=enabled;}
    public MaintenanceResult pause(Actor actor,List<String> stores,String manifest,String key){
        requireEnabled();ContractChecks.id(manifest);ContractChecks.id(key);
        var evidence=core.evidence(actor,stores);var proof=proof();core.assertCurrent(actor,evidence);
        return pay.pause(evidence,manifest,key,proof);
    }
    public MaintenanceResult apply(Actor actor,List<String> stores,String manifest,String pauseReceipt,String key){
        requireEnabled();ContractChecks.id(manifest);ContractChecks.id(pauseReceipt);ContractChecks.id(key);
        var evidence=core.evidence(actor,stores);var proof=proof();core.assertCurrent(actor,evidence);
        return pay.apply(evidence,manifest,pauseReceipt,key,proof);
    }
    private Proof proof(){
        var proof=readiness.proof();
        if(proof==null||proof.report()==null||!proof.report().ready()||proof.report().expiresAt()==null||!proof.report().expiresAt().isAfter(Instant.now()))throw BizException.of(ErrorCode.CONFLICT);
        return proof;
    }
    private void requireEnabled(){if(!enabled)throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);}
}
