package ai.neargo.shop.pay.storedvalue.migration;

import ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

class IssuerAccountMigrationTest {
    record Fixture(JdbcTemplate jdbc,StoredValueIssuerMigrationService service) { }
    Fixture fixture() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:migration-"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        var j=new JdbcTemplate(ds);
        j.execute("CREATE TABLE sv_account(tenant_no varchar,account_no varchar,entity_no varchar,store_no varchar,customer_no varchar,user_no varchar,currency varchar,principal_minor bigint,bonus_minor bigint,held_principal_minor bigint,held_bonus_minor bigint,account_seq bigint,version bigint,deleted int)");
        j.execute("CREATE TABLE sv_lot(tenant_no varchar,lot_no varchar,account_no varchar,recharge_no varchar,principal_minor bigint,bonus_minor bigint,held_principal_minor bigint,held_bonus_minor bigint,deleted int)");
        j.execute("CREATE TABLE sv_voucher(tenant_no varchar,voucher_no varchar,account_no varchar,account_seq bigint,after_principal_minor bigint,after_bonus_minor bigint,payload_hash varchar,principal_delta bigint,bonus_delta bigint,deleted int)");
        j.execute("CREATE TABLE sv_recharge(tenant_no varchar,recharge_no varchar,entity_no varchar,store_no varchar,status varchar,version bigint,deleted int)");
        j.execute("CREATE TABLE sv_entry(tenant_no varchar,voucher_no varchar,lot_no varchar,principal_delta bigint,bonus_delta bigint,deleted int)");
        j.execute("CREATE TABLE sv_posting_line(tenant_no varchar,voucher_no varchar,line_no int,pool varchar,book varchar,side varchar,amount_minor bigint,deleted int)");
        j.execute("CREATE TABLE sv_hold_allocation(tenant_no varchar,operation_no varchar,lot_no varchar,principal_minor bigint,bonus_minor bigint,deleted int)");
        j.execute("CREATE TABLE ent_use_intent(tenant_no varchar,operation_no varchar,status varchar,entity_no varchar,store_no varchar,deleted int)");
        j.execute("CREATE TABLE sv_customer_identity(tenant_no varchar,customer_no varchar,user_no varchar)");
        j.execute("CREATE TABLE sv_issuer(tenant_no varchar,issuer_no varchar,scope_type varchar,scope_no varchar,current_issuer_version_no varchar,status varchar,deleted int)");
        j.execute("CREATE TABLE sv_issuer_version(tenant_no varchar,issuer_no varchar,issuer_version_no varchar,settlement_entity_no varchar,currency varchar,deleted int)");
        j.update("INSERT INTO sv_issuer VALUES('MAIN','I1','STORE','S1','IV1','DRAFT',0)");
        j.update("INSERT INTO sv_issuer_version VALUES('MAIN','I1','IV1','E1','CNY',0)");
        return new Fixture(j,new StoredValueIssuerMigrationService(new IssuerRepository(new DataSourceTransactionManager(ds),new ObjectMapper())));
    }
    StoredValueIssuerMigrationService.Batch batch() {
        return new StoredValueIssuerMigrationService.Batch("MAIN","AUDITOR",List.of(new StoredValueIssuerMigrationService.StoreMapping("S1","E1","I1")),List.of(new StoredValueIssuerMigrationService.IdentityEvidence("P1","U1")));
    }
    void zeroBalanceHistory(JdbcTemplate j) {
        j.update("INSERT INTO sv_account VALUES('MAIN','A1','E1','S1','P1','U1','CNY',0,0,0,0,2,2,0)");
        j.update("INSERT INTO sv_lot VALUES('MAIN','L1','A1','R1',0,0,0,0,0)");
        j.update("INSERT INTO sv_voucher VALUES('MAIN','V1','A1',1,1,0,'hash1',1,0,0),('MAIN','V2','A1',2,0,0,'hash2',-1,0,0)");
        j.update("INSERT INTO sv_recharge VALUES('MAIN','R1','E1','S1','CREDITED',2,0)");
        j.update("INSERT INTO sv_customer_identity VALUES('MAIN','P1','U1')");
        j.update("INSERT INTO sv_entry VALUES('MAIN','V1','L1',1,0,0),('MAIN','V2','L1',-1,0,0)");
        j.update("INSERT INTO sv_posting_line VALUES('MAIN','V1',1,'PRINCIPAL','CUSTOMER_CLAIM','DEBIT',1,0),('MAIN','V1',2,'PRINCIPAL','MERCHANT_OBLIGATION','CREDIT',1,0),('MAIN','V2',1,'PRINCIPAL','CUSTOMER_CLAIM','CREDIT',1,0),('MAIN','V2',2,'PRINCIPAL','MERCHANT_OBLIGATION','DEBIT',1,0)");
    }
    @Test void migrationPreservesFactsAndIncludesZeroBalanceHistory() {
        var f=fixture();zeroBalanceHistory(f.jdbc());
        var report=f.service().audit(batch());
        assertThat(report.ready()).isTrue();assertThat(report.accounts()).hasSize(1);
        assertThat(report.accounts().getFirst().accountNo()).isEqualTo("A1");
        assertThat(report.accounts().getFirst().issuerNo()).isEqualTo("I1");
        assertThat(f.jdbc().queryForObject("SELECT account_seq FROM sv_account",Long.class)).isEqualTo(2);
        assertThat(f.jdbc().queryForObject("SELECT COUNT(*) FROM sv_voucher",Long.class)).isEqualTo(2);
        assertThat(f.service().audit(batch()).fingerprint()).isEqualTo(report.fingerprint());
    }
    @Test void nullProvenanceOrUnbalancedLotsBlockInsteadOfGuessingOrChangingMoney() {
        var f=fixture();zeroBalanceHistory(f.jdbc());
        f.jdbc().update("UPDATE sv_account SET store_no=NULL,principal_minor=1");
        var report=f.service().audit(batch());assertThat(report.ready()).isFalse();
        assertThat(report.issues()).extracting(x->x.code()).contains("STORE_PROVENANCE_MISSING");
        assertThat(f.jdbc().queryForObject("SELECT principal_minor FROM sv_account",Long.class)).isEqualTo(1);
        f.jdbc().update("UPDATE sv_account SET store_no='S1'");
        assertThat(f.service().audit(batch()).issues()).extracting(x->x.code()).contains("LOT_BALANCE_MISMATCH");
    }
    @Test void receivedUnknownAndDuplicateCanonicalWalletsBlockCutover() {
        var f=fixture();zeroBalanceHistory(f.jdbc());
        f.jdbc().update("INSERT INTO sv_recharge VALUES('MAIN','PENDING','E1','S1','RECEIVED',1,0)");
        f.jdbc().update("INSERT INTO sv_account VALUES('MAIN','A2','E1','S1','P1','U1','CNY',0,0,0,0,0,0,0)");
        var report=f.service().audit(batch());
        assertThat(report.issues()).extracting(x->x.code()).contains("RECHARGE_IN_FLIGHT","DUPLICATE_ISSUER_CUSTOMER");
        assertThat(report.ready()).isFalse();
    }
    @Test void zeroBalanceDoesNotHideBrokenHistoricalVoucherAmountsOrMissingLot() {
        var f=fixture();zeroBalanceHistory(f.jdbc());
        f.jdbc().update("UPDATE sv_voucher SET principal_delta=1 WHERE voucher_no='V1'");
        f.jdbc().update("UPDATE sv_voucher SET principal_delta=-1 WHERE voucher_no='V2'");
        f.jdbc().update("UPDATE sv_voucher SET after_principal_minor=2 WHERE voucher_no='V1'");
        assertThat(f.service().audit(batch()).issues()).extracting(x->x.code()).contains("VOUCHER_RUNNING_BALANCE_MISMATCH");
        f.jdbc().update("UPDATE sv_voucher SET after_principal_minor=1 WHERE voucher_no='V1'");
        f.jdbc().update("DELETE FROM sv_lot WHERE lot_no='L1'");
        assertThat(f.service().audit(batch()).issues()).extracting(x->x.code()).contains("HISTORICAL_LOT_MISSING");
    }
    @Test void brokenEntryOrJournalAndMissingHoldEvidenceBlockAudit() {
        var f=fixture();zeroBalanceHistory(f.jdbc());
        f.jdbc().update("UPDATE sv_entry SET principal_delta=0 WHERE voucher_no='V1'");
        f.jdbc().update("UPDATE sv_posting_line SET amount_minor=2 WHERE voucher_no='V1' AND side='CREDIT'");
        var broken=f.service().audit(batch());
        assertThat(broken.issues()).extracting(x->x.code()).contains("VOUCHER_ENTRY_MISMATCH","JOURNAL_UNBALANCED");
        f.jdbc().update("UPDATE sv_account SET principal_minor=1,held_principal_minor=1");
        f.jdbc().update("UPDATE sv_lot SET principal_minor=1,held_principal_minor=1");
        assertThat(f.service().audit(batch()).issues()).extracting(x->x.code()).contains("HOLD_EVIDENCE_MISMATCH");
    }
    @Test void creditedRechargeWithoutLotAndWrongJournalBookAreNotHiddenByBalancedTotals() {
        var f=fixture();zeroBalanceHistory(f.jdbc());
        f.jdbc().update("INSERT INTO sv_recharge VALUES('MAIN','ORPHAN','E1','S1','CREDITED',1,0)");
        f.jdbc().update("UPDATE sv_posting_line SET book='CUSTOMER_CLAIM' WHERE voucher_no='V1' AND side='CREDIT'");
        assertThat(f.service().audit(batch()).issues()).extracting(x->x.code()).contains("ORPHAN_CREDITED_RECHARGE","JOURNAL_BOOK_MISMATCH");
    }
    void migrationSchema(JdbcTemplate j) throws Exception {
        j.execute("CREATE TABLE sv_statement(tenant_no varchar,account_no varchar,store_no varchar,entity_no varchar,type varchar,occurred_at timestamp,version bigint default 0,deleted int default 0,updated_by varchar,updated_at timestamp)");
        j.execute("CREATE TABLE sv_recharge_plan(tenant_no varchar,entity_no varchar,store_no varchar,plan_no varchar,version bigint default 0,deleted int default 0,updated_by varchar,updated_at timestamp)");
        for(String table:List.of("sv_account","sv_lot","sv_recharge","sv_voucher","sv_hold_allocation","sv_issuer")) {
            if(!List.of("sv_account","sv_recharge").contains(table)) j.execute("ALTER TABLE "+table+" ADD COLUMN version bigint DEFAULT 0");
            j.execute("ALTER TABLE "+table+" ADD COLUMN updated_at timestamp");j.execute("ALTER TABLE "+table+" ADD COLUMN updated_by varchar");
        }
        j.execute("ALTER TABLE sv_lot ADD COLUMN credited_at timestamp");
        j.update("UPDATE sv_lot SET credited_at='2026-10-20 01:00:00'");
        j.execute("CREATE TABLE pay_ent_command_dedup(id bigint auto_increment primary key,tenant_no varchar,operation_kind varchar,request_key varchar,payload_hash varchar,resource_no varchar,result_status varchar,response_snapshot varchar(20000),version bigint,deleted int,created_at timestamp,updated_at timestamp,created_by varchar,updated_by varchar,UNIQUE(tenant_no,operation_kind,request_key))");
        String ddl=java.nio.file.Files.readString(java.nio.file.Path.of("../../shop-app/src/main/resources/db/migration/V549__stored_value_issuer_ledger_dimensions.sql"))
                .replaceAll("(?m)^--.*$", "").replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_520_ci", "").replace(" JSON", " VARCHAR(10000)");
        for(String part:ddl.split(";")) if(!part.isBlank()) {
            var m=java.util.regex.Pattern.compile("CREATE TABLE ([a-z_]+)").matcher(part);
            if(m.find()) part=part.replace("KEY uk_","KEY "+m.group(1)+"_uk_").replace("KEY ix_","KEY "+m.group(1)+"_ix_");
            j.execute(part);
        }
    }
    @Test void auditedMappingIsIdempotentAndPreservesOriginalIdsAndMoney() throws Exception {
        var f=fixture();zeroBalanceHistory(f.jdbc());migrationSchema(f.jdbc());
        var report=f.service().audit(batch());assertThat(report.ready()).isTrue();
        var result=f.service().map(batch(),report.fingerprint(),"M1");
        assertThat(f.service().map(batch(),report.fingerprint(),"M1")).isEqualTo(result);
        assertThat(f.jdbc().queryForObject("SELECT account_no FROM sv_account",String.class)).isEqualTo("A1");
        assertThat(f.jdbc().queryForObject("SELECT issuer_no FROM sv_account",String.class)).isEqualTo("I1");
        assertThat(f.jdbc().queryForObject("SELECT principal_minor+bonus_minor FROM sv_account",Long.class)).isZero();
        assertThat(f.jdbc().queryForObject("SELECT account_seq FROM sv_account",Long.class)).isEqualTo(2);
        assertThat(f.jdbc().queryForObject("SELECT COUNT(*) FROM sv_voucher",Long.class)).isEqualTo(2);
        assertThat(f.jdbc().queryForObject("SELECT has_recharge FROM sv_customer_issuer_index",Integer.class)).isEqualTo(1);
        assertThat(f.jdbc().queryForObject("SELECT status FROM sv_issuer",String.class)).isEqualTo("DRAFT");
    }
    @Test void changedFactsInvalidateManifestWithoutPartialMetadataWrites() throws Exception {
        var f=fixture();zeroBalanceHistory(f.jdbc());migrationSchema(f.jdbc());
        var report=f.service().audit(batch());
        f.jdbc().update("UPDATE sv_account SET version=version+1");
        assertThatThrownBy(()->f.service().map(batch(),report.fingerprint(),"M1")).isInstanceOf(ai.neargo.shop.common.BizException.class);
        assertThat(f.jdbc().queryForObject("SELECT issuer_no FROM sv_account",String.class)).isNull();
        assertThat(f.jdbc().queryForObject("SELECT COUNT(*) FROM pay_ent_command_dedup",Long.class)).isZero();
    }
    @Test void laterConfigurationDoesNotReinterpretAlreadyMappedOriginalFacts() throws Exception {
        var f=fixture();zeroBalanceHistory(f.jdbc());migrationSchema(f.jdbc());
        var first=f.service().audit(batch());f.service().map(batch(),first.fingerprint(),"M1");
        f.jdbc().update("INSERT INTO sv_issuer_version VALUES('MAIN','I1','IV2','E1','CNY',0)");
        f.jdbc().update("UPDATE sv_issuer SET current_issuer_version_no='IV2'");
        var second=f.service().audit(batch());f.service().map(batch(),second.fingerprint(),"M2");
        for(String table:List.of("sv_account","sv_lot","sv_recharge"))
            assertThat(f.jdbc().queryForObject("SELECT issuer_version_no FROM "+table,String.class)).isEqualTo("IV1");
    }
    @Test void finalMetadataMappingKeepsPersistentFenceAcrossFailureSuccessAndReplay() throws Exception {
        var f=fixture();zeroBalanceHistory(f.jdbc());migrationSchema(f.jdbc());
        f.jdbc().update("UPDATE sv_issuer SET write_mode='FENCED'");
        var stale=f.service().audit(batch());
        f.jdbc().update("UPDATE sv_account SET version=version+1");
        assertThatThrownBy(()->f.service().map(batch(),stale.fingerprint(),"MF")).isInstanceOf(ai.neargo.shop.common.BizException.class);
        assertThat(f.jdbc().queryForObject("SELECT write_mode FROM sv_issuer",String.class)).isEqualTo("FENCED");
        assertThat(f.jdbc().queryForObject("SELECT issuer_no FROM sv_account",String.class)).isNull();
        assertThat(f.jdbc().queryForObject("SELECT COUNT(*) FROM pay_ent_command_dedup",Long.class)).isZero();
        var fresh=f.service().audit(batch());var result=f.service().map(batch(),fresh.fingerprint(),"MF");
        assertThat(f.service().map(batch(),fresh.fingerprint(),"MF")).isEqualTo(result);
        assertThat(f.jdbc().queryForObject("SELECT write_mode FROM sv_issuer",String.class)).isEqualTo("FENCED");
        assertThat(f.jdbc().queryForObject("SELECT status FROM sv_issuer",String.class)).isEqualTo("DRAFT");
        assertThat(f.jdbc().queryForObject("SELECT issuer_no FROM sv_account",String.class)).isEqualTo("I1");
        assertThat(f.jdbc().queryForObject("SELECT account_no FROM sv_account",String.class)).isEqualTo("A1");
        assertThat(f.jdbc().queryForObject("SELECT account_seq FROM sv_account",Long.class)).isEqualTo(2);
        assertThat(f.jdbc().queryForObject("SELECT COUNT(*) FROM sv_voucher",Long.class)).isEqualTo(2);
        assertThat(f.jdbc().queryForObject("SELECT principal_minor+bonus_minor FROM sv_account",Long.class)).isZero();
    }

}
