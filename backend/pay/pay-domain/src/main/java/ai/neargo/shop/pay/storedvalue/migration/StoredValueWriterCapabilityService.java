package ai.neargo.shop.pay.storedvalue.migration;

import ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.storedvalue.StoredValueMigrationReadiness.*;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/** Read-only process facts. A reachable process is not by itself proof that the deployment inventory is complete. */
@Service
public class StoredValueWriterCapabilityService {
    static final String LEDGER_ID_SQL="SELECT SHA2(CONCAT(@@server_uuid,':',DATABASE()),256) AS ledger_fingerprint";
    static final String SCHEMA_SQL="SELECT COUNT(*) AS applied FROM flyway_schema_history WHERE version IN ('548','549') AND success=1";
    private final IssuerRepository db;
    private final String application,buildSha,processNo=UUID.randomUUID().toString();
    private final boolean writerGuardEnabled,maintenanceEnabled;
    public StoredValueWriterCapabilityService(IssuerRepository db,ObjectProvider<BuildProperties> builds,
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
                schema=ledger!=null&&migrations!=null&&IssuerRepository.number(migrations,"applied")==2;
            } catch(DataAccessException unavailable) {
                // Do not return a JDBC URL, raw exception message or server identity in diagnostics.
                ledger=null;schema=false;
            }
        }
        return new Capability(query.challenge(),application,processNo,buildSha,1,writerGuardEnabled,maintenanceEnabled,ledger,schema,Instant.now());
    }
}
