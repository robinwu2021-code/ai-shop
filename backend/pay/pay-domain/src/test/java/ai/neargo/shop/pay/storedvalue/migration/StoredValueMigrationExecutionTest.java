package ai.neargo.shop.pay.storedvalue.migration;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.storedvalue.StoredValueMigrationPlans.*;
import ai.neargo.shop.spi.storedvalue.StoredValueMigrationReadiness;
import ai.neargo.shop.spi.storedvalue.StoredValueMigrationReadiness.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StoredValueMigrationExecutionTest {
    final String sha="a".repeat(40),ledger="b".repeat(64);
    final StoredValueWriterCapabilityService writer=mock(StoredValueWriterCapabilityService.class);
    Preparation input(StoredValueMigrationManifestTest.Fixture f){return new Preparation(List.of(new Scope(f.evidence().authorities().getFirst(),"I1")),List.of(new Identity("P1","U1")));}
    Capability cap(String nonce){return new Capability(nonce,"pay-svc","boot-pay",sha,2,true,true,ledger,true,Instant.now());}
    Proof proof(String revision){
        String nonce=UUID.randomUUID().toString();var inventory=new Inventory(revision,ledger,true,List.of(new Node("pay-1","PAY_1","pay-svc",sha,"boot-pay")));
        return new Proof(inventory,StoredValueMigrationReadiness.check(inventory,List.of(new NodeResult("pay-1",cap(nonce),null)),nonce,Instant.now()));
    }
    StoredValueMigrationExecutionService service(StoredValueMigrationManifestTest.Fixture f,boolean enabled){
        doAnswer(i->cap(((Query)i.getArgument(0)).challenge())).when(writer).capability(any());
        return new StoredValueMigrationExecutionService(f.data().db(),f.service(),writer,enabled);
    }
    @Test void freshReadinessPauseAndLinkedApplyAreDurableAndNeverActivateWriting() throws Exception {
        var f=new StoredValueMigrationManifestTest().fixture(true,true);var manifest=f.service().prepare(f.evidence(),"P1");var s=service(f,true);
        var paused=s.pause(input(f),manifest.manifestNo(),"PAUSE1",proof("release1"));
        assertThat(paused.state()).isEqualTo("WRITE_FENCED");
        assertThat(s.pause(input(f),manifest.manifestNo(),"PAUSE1",proof("release1"))).isEqualTo(paused);
        var applied=s.apply(input(f),manifest.manifestNo(),paused.receiptNo(),"APPLY1",proof("release1"));
        assertThat(applied.state()).isEqualTo("METADATA_MAPPED");
        assertThat(s.apply(input(f),manifest.manifestNo(),paused.receiptNo(),"APPLY1",proof("release1"))).isEqualTo(applied);
        assertThat(f.data().jdbc().queryForObject("SELECT write_mode FROM sv_issuer",String.class)).isEqualTo("FENCED");
        assertThat(f.data().jdbc().queryForObject("SELECT status FROM sv_issuer",String.class)).isEqualTo("DRAFT");
        assertThat(f.data().jdbc().queryForObject("SELECT account_no FROM sv_account",String.class)).isEqualTo("A1");
    }
    @Test void closedExecutionForgedReadyAndWrongLocalLedgerCannotPauseAnything() throws Exception {
        var f=new StoredValueMigrationManifestTest().fixture(true,true);var manifest=f.service().prepare(f.evidence(),"P1");var s=service(f,false);
        assertThatThrownBy(()->s.pause(input(f),manifest.manifestNo(),"X",proof("release1"))).isInstanceOf(BizException.class);
        var open=service(f,true);var valid=proof("release1");
        var unsealed=new Proof(new Inventory("release1",ledger,false,valid.inventory().nodes()),valid.report());
        assertThatThrownBy(()->open.pause(input(f),manifest.manifestNo(),"X",unsealed)).isInstanceOf(BizException.class);
        doAnswer(i->new Capability(((Query)i.getArgument(0)).challenge(),"pay-svc","boot-pay",sha,2,true,true,"c".repeat(64),true,Instant.now())).when(writer).capability(any());
        assertThatThrownBy(()->open.pause(input(f),manifest.manifestNo(),"X",valid)).isInstanceOf(BizException.class);
        assertThat(f.data().jdbc().queryForObject("SELECT write_mode FROM sv_issuer",String.class)).isEqualTo("LEGACY");
        assertThat(f.data().jdbc().queryForObject("SELECT COUNT(*) FROM pay_ent_command_dedup WHERE operation_kind='SV_MAINTENANCE_PAUSE'",Long.class)).isZero();
    }
    @Test void differentInventoryOrFailedMappingCannotReusePauseOrReleaseTheFence() throws Exception {
        var f=new StoredValueMigrationManifestTest().fixture(true,true);var manifest=f.service().prepare(f.evidence(),"P1");var s=service(f,true);
        var paused=s.pause(input(f),manifest.manifestNo(),"PAUSE1",proof("release1"));
        assertThatThrownBy(()->s.apply(input(f),manifest.manifestNo(),paused.receiptNo(),"A1",proof("release2"))).isInstanceOf(BizException.class);
        f.data().jdbc().update("UPDATE sv_issuer_store_access SET source_revision=8");
        assertThatThrownBy(()->s.apply(input(f),manifest.manifestNo(),paused.receiptNo(),"A1",proof("release1"))).isInstanceOf(BizException.class);
        assertThat(f.data().jdbc().queryForObject("SELECT write_mode FROM sv_issuer",String.class)).isEqualTo("FENCED");
        assertThat(f.data().jdbc().queryForObject("SELECT issuer_no FROM sv_account",String.class)).isNull();
        assertThat(f.data().jdbc().queryForObject("SELECT COUNT(*) FROM pay_ent_command_dedup WHERE operation_kind='SV_MAINTENANCE_APPLY'",Long.class)).isZero();
    }
    @Test void expiredObservationsAndUnlinkedFoundationFenceCannotAuthorizeMapping() throws Exception {
        var f=new StoredValueMigrationManifestTest().fixture(true,true);var manifest=f.service().prepare(f.evidence(),"P1");var s=service(f,true);var valid=proof("release1");
        var old=Instant.now().minusSeconds(60);var cap=new Capability("old","pay-svc","boot-pay",sha,2,true,true,ledger,true,old);
        var forged=new Proof(valid.inventory(),new Report(true,valid.report().inventoryHash(),old,Instant.now().plusSeconds(30),List.of(),List.of(new NodeResult("pay-1",cap,null))));
        assertThatThrownBy(()->s.pause(input(f),manifest.manifestNo(),"X",forged)).isInstanceOf(BizException.class);
        f.service().fence(f.evidence(),manifest.manifestNo(),"RAW");
        assertThatThrownBy(()->s.apply(input(f),manifest.manifestNo(),manifest.manifestNo(),"A1",valid)).isInstanceOf(BizException.class);
        assertThat(f.data().jdbc().queryForObject("SELECT write_mode FROM sv_issuer",String.class)).isEqualTo("FENCED");
        assertThat(f.data().jdbc().queryForObject("SELECT issuer_no FROM sv_account",String.class)).isNull();
    }

    @Test void proofExpiringWhileMappingRollsBackMetadataButKeepsTheCommittedPause() throws Exception {
        var f=new StoredValueMigrationManifestTest().fixture(true,true);var manifest=f.service().prepare(f.evidence(),"P1");var normal=service(f,true);
        var pause=normal.pause(input(f),manifest.manifestNo(),"F1",proof("release1"));var fresh=proof("release1");
        var now=new java.util.concurrent.atomic.AtomicReference<>(Instant.now());var clock=mock(java.time.Clock.class);when(clock.instant()).thenAnswer(i->now.get());
        var target=spy(f.service());
        doAnswer(i->{var result=i.callRealMethod();now.set(now.get().plusSeconds(31));return result;}).when(target).apply(any(),eq(manifest.manifestNo()),any());
        var delayed=new StoredValueMigrationExecutionService(f.data().db(),target,writer,true,clock);
        assertThatThrownBy(()->delayed.apply(input(f),manifest.manifestNo(),pause.receiptNo(),"A1",fresh)).isInstanceOf(BizException.class);
        assertThat(f.data().jdbc().queryForObject("SELECT write_mode FROM sv_issuer",String.class)).isEqualTo("FENCED");
        assertThat(f.data().jdbc().queryForObject("SELECT issuer_no FROM sv_account",String.class)).isNull();
        assertThat(f.data().jdbc().queryForObject("SELECT COUNT(*) FROM pay_ent_command_dedup WHERE operation_kind='SV_MAINTENANCE_APPLY'",Long.class)).isZero();
    }

}
