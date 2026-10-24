package ai.neargo.shop.pay.storedvalue.migration;
import ai.neargo.shop.pay.storedvalue.migration.impl.StoredValueMigrationManifestServiceImpl;


import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.StoreAuthority;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class StoredValueMigrationManifestTest {
    record Fixture(IssuerAccountMigrationTest.Fixture data,StoredValueMigrationManifestService service,
            StoredValueMigrationManifestService.Evidence evidence) { }
    Fixture fixture(boolean maintenance,boolean writers) throws Exception {
        var old=new IssuerAccountMigrationTest();var f=old.fixture();old.zeroBalanceHistory(f.jdbc());old.migrationSchema(f.jdbc());
        f.jdbc().execute("CREATE TABLE sv_issuer_store_access(tenant_no varchar,issuer_no varchar,store_no varchar,operating_entity_no varchar,source_binding_no varchar,source_revision bigint,status varchar,can_issue int default 0,can_redeem int default 0,deleted int default 0)");
        f.jdbc().update("INSERT INTO sv_issuer_store_access(tenant_no,issuer_no,store_no,operating_entity_no,source_binding_no,source_revision,status,deleted) VALUES('MAIN','I1','S1','E1','S1',7,'PREPARING',0)");
        var evidence=new StoredValueMigrationManifestService.Evidence(old.batch(),List.of(new StoreAuthority("MAIN","AUDITOR","E1","S1","本店",7)));
        return new Fixture(f,new StoredValueMigrationManifestServiceImpl(f.db(),f.service(),maintenance,writers),evidence);
    }
    @Test void manifestIsDurableAndReplayNeverReauditsDifferentFinancialFacts() throws Exception {
        var f=fixture(true,true);var first=f.service().prepare(f.evidence(),"PREPARE1");
        f.data().jdbc().update("UPDATE sv_account SET version=version+1");
        var restarted=new StoredValueMigrationManifestServiceImpl(f.data().db(),f.data().service(),true,true);
        assertThat(restarted.prepare(f.evidence(),"PREPARE1")).isEqualTo(first);
        assertThat(f.service().find(f.evidence(),first.manifestNo())).isEqualTo(first);
        f.data().jdbc().update("UPDATE sv_issuer SET write_mode='FENCED'");
        assertThatThrownBy(()->f.service().apply(f.evidence(),first.manifestNo(),"APPLY1")).isInstanceOf(BizException.class);
        assertThat(f.data().jdbc().queryForObject("SELECT issuer_no FROM sv_account",String.class)).isNull();
        assertThat(f.data().jdbc().queryForObject("SELECT COUNT(*) FROM pay_ent_command_dedup WHERE operation_kind='SV_MIGRATION_APPLY'",Long.class)).isZero();
    }
    @Test void applyingPreparedManifestRequiresFenceAndPreservesItOnSuccessAndReplay() throws Exception {
        var f=fixture(true,true);var manifest=f.service().prepare(f.evidence(),"PREPARE1");
        assertThatThrownBy(()->f.service().apply(f.evidence(),manifest.manifestNo(),"APPLY1")).isInstanceOf(BizException.class);
        f.data().jdbc().update("UPDATE sv_issuer SET write_mode='FENCED'");
        var result=f.service().apply(f.evidence(),manifest.manifestNo(),"APPLY1");
        assertThat(f.service().apply(f.evidence(),manifest.manifestNo(),"APPLY1")).isEqualTo(result);
        assertThat(f.data().jdbc().queryForObject("SELECT write_mode FROM sv_issuer",String.class)).isEqualTo("FENCED");
        assertThat(f.data().jdbc().queryForObject("SELECT status FROM sv_issuer",String.class)).isEqualTo("DRAFT");
        assertThat(f.data().jdbc().queryForObject("SELECT account_no FROM sv_account",String.class)).isEqualTo("A1");
        assertThat(f.data().jdbc().queryForObject("SELECT account_seq FROM sv_account",Long.class)).isEqualTo(2);
        assertThat(f.data().jdbc().queryForObject("SELECT COUNT(*) FROM sv_voucher",Long.class)).isEqualTo(2);
        assertThat(f.data().jdbc().queryForObject("SELECT principal_minor+bonus_minor FROM sv_account",Long.class)).isZero();
    }
    @Test void missingSwitchesOrChangedTrustedAuthorityCannotPrepareOrExecute() throws Exception {
        var closed=fixture(false,false);
        assertThatThrownBy(()->closed.service().prepare(closed.evidence(),"P1")).isInstanceOf(BizException.class);
        assertThat(closed.data().jdbc().queryForObject("SELECT COUNT(*) FROM pay_ent_command_dedup",Long.class)).isZero();
        var f=fixture(true,true);var manifest=f.service().prepare(f.evidence(),"PREPARE1");
        var old=f.evidence().batch();
        var wrong=new StoredValueMigrationManifestService.Evidence(old,List.of(new StoreAuthority("MAIN","AUDITOR","E2","S1","本店",7)));
        assertThatThrownBy(()->f.service().apply(wrong,manifest.manifestNo(),"A1")).isInstanceOf(BizException.class);
        f.data().jdbc().update("UPDATE sv_issuer SET write_mode='FENCED'");
        f.data().jdbc().update("UPDATE sv_issuer_store_access SET source_revision=8");
        assertThatThrownBy(()->f.service().apply(f.evidence(),manifest.manifestNo(),"A1")).isInstanceOf(BizException.class);
        assertThat(f.data().jdbc().queryForObject("SELECT issuer_no FROM sv_account",String.class)).isNull();
    }
    @Test void preparationCanPrecedeWriterSwitchWhileApplyCannot() throws Exception {
        var closed=fixture(false,true);
        assertThatThrownBy(()->closed.service().prepare(closed.evidence(),"P1")).isInstanceOf(BizException.class);
        assertThat(closed.data().jdbc().queryForObject("SELECT COUNT(*) FROM pay_ent_command_dedup",Long.class)).isZero();
        var f=fixture(true,false);var manifest=f.service().prepare(f.evidence(),"P1");
        f.data().jdbc().update("UPDATE sv_issuer SET write_mode='FENCED'");
        assertThatThrownBy(()->f.service().apply(f.evidence(),manifest.manifestNo(),"A1")).isInstanceOf(BizException.class);
        assertThat(f.data().jdbc().queryForObject("SELECT issuer_no FROM sv_account",String.class)).isNull();
        assertThat(f.data().jdbc().queryForObject("SELECT COUNT(*) FROM pay_ent_command_dedup WHERE operation_kind='SV_MIGRATION_APPLY'",Long.class)).isZero();
    }
    @Test void oldMappingReplyCannotBypassFinalFence() throws Exception {
        var f=fixture(true,true);var manifest=f.service().prepare(f.evidence(),"P1");
        f.data().service().map(f.evidence().batch(),manifest.fingerprint(),"A1");
        assertThatThrownBy(()->f.service().apply(f.evidence(),manifest.manifestNo(),"A1")).isInstanceOf(BizException.class);
        assertThat(f.data().jdbc().queryForObject("SELECT COUNT(*) FROM pay_ent_command_dedup WHERE operation_kind='SV_MIGRATION_APPLY'",Long.class)).isZero();
    }
    @Test void manifestIdentityMustMatchTheStoredCommandResource() throws Exception {
        var f=fixture(true,true);f.service().prepare(f.evidence(),"P1");
        f.data().jdbc().update("UPDATE pay_ent_command_dedup SET resource_no='OTHER' WHERE operation_kind='SV_MIGRATION_MANIFEST'");
        assertThatThrownBy(()->f.service().find(f.evidence(),"OTHER")).isInstanceOf(BizException.class);
    }

    @Test void unregisteredPersonKeepsOriginalAccountWithoutInventingUserBinding() throws Exception {
        var f=fixture(true,true);f.data().jdbc().update("UPDATE sv_account SET user_no=NULL");
        f.data().jdbc().update("DELETE FROM sv_customer_identity");
        var old=f.evidence().batch();
        var batch=new StoredValueIssuerMigrationService.Batch(old.tenantNo(),old.actorNo(),old.stores(),List.of(new StoredValueIssuerMigrationService.IdentityEvidence("P1",null)));
        var evidence=new StoredValueMigrationManifestService.Evidence(batch,f.evidence().authorities());
        var manifest=f.service().prepare(evidence,"P1");
        f.data().jdbc().update("UPDATE sv_issuer SET write_mode='FENCED'");
        f.service().apply(evidence,manifest.manifestNo(),"A1");
        assertThat(f.data().jdbc().queryForObject("SELECT account_no FROM sv_account",String.class)).isEqualTo("A1");
        assertThat(f.data().jdbc().queryForObject("SELECT customer_no FROM sv_account",String.class)).isEqualTo("P1");
        assertThat(f.data().jdbc().queryForObject("SELECT user_no FROM sv_account",String.class)).isNull();
        assertThat(f.data().jdbc().queryForObject("SELECT COUNT(*) FROM sv_customer_identity",Long.class)).isZero();
    }

    @Test void trustedProbeScopesSubjectsAndPreparesTheSameDurableManifest() throws Exception {
        var f=fixture(true,false);
        f.data().jdbc().update("INSERT INTO sv_account(tenant_no,account_no,entity_no,store_no,deleted) VALUES('MAIN','OTHER','E1','S2',0)");
        var probe=f.service().probe(f.evidence().authorities());
        assertThat(probe.subjects()).extracting(s->s.accountNo()).containsExactly("A1");
        assertThat(probe.scopes().getFirst().issuerNo()).isEqualTo("I1");
        var input=new ai.neargo.shop.spi.storedvalue.StoredValueMigrationPlans.Preparation(probe.scopes(),
                List.of(new ai.neargo.shop.spi.storedvalue.StoredValueMigrationPlans.Identity("P1","U1")));
        var manifest=f.service().prepareTrusted(input,"CORE1");
        assertThat(manifest.manifestNo()).isEqualTo(f.service().prepare(f.evidence(),"CORE1").manifestNo());
        assertThat(f.data().jdbc().queryForObject("SELECT issuer_no FROM sv_account WHERE account_no='A1'",String.class)).isNull();
    }
    @Test void changedStoreAuthorityOrMissingProvenanceCannotProduceTrustedSubjects() throws Exception {
        var f=fixture(true,false);
        var changed=List.of(new StoreAuthority("MAIN","AUDITOR","E1","S1","本店",8));
        assertThatThrownBy(()->f.service().probe(changed)).isInstanceOf(BizException.class);
        f.data().jdbc().update("UPDATE sv_account SET store_no=NULL");
        assertThatThrownBy(()->f.service().probe(f.evidence().authorities())).isInstanceOf(BizException.class);
        assertThatThrownBy(()->fixture(false,false).service().probe(f.evidence().authorities())).isInstanceOf(BizException.class);
    }

    @Test void fenceCommandDrainsIssuerGuardAndReplaysWithoutUnfencingOrRepeatingAudit() throws Exception {
        var f=fixture(true,true);var manifest=f.service().prepare(f.evidence(),"P1");
        var fenced=f.service().fence(f.evidence(),manifest.manifestNo(),"F1");
        assertThat(fenced.state()).isEqualTo("WRITE_FENCED");
        assertThat(f.data().jdbc().queryForObject("SELECT write_mode FROM sv_issuer",String.class)).isEqualTo("FENCED");
        assertThat(f.data().jdbc().queryForObject("SELECT issuer_no FROM sv_account",String.class)).isNull();
        assertThat(f.data().jdbc().queryForObject("SELECT status FROM sv_issuer",String.class)).isEqualTo("DRAFT");
        f.service().apply(f.evidence(),manifest.manifestNo(),"A1");
        var restarted=new StoredValueMigrationManifestServiceImpl(f.data().db(),f.data().service(),true,true);
        assertThat(restarted.fence(f.evidence(),manifest.manifestNo(),"F1")).isEqualTo(fenced);
        assertThat(f.data().jdbc().queryForObject("SELECT write_mode FROM sv_issuer",String.class)).isEqualTo("FENCED");
        assertThat(f.data().jdbc().queryForObject("SELECT COUNT(*) FROM sv_voucher",Long.class)).isEqualTo(2);
    }
    @Test void staleManifestCannotPauseWritingAndLeavesNoFenceReceipt() throws Exception {
        var f=fixture(true,true);var manifest=f.service().prepare(f.evidence(),"P1");
        f.data().jdbc().update("UPDATE sv_account SET version=version+1");
        assertThatThrownBy(()->f.service().fence(f.evidence(),manifest.manifestNo(),"F1")).isInstanceOf(BizException.class);
        assertThat(f.data().jdbc().queryForObject("SELECT write_mode FROM sv_issuer",String.class)).isEqualTo("LEGACY");
        assertThat(f.data().jdbc().queryForObject("SELECT COUNT(*) FROM pay_ent_command_dedup WHERE operation_kind='SV_MIGRATION_FENCE'",Long.class)).isZero();
    }
    @Test void fenceCannotDowngradeIssuerModeOrRunWithoutUpgradedLocalWriters() throws Exception {
        var f=fixture(true,true);var manifest=f.service().prepare(f.evidence(),"P1");
        var old=new StoredValueMigrationManifestServiceImpl(f.data().db(),f.data().service(),true,false);
        assertThatThrownBy(()->old.fence(f.evidence(),manifest.manifestNo(),"F1")).isInstanceOf(BizException.class);
        f.data().jdbc().update("UPDATE sv_issuer SET write_mode='ISSUER'");
        assertThatThrownBy(()->f.service().fence(f.evidence(),manifest.manifestNo(),"F1")).isInstanceOf(BizException.class);
        assertThat(f.data().jdbc().queryForObject("SELECT write_mode FROM sv_issuer",String.class)).isEqualTo("ISSUER");
        assertThat(f.data().jdbc().queryForObject("SELECT COUNT(*) FROM pay_ent_command_dedup WHERE operation_kind='SV_MIGRATION_FENCE'",Long.class)).isZero();
    }

}
