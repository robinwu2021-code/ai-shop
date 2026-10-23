package ai.neargo.shop.pay.storedvalue.migration;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.storedvalue.StoredValueMigrationReadiness.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import ai.neargo.shop.pay.storedvalue.migration.StoredValueSchemaAuthorization.*;

class StoredValueSchemaAuthorizationTest {
    record Fixture(StoredValueMigrationManifestTest.Fixture f,StoredValueMigrationExecutionService execution,
        Proof proof,Approval approval){}
    Fixture fixture() throws Exception {
        var f=new StoredValueMigrationManifestTest().fixture(true,true);
        var helper=new StoredValueMigrationExecutionTest();var execution=helper.service(f,true);var proof=helper.proof("global1");
        var manifest=f.service().prepare(f.evidence(),"P1");
        var paused=execution.pause(helper.input(f),manifest.manifestNo(),"PA1",proof);
        var applied=execution.apply(helper.input(f),manifest.manifestNo(),paused.receiptNo(),"AP1",proof);
        var approval=new Approval("GLOBAL1","OPERATOR","b".repeat(64),proof.report().inventoryHash(),Instant.now().plusSeconds(600),true,
            List.of(new MappingReceipt("MAIN",manifest.manifestNo(),paused.receiptNo(),applied.receiptNo())));
        return new Fixture(f,execution,proof,approval);
    }
    StoredValueSchemaAuthorization service(Fixture f,Approval approval,boolean enabled){
        return new StoredValueSchemaAuthorization(f.f().data().db(),f.f().service(),f.execution(),enabled,()->approval,Clock.systemUTC());
    }
    @Test void sealedGlobalApprovalRequiresExactOperatorAndPreservesMappedFunds() throws Exception {
        var f=fixture();var result=service(f,f.approval(),true).authorize("OPERATOR","GLOBAL1",f.proof());
        assertThat(result.issuerCount()).isEqualTo(1);assertThat(result.accountCount()).isEqualTo(1);
        assertThat(result.approvalHash()).hasSize(64);
        assertThat(f.f().data().jdbc().queryForObject("SELECT account_no FROM sv_account",String.class)).isEqualTo("A1");
        assertThat(f.f().data().jdbc().queryForObject("SELECT write_mode FROM sv_issuer",String.class)).isEqualTo("FENCED");
        assertThatThrownBy(()->service(f,f.approval(),true).authorize("AUDITOR","GLOBAL1",f.proof())).isInstanceOf(BizException.class);
    }
    @Test void disabledUnsealedExpiredOrWrongLedgerApprovalCannotAuthorize() throws Exception {
        var f=fixture();var a=f.approval();
        assertThatThrownBy(()->service(f,a,false).authorize("OPERATOR","GLOBAL1",f.proof())).isInstanceOf(BizException.class);
        for(var invalid:List.of(
                new Approval(a.approvalNo(),a.operatorNo(),a.ledgerFingerprint(),a.inventoryHash(),a.expiresAt(),false,a.receipts()),
                new Approval(a.approvalNo(),a.operatorNo(),a.ledgerFingerprint(),a.inventoryHash(),Instant.now().minusSeconds(1),true,a.receipts()),
                new Approval(a.approvalNo(),a.operatorNo(),"c".repeat(64),a.inventoryHash(),a.expiresAt(),true,a.receipts()),
                new Approval(a.approvalNo(),a.operatorNo(),a.ledgerFingerprint(),"d".repeat(64),a.expiresAt(),true,a.receipts()),
                new Approval(a.approvalNo(),a.operatorNo(),a.ledgerFingerprint(),a.inventoryHash(),a.expiresAt(),true,List.of()))) {
            assertThatThrownBy(()->service(f,invalid,true).authorize("OPERATOR","GLOBAL1",f.proof())).isInstanceOf(BizException.class);
        }
    }
    @Test void unmappedExtraAccountIssuerOrChangedFinancialFactsInvalidatesCoverage() throws Exception {
        var f=fixture();var j=f.f().data().jdbc();var s=service(f,f.approval(),true);
        j.update("UPDATE sv_account SET account_seq=3");
        assertThatThrownBy(()->s.authorize("OPERATOR","GLOBAL1",f.proof())).isInstanceOf(BizException.class);
        j.update("UPDATE sv_account SET account_seq=2");
        j.update("INSERT INTO sv_issuer(tenant_no,issuer_no,scope_type,scope_no,current_issuer_version_no,status,deleted,write_mode) VALUES('OTHER','I2','STORE','S2','IV2','DRAFT',0,'FENCED')");
        assertThatThrownBy(()->s.authorize("OPERATOR","GLOBAL1",f.proof())).isInstanceOf(BizException.class);
        j.update("DELETE FROM sv_issuer WHERE issuer_no='I2'");
        j.update("UPDATE sv_account SET account_no='UNMAPPED'");
        assertThatThrownBy(()->s.authorize("OPERATOR","GLOBAL1",f.proof())).isInstanceOf(BizException.class);
    }
    @Test void alteredMappingReceiptOrChangedSourceRevisionCannotAuthorize() throws Exception {
        var f=fixture();var j=f.f().data().jdbc();var s=service(f,f.approval(),true);
        j.update("UPDATE pay_ent_command_dedup SET payload_hash='altered' WHERE operation_kind='SV_MAINTENANCE_APPLY'");
        assertThatThrownBy(()->s.authorize("OPERATOR","GLOBAL1",f.proof())).isInstanceOf(BizException.class);
        var other=fixture();other.f().data().jdbc().update("UPDATE sv_issuer_store_access SET source_revision=8");
        assertThatThrownBy(()->service(other,other.approval(),true).authorize("OPERATOR","GLOBAL1",other.proof())).isInstanceOf(BizException.class);
    }

