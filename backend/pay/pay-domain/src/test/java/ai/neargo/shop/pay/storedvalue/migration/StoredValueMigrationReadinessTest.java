package ai.neargo.shop.pay.storedvalue.migration;

import ai.neargo.shop.spi.storedvalue.StoredValueMigrationReadiness.*;
import ai.neargo.shop.spi.storedvalue.StoredValueMigrationReadiness;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class StoredValueMigrationReadinessTest {
    final Instant now=Instant.parse("2026-10-23T08:00:00Z");
    final String sha="a".repeat(40),ledger="b".repeat(64),challenge="check-123";
    final Node core=new Node("core-1","CORE_1","shop-app",sha,"boot-core"),pay=new Node("pay-1","PAY_1","pay-svc",sha,"boot-pay");
    Inventory inventory(boolean sealed){return new Inventory("deploy-1",ledger,sealed,List.of(core,pay));}
    Capability cap(String app,String boot){return new Capability(challenge,app,boot,sha,2,true,true,ledger,true,now);}
    List<NodeResult> healthy(){return List.of(new NodeResult("core-1",cap("shop-app","boot-core"),null),new NodeResult("pay-1",cap("pay-svc","boot-pay"),null));}
    @Test void onlyTheSealedExactFreshInventoryCanBeReady(){
        var report=StoredValueMigrationReadiness.check(inventory(true),healthy(),challenge,now);
        assertThat(report.ready()).isTrue();assertThat(report.expiresAt()).isEqualTo(now.plusSeconds(30));
        assertThat(report.inventoryHash()).matches("[a-f0-9]{64}");
        assertThat(StoredValueMigrationReadiness.check(inventory(false),healthy(),challenge,now).ready()).isFalse();
        assertThat(StoredValueMigrationReadiness.check(inventory(true),List.of(healthy().getFirst()),challenge,now).ready()).isFalse();
        assertThat(StoredValueMigrationReadiness.check(inventory(true),healthy(),"different",now).ready()).isFalse();
    }
    @Test void oldBuildWrongLedgerDisabledGuardAndStaleRepliesCannotAuthorizeCutover(){
        record Rejected(Capability capability,String issue) { }
        var bad=List.of(
            new Rejected(new Capability(challenge,"pay-svc","boot-pay","c".repeat(40),2,true,true,ledger,true,now),"BUILD_MISMATCH"),
            new Rejected(new Capability(challenge,"pay-svc","boot-pay",sha,2,true,true,"d".repeat(64),true,now),"LEDGER_NOT_READY"),
            new Rejected(new Capability(challenge,"pay-svc","boot-pay",sha,2,false,true,ledger,true,now),"WRITER_NOT_READY"),
            new Rejected(new Capability(challenge,"pay-svc","boot-pay",sha,2,true,false,ledger,true,now),"WRITER_NOT_READY"),
            new Rejected(new Capability(challenge,"pay-svc","boot-pay",sha,2,true,true,ledger,false,now),"LEDGER_NOT_READY"),
            new Rejected(new Capability(challenge,"pay-svc","boot-pay",sha,2,true,true,ledger,true,now.minusSeconds(31)),"STALE_OBSERVATION"),
            new Rejected(new Capability(challenge,"pay-svc","boot-pay",sha,2,true,true,ledger,true,now.minusSeconds(30)),"STALE_OBSERVATION"),
            new Rejected(new Capability(challenge,"pay-svc","boot-pay",sha,2,true,true,ledger,true,now.plusSeconds(6)),"STALE_OBSERVATION"));
        for(var rejected:bad) {
            var report=StoredValueMigrationReadiness.check(inventory(true),List.of(healthy().getFirst(),new NodeResult("pay-1",rejected.capability(),null)),challenge,now);
            assertThat(report.ready()).as("%s: %s",rejected.issue(),rejected.capability()).isFalse();
            assertThat(report.issues()).extracting(Issue::code).contains(rejected.issue());
        }
        assertThat(StoredValueMigrationReadiness.check(inventory(true),List.of(healthy().getFirst(),new NodeResult("pay-1",null,"PROBE_UNAVAILABLE")),challenge,now).ready()).isFalse();
    }
    @Test void duplicateProcessAliasesAndUnexpectedOrDuplicateNodesAreRejected(){
        var aliases=List.of(healthy().getFirst(),new NodeResult("pay-1",cap("pay-svc","boot-core"),null));
        assertThat(StoredValueMigrationReadiness.check(inventory(true),aliases,challenge,now).ready()).isFalse();
        assertThat(StoredValueMigrationReadiness.check(inventory(true),List.of(healthy().getFirst(),healthy().getFirst(),healthy().getLast()),challenge,now).ready()).isFalse();
        assertThat(StoredValueMigrationReadiness.check(inventory(true),List.of(healthy().getFirst(),healthy().getLast(),new NodeResult("unknown",cap("pay-svc","boot-other"),null)),challenge,now).ready()).isFalse();
    }
    @Test void restartedProcessRequiresANewSealedInventoryEvenWhenBuildIsIdentical(){
        var replacement=new NodeResult("pay-1",cap("pay-svc","new-boot-pay"),null);
        assertThat(StoredValueMigrationReadiness.check(inventory(true),List.of(healthy().getFirst(),replacement),challenge,now).ready()).isFalse();
    }


    @Test void legacyWriterProtocolCannotAuthorizeTheCompletedCutoverContract(){
        var good=healthy();
        assertThat(StoredValueMigrationReadiness.check(inventory(true),good,challenge,now).ready()).isTrue();
        var nodes=new java.util.ArrayList<>(good);var first=nodes.getFirst();var c=first.capability();
        nodes.set(0,new NodeResult(first.nodeId(),new Capability(c.challenge(),c.application(),c.processNo(),c.buildSha(),1,c.writerGuardEnabled(),c.maintenanceEnabled(),c.ledgerFingerprint(),c.schemaReady(),c.checkedAt()),null));
        var report=StoredValueMigrationReadiness.check(inventory(true),nodes,challenge,now);
        assertThat(report.ready()).isFalse();assertThat(report.issues()).extracting(Issue::code).contains("WRITER_NOT_READY");
    }
}
