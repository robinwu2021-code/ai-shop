package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import ai.neargo.shop.promotion.migration.LegacyCouponMigrationPreflightService;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceSystem;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:legacy-preflight;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class LegacyCouponMigrationPreflightFlowTest {
    @Autowired LegacyCouponMigrationPreflightService preflight;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Test void inventoryIncludesTombstonesIssuanceHistoryAndOrphansWithoutInventingLegacyHistory() {
        String suffix=java.util.UUID.randomUUID().toString();
        String coupon="T"+suffix;
        jdbc.update("INSERT INTO pmt_coupon(coupon_no,title,benefit_mode,benefit_value,funder,entity_no,tenant_no,"
                + "archived_at,deleted,created_at,updated_at) VALUES(?,'hidden','CASH',100,'MERCHANT','M1','MAIN',"
                + "CURRENT_TIMESTAMP,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",coupon);
        for(int n=0;n<3;n++) {
            String instance="U"+n+suffix;
            jdbc.update("INSERT INTO pmt_user_coupon(user_coupon_no,coupon_no,user_no,entity_no,status,times_used,"
                    + "received_at,expire_at,tenant_no,deleted,created_at,updated_at) VALUES(?,?,'U1','M1','UNUSED',0,1,1800000000000,'MAIN',?,"
                    + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",instance,coupon,n==0?1:0);
            if(n<2) {
                jdbc.update("INSERT INTO pmt_coupon_issue(issue_no,coupon_no,entity_no,issue_mode,issued_at,tenant_no,"
                        + "deleted,created_at,updated_at) VALUES(?,?,'M1','TARGETED',1,'MAIN',?,CURRENT_TIMESTAMP,"
                        + "CURRENT_TIMESTAMP)","I"+n+suffix,coupon,n==0?1:0);
                jdbc.update("INSERT INTO pmt_apply(apply_no,promo_type,promo_no,user_no,amount_minor,applied_at,"
                        + "tenant_no,deleted,created_at,updated_at) VALUES(?,'COUPON',?,'U1',100,1,'MAIN',?,"
                        + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)","A"+n+suffix,instance,n==0?1:0);
            }
        }
        jdbc.update("INSERT INTO pmt_user_coupon(user_coupon_no,coupon_no,user_no,status,received_at,expire_at,tenant_no,"
                + "deleted,created_at,updated_at) VALUES(?,?,'U1','UNUSED',1,1800000000000,'MAIN',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                "O"+suffix,"MISSING"+suffix);
        jdbc.update("INSERT INTO pmt_apply(apply_no,promo_type,promo_no,user_no,amount_minor,applied_at,tenant_no,"
                + "created_at,updated_at) VALUES(?,'COUPON',?,'U1',100,1,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                "ORPHAN"+suffix,"MISSING"+suffix);
        jdbc.update("INSERT INTO pmt_apply(apply_no,promo_type,promo_no,user_no,amount_minor,applied_at,tenant_no,"
                + "created_at,updated_at) VALUES(?,'COUPON',?,'U1',100,1,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                "ORPHAN-ROOT"+suffix,"O"+suffix);
        String oldTemplate="L"+suffix;
        jdbc.update("INSERT INTO mkt_coupon(coupon_no,title,type,face_minor,funder,entity_no,start_at,end_at,tenant_no,"
                + "deleted,archived_at,created_at,updated_at) VALUES(?,'old audit','FULL_CUT',100,'MERCHANT','M1',1,"
                + "1800000000000,'MAIN',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",oldTemplate);
        jdbc.update("INSERT INTO mkt_user_coupon(user_coupon_no,coupon_no,user_no,status,received_at,tenant_no,deleted,"
                + "created_at,updated_at) VALUES(?,?,'U1','USED',1,'MAIN',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                "OLD-U"+suffix,oldTemplate);
        jdbc.update("INSERT INTO mkt_coupon_issue(issue_no,coupon_no,coupon_name,target,issued_count,tenant_no,deleted,"
                + "created_at,updated_at) VALUES(?,?,'old audit','SINGLE_USER',1,'MAIN',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                "OLD-I"+suffix,oldTemplate);
        jdbc.update("INSERT INTO pmt_coupon_issue(issue_no,coupon_no,issue_mode,issued_at,tenant_no,created_at,updated_at)"
                + " VALUES(?,?,'TARGETED',1,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)","ORPHAN-I"+suffix,"MISSING"+suffix);
        jdbc.update("INSERT INTO pmt_user_coupon(user_coupon_no,coupon_no,user_no,status,received_at,expire_at,tenant_no,"
                + "created_at,updated_at) VALUES(?,?,'U1','UNUSED',1,1800000000000,'OTHER',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                "OTHER-U"+suffix,"OTHER-MISSING"+suffix);
        jdbc.update("INSERT INTO pmt_apply(apply_no,promo_type,promo_no,user_no,applied_at,tenant_no,created_at,updated_at)"
                + " VALUES(?,'COUPON',?,'U1',1,'OTHER',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)","OTHER-A"+suffix,"OTHER-U"+suffix);
        jdbc.update("INSERT INTO pmt_coupon_issue(issue_no,coupon_no,issue_mode,issued_at,tenant_no,created_at,updated_at)"
                + " VALUES(?,?,'TARGETED',1,'OTHER',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)","OTHER-I"+suffix,"OTHER-MISSING"+suffix);
        var ctx=new EntitlementContext("MAIN","MIGRATION","INVENTORY","a".repeat(64));
        var report=preflight.inventory(ctx,SourceSystem.PMT,0,100);
        var line=report.records().stream().filter(r -> coupon.equals(r.templateNo())).findFirst().orElseThrow();
        assertThat(line.templateDeleted()).isTrue();
        assertThat(line.templateArchived()).isTrue();
        assertThat(line.instanceCount()).isEqualTo(3);
        assertThat(line.deletedInstanceCount()).isEqualTo(1);
        assertThat(line.issueCount()).isEqualTo(2);
        assertThat(line.deletedIssueCount()).isEqualTo(1);
        assertThat(line.historyCount()).isEqualTo(2);
        assertThat(line.deletedHistoryCount()).isEqualTo(1);
        assertThat(line.blockers()).contains("INSTANCE_TOMBSTONES_REQUIRE_PRESERVATION",
                "ISSUE_TOMBSTONES_REQUIRE_PRESERVATION","HISTORY_TOMBSTONES_REQUIRE_PRESERVATION");
        assertThat(report.orphanInstances()).isEqualTo(1);
        assertThat(report.orphanIssues()).isEqualTo(1);
        assertThat(report.orphanHistory()).isEqualTo(2);
        assertThat(preflight.inventory(ctx,SourceSystem.PMT,Long.MAX_VALUE,20).records()).isEmpty();
        var legacy=preflight.inventory(ctx,SourceSystem.MKT_LEGACY,0,100);
        assertThat(legacy.orphanHistory()).isNull();
        assertThat(legacy.records()).allSatisfy(r -> assertThat(r.historyCount()).isNull());
        var oldLine=legacy.records().stream().filter(r -> oldTemplate.equals(r.templateNo())).findFirst().orElseThrow();
        assertThat(oldLine.instanceCount()).isEqualTo(1);
        assertThat(oldLine.deletedInstanceCount()).isEqualTo(1);
        assertThat(oldLine.issueCount()).isEqualTo(1);
        assertThat(oldLine.deletedIssueCount()).isEqualTo(1);
        assertThat(oldLine.blockers()).contains("INDIVIDUAL_ISSUE_LINK_UNAVAILABLE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_instance",Integer.class)).isZero();
        assertThatThrownBy(() -> preflight.inventory(ctx,SourceSystem.PAY,0,100)).isInstanceOf(RuntimeException.class);
    }
    @Test void overlapFromHistoricalBackfillAndMissingHistoryAreExplicitAndNoAssetIsImported() {
        long end = 1800000000000L;
        jdbc.update("INSERT INTO mkt_coupon(coupon_no,title,type,face_minor,funder,entity_no,start_at,end_at,"
                + "tenant_no,created_at,updated_at) VALUES('LC1','legacy','FULL_CUT',100,'MERCHANT','M1',1,?,"
                + "'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", end);
        jdbc.update("INSERT INTO mkt_user_coupon(user_coupon_no,coupon_no,user_no,status,received_at,tenant_no,"
                + "created_at,updated_at) VALUES('DUP1','LC1','U1','UNUSED',1,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO pmt_coupon(coupon_no,title,benefit_mode,benefit_value,funder,entity_no,scope_type,"
                + "validity_mode,start_at,end_at,issue_mode,redeem_mode,times_total,tenant_no,created_at,updated_at) "
                + "VALUES('LC1','backfilled','CASH',100,'MERCHANT','M1','ALL','ABSOLUTE',1,?,'CENTER','STORE_CODE',"
                + "5,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", end);
        jdbc.update("INSERT INTO pmt_user_coupon(user_coupon_no,coupon_no,user_no,entity_no,status,times_used,"
                + "received_at,expire_at,redeem_code,tenant_no,created_at,updated_at) VALUES('DUP1','LC1','U1','M1',"
                + "'UNUSED',2,1,?,'SECRET-STATIC-CODE','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", end);
        var context = new EntitlementContext("MAIN", "MIGRATION_REHEARSAL", "BATCH1", "a".repeat(64));
        var legacy = preflight.scan(context, SourceSystem.MKT_LEGACY, 0, 100);
        var promo = preflight.scan(context, SourceSystem.PMT, 0, 100);
        var old = legacy.records().stream().filter(row -> row.sourceNo().equals("DUP1")).findFirst().orElseThrow();
        var current = promo.records().stream().filter(row -> row.sourceNo().equals("DUP1")).findFirst().orElseThrow();
        assertThat(old.remainingUnits()).isEqualTo(1);
        assertThat(current.remainingUnits()).isEqualTo(3);
        assertThat(old.blockers()).contains("CROSS_SOURCE_NUMBER", "SOURCE_FENCE_REQUIRED");
        assertThat(current.blockers()).contains("CROSS_SOURCE_NUMBER", "HISTORY_COUNTER_MISMATCH",
                "LEGACY_CODE_COMPATIBILITY_REQUIRED", "SOURCE_FENCE_REQUIRED");
        assertThat(current.exclusiveUntil().toEpochMilli()).isEqualTo(end + 1);
        assertThat(current.hasLegacyCode()).isTrue();
        assertThat(json.writeValueAsString(promo)).doesNotContain("SECRET-STATIC-CODE", "redeemCode");
        assertThat(preflight.scan(context, SourceSystem.PMT, Long.MAX_VALUE, 10).records()).isEmpty();
        assertThatThrownBy(() -> preflight.scan(context, SourceSystem.PAY, 0, 10)).isInstanceOf(RuntimeException.class);
        for (String table : new String[]{"ent_owner_registry", "ent_instance", "ent_ledger", "pay_ent_outbox"}) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).as(table).isZero();
        }
        assertThat(jdbc.queryForObject("SELECT times_used FROM pmt_user_coupon WHERE user_coupon_no='DUP1'",
                Integer.class)).isEqualTo(2);
    }
}