    @Test void missingMalformedOversizedOrSymlinkedApprovalFileNeverGrantsAccess() throws Exception{
        var f=fixture();var db=f.f().data().db();var directory=java.nio.file.Files.createTempDirectory("sv-schema-approval-");
        try{
            var path=directory.resolve("approval.json");
            assertThat(StoredValueSchemaAuthorization.load(db,path.toString())).isNull();
            java.nio.file.Files.writeString(path,db.encode(f.approval()));
            assertThat(StoredValueSchemaAuthorization.load(db,path.toString())).isEqualTo(f.approval());
            var link=directory.resolve("linked.json");java.nio.file.Files.createSymbolicLink(link,path);
            assertThat(StoredValueSchemaAuthorization.load(db,link.toString())).isNull();
            java.nio.file.Files.writeString(path,"broken");
            assertThat(StoredValueSchemaAuthorization.load(db,path.toString())).isNull();
            java.nio.file.Files.writeString(path," ".repeat(65537));
            assertThat(StoredValueSchemaAuthorization.load(db,path.toString())).isNull();
            java.nio.file.Files.deleteIfExists(link);java.nio.file.Files.deleteIfExists(path);
        }finally{try(var paths=java.nio.file.Files.list(directory)){for(var path:paths.toList())java.nio.file.Files.deleteIfExists(path);}java.nio.file.Files.deleteIfExists(directory);}
    }

    @Test void changedLotOrPendingRechargeInvalidatesExistingMappingReceipt() throws Exception{
        var f=fixture();var j=f.f().data().jdbc();var s=service(f,f.approval(),true);
        j.update("UPDATE sv_lot SET principal_minor=1");
        assertThatThrownBy(()->s.authorize("OPERATOR","GLOBAL1",f.proof())).isInstanceOf(BizException.class);
        j.update("UPDATE sv_lot SET principal_minor=0");
        j.update("UPDATE sv_recharge SET status='RECEIVED'");
        assertThatThrownBy(()->s.authorize("OPERATOR","GLOBAL1",f.proof())).isInstanceOf(BizException.class);
    }
}
