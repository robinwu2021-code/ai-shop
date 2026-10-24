package ai.neargo.shop.pay.storedvalue.clearing;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

class StoredValueClearingEntryTest {
    static class Fixture {
        final DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:h2:mem:p5-clearing-"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        final JdbcTemplate jdbc=new JdbcTemplate(ds);final IssuerRepository db=new IssuerRepository(new DataSourceTransactionManager(ds),new ObjectMapper());final StoredValueClearingService service=new StoredValueClearingService(db,true);
        final EntitlementContext ctx=new EntitlementContext("MAIN","CLERK","CLEARING_TEST","0".repeat(64));
        Fixture()throws Exception{
            jdbc.execute("CREATE TABLE ent_usage(tenant_no VARCHAR(32),usage_no VARCHAR(64),operation_no VARCHAR(64),asset_kind VARCHAR(24),status VARCHAR(24),entity_no VARCHAR(64),store_no VARCHAR(64),amount_minor BIGINT,deleted INT DEFAULT 0)");
            jdbc.execute("CREATE TABLE sv_voucher(tenant_no VARCHAR(32),voucher_no VARCHAR(64),account_no VARCHAR(64),biz_type VARCHAR(24),biz_no VARCHAR(64),principal_delta BIGINT,bonus_delta BIGINT,occurred_at TIMESTAMP,issuer_no VARCHAR(64),deleted INT DEFAULT 0)");
            jdbc.execute("CREATE TABLE sv_account(tenant_no VARCHAR(32),account_no VARCHAR(64),issuer_no VARCHAR(64),entity_no VARCHAR(64),currency VARCHAR(8),deleted INT DEFAULT 0)");
            jdbc.execute("CREATE TABLE sv_issuer(tenant_no VARCHAR(32),issuer_no VARCHAR(64),scope_type VARCHAR(24),scope_no VARCHAR(64),deleted INT DEFAULT 0)");
            jdbc.execute("CREATE TABLE sv_issuer_version(tenant_no VARCHAR(32),issuer_no VARCHAR(64),issuer_version_no VARCHAR(64),settlement_entity_no VARCHAR(64),currency VARCHAR(8),clearing_mode VARCHAR(24),deleted INT DEFAULT 0)");
            jdbc.execute("CREATE TABLE sv_lot(tenant_no VARCHAR(32),lot_no VARCHAR(64),account_no VARCHAR(64),recharge_no VARCHAR(64),recharge_store_no VARCHAR(64),issuer_no VARCHAR(64),issuer_version_no VARCHAR(64),release_no VARCHAR(64),deleted INT DEFAULT 0)");
            jdbc.execute("CREATE TABLE sv_hold_allocation(id BIGINT PRIMARY KEY,tenant_no VARCHAR(32),operation_no VARCHAR(64),lot_no VARCHAR(64),issuer_no VARCHAR(64),release_no VARCHAR(64),allocation_no VARCHAR(64),principal_minor BIGINT,bonus_minor BIGINT,deleted INT DEFAULT 0)");
            jdbc.execute("CREATE TABLE sv_entry(tenant_no VARCHAR(32),voucher_no VARCHAR(64),lot_no VARCHAR(64),principal_delta BIGINT,bonus_delta BIGINT,deleted INT DEFAULT 0)");
            jdbc.execute("CREATE TABLE sv_statement(tenant_no VARCHAR(32),voucher_no VARCHAR(64),issuer_no VARCHAR(64),scope_type VARCHAR(24),scope_no VARCHAR(64),operating_entity_no VARCHAR(64),settlement_entity_no VARCHAR(64))");
            jdbc.execute("CREATE TABLE sv_customer_issuer_index(tenant_no VARCHAR(32),account_no VARCHAR(64),last_activity_at TIMESTAMP,last_occurrence_store_no VARCHAR(64),updated_at TIMESTAMP,updated_by VARCHAR(64),version BIGINT DEFAULT 0)");
            String ddl=Files.readString(Path.of("../../shop-app/src/main/resources/db/migration/V570__stored_value_clearing_entries.sql")).replaceAll("(?m)^--.*$","").replaceAll(" ENGINE=InnoDB[^;]*","").replace("entry_type IN ('USE','REVERSAL','ADJUSTMENT')","CASE WHEN entry_type='USE' THEN TRUE WHEN entry_type='REVERSAL' THEN TRUE WHEN entry_type='ADJUSTMENT' THEN TRUE ELSE FALSE END");jdbc.execute(ddl);
            jdbc.update("INSERT INTO ent_usage VALUES('MAIN','U1','OP1','STORED_VALUE','COMMITTED','E2','B',2600,0)");
            jdbc.update("INSERT INTO sv_voucher VALUES('MAIN','V1','A1','CONSUME','OP1',-2000,-600,TIMESTAMP '2026-10-24 03:00:00',NULL,0)");
            jdbc.update("INSERT INTO sv_account VALUES('MAIN','A1','I1','E1','CNY',0)");jdbc.update("INSERT INTO sv_issuer VALUES('MAIN','I1','BRAND','BRAND1',0)");
            jdbc.update("INSERT INTO sv_issuer_version VALUES('MAIN','I1','IV1','E1','CNY','PRINCIPAL_COMPONENT',0)");
            jdbc.update("INSERT INTO sv_lot VALUES('MAIN','L1','A1','R1','A','I1','IV1','RELEASE1',0)");
            jdbc.update("INSERT INTO sv_hold_allocation VALUES(1,'MAIN','OP1','L1','I1','RELEASE1','AL1',2000,600,0)");
            jdbc.update("INSERT INTO sv_entry VALUES('MAIN','V1','L1',-2000,-600,0)");jdbc.update("INSERT INTO sv_statement(tenant_no,voucher_no) VALUES('MAIN','V1')");jdbc.update("INSERT INTO sv_customer_issuer_index(tenant_no,account_no) VALUES('MAIN','A1')");
        }
        void record(){db.write(()->{service.record(ctx,"U1","V1");return null;});}
    }
    @Test void frozenComponentsProduceOneClearingFactAndReplayDoesNotRewriteIt()throws Exception{
        var f=new Fixture();f.record();var row=f.jdbc.queryForMap("SELECT * FROM sv_clearing_entry");assertThat(row.get("principal_used_minor")).isEqualTo(2000L);assertThat(row.get("bonus_used_minor")).isEqualTo(600L);assertThat(row.get("payable_minor")).isEqualTo(2000L);assertThat(row.get("issuer_borne_minor")).isEqualTo(600L);assertThat(row.get("recharge_store_no")).isEqualTo("A");assertThat(row.get("redemption_store_no")).isEqualTo("B");
        f.record();assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM sv_clearing_entry",Long.class)).isEqualTo(1);assertThat(f.jdbc.queryForObject("SELECT version FROM sv_customer_issuer_index",Long.class)).isEqualTo(1);
    }
    @Test void sameEntityProducesInternalReconciliationWithoutFakePayable()throws Exception{
        var f=new Fixture();f.jdbc.update("UPDATE ent_usage SET entity_no='E1'");f.record();assertThat(f.jdbc.queryForObject("SELECT payable_minor FROM sv_clearing_entry",Long.class)).isZero();assertThat(f.jdbc.queryForObject("SELECT bonus_used_minor FROM sv_clearing_entry",Long.class)).isEqualTo(600);
    }
    @Test void inconsistentFrozenAllocationRollsBackClearingAndProjection()throws Exception{
        var f=new Fixture();f.jdbc.update("UPDATE sv_entry SET principal_delta=-1999");assertThatThrownBy(f::record).isInstanceOf(BizException.class);assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM sv_clearing_entry",Long.class)).isZero();assertThat(f.jdbc.queryForObject("SELECT version FROM sv_customer_issuer_index",Long.class)).isZero();
    }
}
