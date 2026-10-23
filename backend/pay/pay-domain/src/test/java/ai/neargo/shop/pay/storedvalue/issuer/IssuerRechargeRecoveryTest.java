package ai.neargo.shop.pay.storedvalue.issuer;

import ai.neargo.shop.common.*;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

class IssuerRechargeRecoveryTest {
    record Fixture(JdbcTemplate jdbc,IssuerRepository db,StoredValueIssueQuotaService quotas) { }
    final EntitlementContext actor=new EntitlementContext("MAIN","U1","REQ1","0".repeat(64));
    Fixture fixture() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:quota-"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        var j=new JdbcTemplate(ds);
        String sql=Files.readString(Path.of("../../shop-app/src/main/resources/db/migration/V547__stored_value_issuer_ledger_dimensions.sql"));
        for(String table:List.of("sv_issue_quota","sv_issue_quota_reservation")) {
            int start=sql.indexOf("CREATE TABLE "+table+" (");String part=sql.substring(start,sql.indexOf(';',start));
            part=part.replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_520_ci", "").replace(" JSON", " VARCHAR(10000)").replace("KEY uk_","KEY "+table+"_uk_");j.execute(part);
        }
        j.execute("CREATE TABLE sv_recharge(tenant_no varchar,recharge_no varchar,issuer_no varchar,store_no varchar,currency varchar,principal_minor bigint,bonus_minor bigint,status varchar,received_at timestamp,quota_reservation_no varchar,deleted int default 0,version bigint default 0,updated_at timestamp,updated_by varchar)");
        var db=new IssuerRepository(new DataSourceTransactionManager(ds),new ObjectMapper());
        return new Fixture(j,db,new StoredValueIssueQuotaService(db));
    }
    void original(JdbcTemplate j,String no,long amount,String state) {
        j.update("INSERT INTO sv_recharge(tenant_no,recharge_no,issuer_no,store_no,currency,principal_minor,bonus_minor,status) VALUES('MAIN',?,'I1','S1','CNY',?,0,?)",no,amount,state);
    }
    @Test void receivedUnknownResumesOriginalAndCannotReleaseByTimeout() throws Exception {
        var f=fixture();original(f.jdbc(),"R1",1,"RECEIVED");
        f.jdbc().update("UPDATE sv_recharge SET received_at=CURRENT_TIMESTAMP WHERE recharge_no='R1'");
        var reserved=f.quotas().reserve(actor,"R1");
        assertThat(f.quotas().expire(actor,"R1",java.time.Instant.now().plusSeconds(999999))).isFalse();
        assertThat(f.jdbc().queryForObject("SELECT reserved_minor FROM sv_issue_quota",Long.class)).isEqualTo(1);
        f.jdbc().update("UPDATE sv_recharge SET status='CREDITED' WHERE recharge_no='R1'");
        f.quotas().commit(actor,"R1");f.quotas().commit(actor,"R1");
        assertThat(f.quotas().reserve(actor,"R1").reservationNo()).isEqualTo(reserved.reservationNo());
        assertThat(f.jdbc().queryForObject("SELECT outstanding_minor FROM sv_issue_quota",Long.class)).isEqualTo(1);
        assertThat(f.jdbc().queryForObject("SELECT reserved_minor FROM sv_issue_quota",Long.class)).isZero();
    }
    @Test void cancellationReleasesOnceAndPostingRollbackDoesNotLeakQuota() throws Exception {
        var f=fixture();original(f.jdbc(),"R1",1,"WAIT_COLLECTION");
        assertThatThrownBy(()->f.db().write(()->{f.quotas().reserve(actor,"R1");throw BizException.of(ErrorCode.CONFLICT);})).isInstanceOf(BizException.class);
        assertThat(f.jdbc().queryForObject("SELECT COUNT(*) FROM sv_issue_quota_reservation",Long.class)).isZero();
        f.quotas().reserve(actor,"R1");f.jdbc().update("UPDATE sv_recharge SET status='CANCELLED'");
        f.quotas().release(actor,"R1");f.quotas().release(actor,"R1");
        assertThat(f.jdbc().queryForObject("SELECT reserved_minor FROM sv_issue_quota",Long.class)).isZero();
        assertThat(f.jdbc().queryForObject("SELECT status FROM sv_issue_quota_reservation",String.class)).isEqualTo("RELEASED");
    }
    @Test void aggregateOverflowRejectsSecondReservationWithoutChangingFirst() throws Exception {
        var f=fixture();original(f.jdbc(),"R1",Long.MAX_VALUE,"WAIT_COLLECTION");original(f.jdbc(),"R2",1,"WAIT_COLLECTION");
        f.quotas().reserve(actor,"R1");
        assertThatThrownBy(()->f.quotas().reserve(actor,"R2")).isInstanceOf(BizException.class);
        assertThat(f.jdbc().queryForObject("SELECT reserved_minor FROM sv_issue_quota",Long.class)).isEqualTo(Long.MAX_VALUE);
        assertThat(f.jdbc().queryForObject("SELECT COUNT(*) FROM sv_issue_quota_reservation",Long.class)).isEqualTo(1);
    }
}
