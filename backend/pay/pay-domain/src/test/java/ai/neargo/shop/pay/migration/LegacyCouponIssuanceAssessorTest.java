package ai.neargo.shop.pay.migration;

import static org.assertj.core.api.Assertions.assertThat;
import ai.neargo.shop.pay.migration.service.LegacyCouponIssuanceAssessor;
import ai.neargo.shop.pay.migration.service.LegacyCouponMigrationNormalizer;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class LegacyCouponIssuanceAssessorTest {
    private final ObjectMapper json=new ObjectMapper();
    private final Instant at=Instant.parse("2026-10-20T00:00:00Z");
    private ObjectNode body() {
        return (ObjectNode)json.readTree("""
                {"source":{"sourceSystem":"PMT"},"template":{"totalCount":10,"receivedCount":2,"perUserLimit":2,"budgetMinor":3000},
                 "instances":[{"userNo":"P1"},{"userNo":"P1"}]}
                """);
    }
    private EntitlementDefinition rule(long value) {
        return new EntitlementDefinition("old","ORDER_COUPON","MERCHANT","CNY","CASH",value,null,null,
                null,null,"COUNT",3,null,null,null,List.of("TARGETED"));
    }
    private List<LegacyCouponMigrationNormalizer.InstancePlan> items() {
        return List.of(new LegacyCouponMigrationNormalizer.InstancePlan("I1",false,"UNUSED",2,at.minusSeconds(1),
                at.plusSeconds(1000),null),new LegacyCouponMigrationNormalizer.InstancePlan("I2",true,"USED",0,
                at.minusSeconds(100),at.minusSeconds(1),null));
    }
    @Test void lifetimeIssuedExposureIsSeparateFromRemainingLiabilityAndDeletedArchive() {
        var result=new LegacyCouponIssuanceAssessor().assess(body(),rule(100),items(),at);
        assertThat(result.blockers()).isEmpty();
        assertThat(result.lifetimeIssuedExposureMinor()).isEqualTo(600);
        assertThat(result.activeRemainingExposureMinor()).isEqualTo(200);
        assertThat(result.archivalInstanceCount()).isEqualTo(1);
        assertThat(result.recipientCount()).isEqualTo(1);
        assertThat(result.sourceIssuedCount()).isEqualTo(2);
        assertThat(result.toString()).doesNotContain("P1");
    }
    @Test void missingRecordsAndOverdrawnBudgetAreBlockedRatherThanBalancedByDeletingHistory() {
        var body=body();
        ((ObjectNode)body.path("template")).put("receivedCount",3).put("budgetMinor",100);
        var result=new LegacyCouponIssuanceAssessor().assess(body,rule(100),items(),at);
        assertThat(result.blockers()).contains("ISSUANCE_COUNTER_MISMATCH","ISSUANCE_EXPOSURE_EXCEEDS_BUDGET");
    }
    @Test void unlimitedLegacyCeilingsRemainExplicitAndOverflowNeverWrapsAround() {
        var body=body();
        ((ObjectNode)body.path("template")).putNull("totalCount").put("budgetMinor",0);
        var result=new LegacyCouponIssuanceAssessor().assess(body,rule(100),items(),at);
        assertThat(result.limitCount()).isNull();
        assertThat(result.maxExposureMinor()).isNull();
        assertThat(result.blockers()).isEmpty();
        var overflow=new LegacyCouponIssuanceAssessor().assess(body,rule(Long.MAX_VALUE),items(),at);
        assertThat(overflow.blockers()).contains("ISSUANCE_EXPOSURE_OVERFLOW");
        assertThat(overflow.lifetimeIssuedExposureMinor()).isNull();
    }
    @Test void historicalRecipientCountsCannotBeResetToBypassPerUserLimits() {
        var body=body();
        ((ObjectNode)body.path("template")).put("perUserLimit",1);
        assertThat(new LegacyCouponIssuanceAssessor().assess(body,rule(100),items(),at).blockers())
                .contains("RECIPIENT_LIMIT_EXCEEDED");
    }
    @Test void zeroCountMeansDifferentThingsInTheTwoSourceSystems() {
        var body=body();
        ((ObjectNode)body.path("template")).put("totalCount",0);
        var pmt=new LegacyCouponIssuanceAssessor().assess(body,rule(100),items(),at);
        assertThat(pmt.limitCount()).isZero();
        assertThat(pmt.blockers()).contains("ISSUANCE_COUNT_EXCEEDS_LIMIT");
        ((ObjectNode)body.path("source")).put("sourceSystem","MKT_LEGACY");
        var mkt=new LegacyCouponIssuanceAssessor().assess(body,rule(100),items(),at);
        assertThat(mkt.limitCount()).isNull();
        assertThat(mkt.blockers()).isEmpty();
    }
    @Test void expiredRightsDoNotReduceLifetimeCommitmentOrBecomeCurrentLiability() {
        var expired=new LegacyCouponMigrationNormalizer.InstancePlan("I1",false,"UNUSED",2,at.minusSeconds(100),
                at.minusSeconds(1),null);
        var result=new LegacyCouponIssuanceAssessor().assess(body(),rule(100),List.of(expired,items().getLast()),at);
        assertThat(result.lifetimeIssuedExposureMinor()).isEqualTo(600);
        assertThat(result.activeRemainingExposureMinor()).isZero();
    }

}
