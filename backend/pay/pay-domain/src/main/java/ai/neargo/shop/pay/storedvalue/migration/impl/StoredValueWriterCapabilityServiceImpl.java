package ai.neargo.shop.pay.storedvalue.migration.impl;

import ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository;
import ai.neargo.shop.pay.storedvalue.migration.StoredValueWriterCapabilityService;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.storedvalue.StoredValueMigrationReadiness.Capability;
import ai.neargo.shop.spi.storedvalue.StoredValueMigrationReadiness.Query;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;



/** Read-only process facts. A reachable process is not by itself proof that the deployment inventory is complete. */
@Service("storedValueWriterCapabilityService")
public class StoredValueWriterCapabilityServiceImpl implements StoredValueWriterCapabilityService {
    private final IssuerRepository db;
    private final String application,buildSha,processNo=UUID.randomUUID().toString();
    private final boolean writerGuardEnabled,maintenanceEnabled;
    public StoredValueWriterCapabilityServiceImpl(IssuerRepository db,ObjectProvider<BuildProperties> builds,
            @Value("${spring.application.name:unknown}") String application,
            @Value("${shop.pay.stored-value.issuer-mode:false}") boolean writerGuardEnabled,
            @Value("${shop.pay.stored-value.migration-enabled:false}") boolean maintenanceEnabled) {
        this.db=db;this.application=application;this.writerGuardEnabled=writerGuardEnabled;this.maintenanceEnabled=maintenanceEnabled;
        var build=builds.getIfAvailable();String sha=build==null?null:build.get("gitSha");
        this.buildSha=sha!=null&&sha.matches("[a-f0-9]{40}")?sha:"unknown";
    }
    public Capability capability(Query query) {
        ContractChecks.present(query);ContractChecks.id(query.challenge());
        String ledger=null;boolean schema=false;
        if(!"unknown".equals(buildSha)) {
            try {
                var identity=db.one(LEDGER_ID_SQL);var migrations=db.one(SCHEMA_SQL);
                String value=IssuerRepository.text(identity,"ledger_fingerprint");
                if(value!=null&&value.matches("[a-f0-9]{64}"))ledger=value;
                db.rows("SELECT issuer_no,issuer_version_no FROM sv_account WHERE 1=0");
                db.rows("SELECT write_mode FROM sv_issuer WHERE 1=0");
                db.rows("SELECT legacy_activity_version_no FROM sv_release WHERE 1=0");
                db.rows("SELECT legacy_tier_key FROM sv_release_tier WHERE 1=0");
                schema=ledger!=null&&migrations!=null&&IssuerRepository.number(migrations,"applied")==3;
            } catch(DataAccessException unavailable) {
                // Do not return a JDBC URL, raw exception message or server identity in diagnostics.
                ledger=null;schema=false;
            }
        }
        return new Capability(query.challenge(),application,processNo,buildSha,2,writerGuardEnabled,maintenanceEnabled,ledger,schema,Instant.now());
    }
}
