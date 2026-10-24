package ai.neargo.shop.pay.storedvalue.issuer;
import ai.neargo.shop.pay.storedvalue.issuer.impl.StoredValueIssueQuotaServiceImpl;


import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IssuerRechargeRecoveryTest {
    record Fixture(JdbcTemplate jdbc,IssuerRepository db,StoredValueIssueQuotaService quotas) { }
    final EntitlementContext actor=new EntitlementContext("MAIN","U1","REQ1","0".repeat(64));
    Fixture fixture(String scope) throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:quota-"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        var j=new JdbcTemplate(ds);
        // The issuer owner determines whether locking uses the STORE or BRAND quota key.
        // Keep the complete production table contracts, including their constraints.
        createTable(j,"V548__stored_value_issuer_foundation.sql","sv_issuer");
        for(String table:List.of("sv_issue_quota","sv_issue_quota_reservation")) {
            createTable(j,"V549__stored_value_issuer_ledger_dimensions.sql",table);
        }
        String brandQuota=migration("V572__stored_value_brand_quota_scope.sql").replaceAll("--[^\\r\\n]*", "");
        for(String statement:brandQuota.split(";"))if(!statement.isBlank())j.execute(statement);
        j.update("INSERT INTO sv_issuer(tenant_no,issuer_no,scope_type,scope_no,name,status,current_issuer_version_no,created_at,updated_at,created_by,updated_by) VALUES('MAIN','I1',?,?,'Recovery issuer','ACTIVE','IV1',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'U1','U1')",scope,"BRAND".equals(scope)?"B1":"S1");
        j.execute("CREATE TABLE sv_recharge(tenant_no varchar,recharge_no varchar,issuer_no varchar,store_no varchar,currency varchar,principal_minor bigint,bonus_minor bigint,status varchar,received_at timestamp,quota_reservation_no varchar,deleted int default 0,version bigint default 0,updated_at timestamp,updated_by varchar)");
        j.execute("CREATE TABLE sv_account(tenant_no varchar,account_no varchar,issuer_no varchar,store_no varchar,currency varchar,deleted int default 0)");
        var db=new IssuerRepository(new DataSourceTransactionManager(ds),new ObjectMapper());
        return new Fixture(j,db,new StoredValueIssueQuotaServiceImpl(db));
    }
    String migration(String name) throws Exception {
        return Files.readString(Path.of("../../shop-app/src/main/resources/db/migration",name));
    }
    void createTable(JdbcTemplate jdbc,String migration,String table) throws Exception {
        String sql=migration(migration);int start=sql.indexOf("CREATE TABLE "+table+" (");
        String part=sql.substring(start,sql.indexOf(';',start));
        part=part.replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_520_ci", "")
                .replace(" JSON", " VARCHAR(10000)").replace("KEY uk_","KEY "+table+"_uk_")
                // H2 retains the DDL session in constant-set IN checks; CASE preserves the allowed values.
                .replace("CHECK (scope_type IN ('BRAND','STORE'))",
                        "CHECK (CASE scope_type WHEN 'BRAND' THEN TRUE WHEN 'STORE' THEN TRUE ELSE FALSE END)");
        jdbc.execute(part);
    }
    void assertQuotaScope(JdbcTemplate jdbc,String scope) {
        var pool=jdbc.queryForMap("SELECT store_no,issuer_no FROM sv_issue_quota");
        assertThat(pool.get("store_no")).isEqualTo("STORE".equals(scope)?"S1":null);
        assertThat(pool.get("issuer_no")).isEqualTo("BRAND".equals(scope)?"I1":null);
    }
    void original(JdbcTemplate j,String no,long amount,String state) {
        j.update("INSERT INTO sv_recharge(tenant_no,recharge_no,issuer_no,store_no,currency,principal_minor,bonus_minor,status) VALUES('MAIN',?,'I1','S1','CNY',?,0,?)",no,amount,state);
    }
    @ParameterizedTest @ValueSource(strings={"STORE","BRAND"})
    void receivedUnknownResumesOriginalAndCannotReleaseByTimeout(String scope) throws Exception {
        var f=fixture(scope);original(f.jdbc(),"R1",1,"RECEIVED");
        f.jdbc().update("UPDATE sv_recharge SET received_at=CURRENT_TIMESTAMP WHERE recharge_no='R1'");
        var reserved=f.quotas().reserve(actor,"R1");assertQuotaScope(f.jdbc(),scope);
        assertThat(f.quotas().expire(actor,"R1",java.time.Instant.now().plusSeconds(999999))).isFalse();
        assertThat(f.jdbc().queryForObject("SELECT reserved_minor FROM sv_issue_quota",Long.class)).isEqualTo(1);
        f.jdbc().update("UPDATE sv_recharge SET status='CREDITED' WHERE recharge_no='R1'");
        f.quotas().commit(actor,"R1");f.quotas().commit(actor,"R1");
        assertThat(f.quotas().reserve(actor,"R1").reservationNo()).isEqualTo(reserved.reservationNo());
        assertThat(f.jdbc().queryForObject("SELECT outstanding_minor FROM sv_issue_quota",Long.class)).isEqualTo(1);
        assertThat(f.jdbc().queryForObject("SELECT reserved_minor FROM sv_issue_quota",Long.class)).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"STORE","BRAND"})
    void cancellationReleasesOnceAndPostingRollbackDoesNotLeakQuota(String scope) throws Exception {
        var f=fixture(scope);original(f.jdbc(),"R1",1,"WAIT_COLLECTION");
        assertThatThrownBy(()->f.db().write(()->{f.quotas().reserve(actor,"R1");throw BizException.of(ErrorCode.CONFLICT);})).isInstanceOf(BizException.class);
        assertThat(f.jdbc().queryForObject("SELECT COUNT(*) FROM sv_issue_quota_reservation",Long.class)).isZero();
        f.quotas().reserve(actor,"R1");assertQuotaScope(f.jdbc(),scope);f.jdbc().update("UPDATE sv_recharge SET status='CANCELLED'");
        f.quotas().release(actor,"R1");f.quotas().release(actor,"R1");
        assertThat(f.jdbc().queryForObject("SELECT reserved_minor FROM sv_issue_quota",Long.class)).isZero();
        assertThat(f.jdbc().queryForObject("SELECT status FROM sv_issue_quota_reservation",String.class)).isEqualTo("RELEASED");
    }
    @ParameterizedTest @ValueSource(strings={"STORE","BRAND"})
    void aggregateOverflowRejectsSecondReservationWithoutChangingFirst(String scope) throws Exception {
        var f=fixture(scope);original(f.jdbc(),"R1",Long.MAX_VALUE,"WAIT_COLLECTION");original(f.jdbc(),"R2",1,"WAIT_COLLECTION");
        f.quotas().reserve(actor,"R1");assertQuotaScope(f.jdbc(),scope);
        assertThatThrownBy(()->f.quotas().reserve(actor,"R2")).isInstanceOf(BizException.class);
        assertThat(f.jdbc().queryForObject("SELECT reserved_minor FROM sv_issue_quota",Long.class)).isEqualTo(Long.MAX_VALUE);
        assertThat(f.jdbc().queryForObject("SELECT COUNT(*) FROM sv_issue_quota_reservation",Long.class)).isEqualTo(1);
    }
}
