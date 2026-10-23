package ai.neargo.shop.pay.storedvalue.migration;

import static ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository.*;
import ai.neargo.shop.common.*;
import ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.storedvalue.StoredValueMigrationPlans.*;
import ai.neargo.shop.spi.storedvalue.StoredValueMigrationReadiness;
import ai.neargo.shop.spi.storedvalue.StoredValueMigrationReadiness.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Token-transported Core evidence plus fresh deployment proof. Pause and mapping commit separately; no activation/DDL. */
@Service
public class StoredValueMigrationExecutionService {
    private final IssuerRepository db;
    private final StoredValueMigrationManifestService manifests;
    private final StoredValueWriterCapabilityService writer;
    private final boolean enabled;
    private final Clock clock;
    @Autowired
    public StoredValueMigrationExecutionService(IssuerRepository db,StoredValueMigrationManifestService manifests,StoredValueWriterCapabilityService writer,
            @Value("${shop.pay.stored-value.migration.execution-enabled:false}") boolean enabled){this(db,manifests,writer,enabled,Clock.systemUTC());}
    StoredValueMigrationExecutionService(IssuerRepository db,StoredValueMigrationManifestService manifests,StoredValueWriterCapabilityService writer,boolean enabled,Clock clock){this.db=db;this.manifests=manifests;this.writer=writer;this.enabled=enabled;this.clock=clock;}
    public MaintenanceResult pause(Preparation input,String manifestNo,String key,Proof proof) {
        requireEnabled();var evidence=manifests.trusted(input);ContractChecks.id(key);
        String inventory=guard(proof,true);manifests.find(evidence,manifestNo);
        String payload=payload(manifestNo,evidence,inventory),receipt=BizKey.next(BizKey.STORED_VALUE_MIGRATION);
        return manifests.command(evidence,"SV_MAINTENANCE_PAUSE",key,payload,receipt,MaintenanceResult.class,()->{
            manifests.fence(evidence,manifestNo,inner("PAUSE",key,inventory,manifestNo));
            guard(proof,false);
            return new MaintenanceResult(receipt,manifestNo,"WRITE_FENCED",inventory,evidence.batch().stores().size(),0);
        });
    }
    public MaintenanceResult apply(Preparation input,String manifestNo,String pauseReceiptNo,String key,Proof proof) {
        requireEnabled();var evidence=manifests.trusted(input);ContractChecks.id(key);ContractChecks.id(pauseReceiptNo);
        String inventory=guard(proof,true);manifests.find(evidence,manifestNo);
        var pause=db.one("SELECT * FROM pay_ent_command_dedup WHERE tenant_no=? AND operation_kind='SV_MAINTENANCE_PAUSE' AND resource_no=? AND result_status='COMPLETED' AND deleted=0",evidence.batch().tenantNo(),pauseReceiptNo);
        if(pause==null||!payload(manifestNo,evidence,inventory).equals(text(pause,"payload_hash")))conflict();
        var prior=db.decode(text(pause,"response_snapshot"),MaintenanceResult.class);
        if(!pauseReceiptNo.equals(prior.receiptNo())||!manifestNo.equals(prior.manifestNo())||!inventory.equals(prior.inventoryHash())||!"WRITE_FENCED".equals(prior.state()))conflict();
        String payload=hash(db.encode(List.of(manifestNo,pauseReceiptNo,evidence,inventory))),receipt=BizKey.next(BizKey.STORED_VALUE_MIGRATION);
        return manifests.command(evidence,"SV_MAINTENANCE_APPLY",key,payload,receipt,MaintenanceResult.class,()->{
            var mapped=manifests.apply(evidence,manifestNo,inner("APPLY",key,inventory,manifestNo));
            guard(proof,false); // Includes lock waiting and mapping time; expiry rolls mapping back, preserving the prior pause.
            return new MaintenanceResult(receipt,manifestNo,"METADATA_MAPPED",inventory,evidence.batch().stores().size(),mapped.mapping().accountCount());
        });
    }
    private String guard(Proof proof,boolean verifyLocal) {
        if(proof==null||proof.inventory()==null||proof.report()==null)conflict();
        var report=proof.report();var now=clock.instant();
        if(!report.ready()||!report.issues().isEmpty()||report.nodes().isEmpty()||report.checkedAt()==null||report.expiresAt()==null
                ||report.checkedAt().isAfter(now.plusSeconds(5))||!report.expiresAt().isAfter(now))conflict();
        var first=report.nodes().getFirst().capability();if(first==null)conflict();String nonce=first.challenge();
        var checked=StoredValueMigrationReadiness.check(proof.inventory(),report.nodes(),nonce,now);
        if(!checked.ready()||!checked.inventoryHash().equals(report.inventoryHash())||report.expiresAt().isAfter(checked.expiresAt()))conflict();
        if(verifyLocal) {
            var local=writer.capability(new Query(nonce));var observations=new ArrayList<NodeResult>();int found=0;
            for(var node:report.nodes()) {
                if(node.capability()!=null&&local.processNo().equals(node.capability().processNo())){found++;observations.add(new NodeResult(node.nodeId(),local,null));}
                else observations.add(node);
            }
            if(found!=1||!StoredValueMigrationReadiness.check(proof.inventory(),observations,nonce,clock.instant()).ready())conflict();
        }
        return checked.inventoryHash();
    }
    private String payload(String manifest,StoredValueMigrationManifestService.Evidence evidence,String inventory){return hash(db.encode(List.of(manifest,evidence,inventory)));}
    private static String inner(String op,String key,String inventory,String manifest){return "ME"+hash(op+":"+key.length()+":"+key+":"+inventory+":"+manifest).substring(0,62);}
    private void requireEnabled(){if(!enabled)throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);}
    private static void conflict(){throw BizException.of(ErrorCode.CONFLICT);}
    private static String hash(String s){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
}
