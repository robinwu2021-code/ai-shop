package ai.neargo.shop.pay.storedvalue.migration;

import static ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository.*;
import ai.neargo.shop.common.*;
import ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.StoreAuthority;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Internal maintenance workflow; no public request may bind authority or identity evidence directly. */
@Service
public class StoredValueMigrationManifestService {
    public record Evidence(StoredValueIssuerMigrationService.Batch batch,List<StoreAuthority> authorities) {
        public Evidence { authorities=List.copyOf(authorities); }
        Evidence canonical() {
            if(batch==null||!"MAIN".equals(batch.tenantNo())||batch.stores().isEmpty()||batch.stores().size()>100
                    ||authorities.size()!=batch.stores().size())throw BizException.of(ErrorCode.BAD_REQUEST);
            ContractChecks.id(batch.actorNo());
            var seen=new HashSet<String>();
            for(var store:batch.stores()) {
                ContractChecks.id(store.storeNo());ContractChecks.id(store.entityNo());ContractChecks.id(store.issuerNo());
                if(!seen.add(store.storeNo()))throw BizException.of(ErrorCode.BAD_REQUEST);
                var scope=authorities.stream().filter(a->store.storeNo().equals(a.storeNo())).toList();
                if(scope.size()!=1||!batch.tenantNo().equals(scope.getFirst().tenantNo())||!batch.actorNo().equals(scope.getFirst().actorNo())
                        ||!store.entityNo().equals(scope.getFirst().entityNo()))throw BizException.of(ErrorCode.FORBIDDEN);
            }
            var customers=new HashSet<String>();
            for(var person:batch.identities()) {
                ContractChecks.id(person.customerNo());ContractChecks.optionalId(person.userNo());
                if(!customers.add(person.customerNo()))throw BizException.of(ErrorCode.BAD_REQUEST);
            }
            var ordered=new StoredValueIssuerMigrationService.Batch(batch.tenantNo(),batch.actorNo(),
                    batch.stores().stream().sorted(Comparator.comparing(StoredValueIssuerMigrationService.StoreMapping::issuerNo)).toList(),
                    batch.identities().stream().sorted(Comparator.comparing(StoredValueIssuerMigrationService.IdentityEvidence::customerNo)).toList());
            return new Evidence(ordered,authorities.stream().sorted(Comparator.comparing(StoreAuthority::storeNo)).toList());
        }
        void verifyPayScope(IssuerRepository db,boolean lock) {
            for(var store:batch.stores()) {
                var scope=authorities.stream().filter(a->store.storeNo().equals(a.storeNo())).findFirst().orElseThrow();
                var access=db.one("SELECT * FROM sv_issuer_store_access WHERE tenant_no=? AND issuer_no=? AND store_no=? AND deleted=0"+(lock?" FOR UPDATE":""),batch.tenantNo(),store.issuerNo(),store.storeNo());
                if(access==null||!scope.entityNo().equals(text(access,"operating_entity_no"))||!scope.storeNo().equals(text(access,"source_binding_no"))
                        ||scope.storeRevision()!=number(access,"source_revision")||!Set.of("PREPARING","ACTIVE").contains(text(access,"status")))
                    throw BizException.of(ErrorCode.CONFLICT);
            }
        }
    }
    public record Manifest(String manifestNo,String fingerprint,String authorityHash,long accountCount) { }
    public record SavedManifest(Manifest manifest,Evidence evidence,StoredValueIssuerMigrationService.Report report) { }
    public record Applied(String manifestNo,String state,StoredValueIssuerMigrationService.MappingResult mapping) { }
    private final IssuerRepository db;
    private final StoredValueIssuerMigrationService migration;
    private final boolean maintenanceEnabled,writersEnabled;
    public StoredValueMigrationManifestService(IssuerRepository db,StoredValueIssuerMigrationService migration,
            @Value("${shop.pay.stored-value.migration-enabled:false}") boolean maintenanceEnabled,
            @Value("${shop.pay.stored-value.issuer-mode:false}") boolean writersEnabled) {
        this.db=db;this.migration=migration;this.maintenanceEnabled=maintenanceEnabled;this.writersEnabled=writersEnabled;
    }
    /** Persist the complete successful audit, including original-to-issuer mappings; no balances are mutated. */
    public Manifest prepare(Evidence input,String key) {
        requireMaintenance();var evidence=input.canonical();ContractChecks.id(key);
        String authorityHash=hash(db.encode(evidence));String no=BizKey.next(BizKey.STORED_VALUE_MIGRATION);
        return command(evidence,"SV_MIGRATION_MANIFEST",key,authorityHash,no,SavedManifest.class,()->{
            evidence.verifyPayScope(db,false);
            var report=migration.audit(evidence.batch());
            if(!report.ready())throw BizException.of(ErrorCode.CONFLICT);
            return new SavedManifest(new Manifest(no,report.fingerprint(),authorityHash,report.accounts().size()),evidence,report);
        }).manifest();
    }
    public Manifest find(Evidence input,String manifestNo) { return saved(input.canonical(),manifestNo).manifest(); }
    /** Only server-stored immutable audit facts may authorize metadata mapping. Activation/DDL are separate. */
    public Applied apply(Evidence input,String manifestNo,String key) {
        requireMaintenance();
        if(!writersEnabled)throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        var evidence=input.canonical();ContractChecks.id(key);
        var original=saved(evidence,manifestNo);
        String payload=hash(db.encode(List.of(manifestNo,evidence)));
        return command(evidence,"SV_MIGRATION_APPLY",key,payload,manifestNo,Applied.class,()->{
            var result=migration.mapFenced(evidence,original.manifest().fingerprint(),key);
            return new Applied(manifestNo,"METADATA_MAPPED",result);
        });
    }
    private SavedManifest saved(Evidence evidence,String no) {
        ContractChecks.id(no);
        var row=db.one("SELECT * FROM pay_ent_command_dedup WHERE tenant_no=? AND operation_kind='SV_MIGRATION_MANIFEST' AND resource_no=? AND result_status='COMPLETED' AND deleted=0",evidence.batch().tenantNo(),no);
        if(row==null)throw BizException.of(ErrorCode.NOT_FOUND);
        var saved=db.decode(text(row,"response_snapshot"),SavedManifest.class);
        String authorityHash=hash(db.encode(evidence));
        if(!no.equals(saved.manifest().manifestNo())||!text(row,"payload_hash").equals(saved.manifest().authorityHash())
                ||!saved.report().ready()||!saved.manifest().fingerprint().equals(saved.report().fingerprint())
                ||saved.manifest().accountCount()!=saved.report().accounts().size()
                ||!saved.manifest().authorityHash().equals(hash(db.encode(saved.evidence()))))throw BizException.of(ErrorCode.CONFLICT);
        if(!saved.manifest().authorityHash().equals(authorityHash))throw BizException.of(ErrorCode.FORBIDDEN);
        return saved;
    }
    private <T>T command(Evidence evidence,String operation,String key,String payload,String resource,Class<T> type,Supplier<T> work) {
        return db.write(()->{
            var batch=evidence.batch();
            var row=db.one("SELECT * FROM pay_ent_command_dedup WHERE tenant_no=? AND operation_kind=? AND request_key=? AND deleted=0 FOR UPDATE",batch.tenantNo(),operation,key);
            if(row!=null) {
                if(!payload.equals(text(row,"payload_hash"))||!"COMPLETED".equals(text(row,"result_status")))throw BizException.of(ErrorCode.CONFLICT);
                return db.decode(text(row,"response_snapshot"),type);
            }
            var at=Timestamp.from(Instant.now());
            db.update("INSERT INTO pay_ent_command_dedup(tenant_no,operation_kind,request_key,payload_hash,resource_no,result_status,version,deleted,created_at,updated_at,created_by,updated_by) VALUES(?,?,?,?,?,'PENDING',0,0,?,?,?,?)",
                    batch.tenantNo(),operation,key,payload,resource,at,at,batch.actorNo(),batch.actorNo());
            T result=work.get();
            int changed=db.update("UPDATE pay_ent_command_dedup SET result_status='COMPLETED',response_snapshot=?,updated_at=?,updated_by=?,version=version+1 WHERE tenant_no=? AND operation_kind=? AND request_key=? AND result_status='PENDING'",db.encode(result),at,batch.actorNo(),batch.tenantNo(),operation,key);
            if(changed!=1)throw BizException.of(ErrorCode.CONFLICT);
            return result;
        });
    }
    private void requireMaintenance(){if(!maintenanceEnabled)throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);}
    private static String hash(String value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
