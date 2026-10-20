package ai.neargo.shop.pay.migration;

import static org.assertj.core.api.Assertions.*;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.pay.migration.service.LegacyCouponIssuanceAssessor.Assessment;
import ai.neargo.shop.pay.migration.service.LegacyCouponMigrationNormalizer.InstancePlan;
import ai.neargo.shop.pay.migration.service.LegacyCouponMigrationNormalizer.Report;
import ai.neargo.shop.pay.migration.service.LegacyCouponTargetPlan;
import ai.neargo.shop.pay.migration.service.LegacyCouponTargetPlan.SourceRef;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class LegacyCouponTargetPlanTest {
    private final ObjectMapper json=new ObjectMapper();
    private final Instant from=Instant.parse("2026-10-01T00:00:00Z"),until=Instant.parse("2026-11-01T00:00:00Z");
    private final EntitlementDefinition rule=new EntitlementDefinition("old","ORDER_COUPON","MERCHANT","CNY","CASH",100L,
            null,null,null,null,"COUNT",2,null,new EntitlementDefinition.Validity("ABSOLUTE",from,until,null,"GRANTED","ROLLING_DAYS","UTC"),
            new EntitlementDefinition.RedemptionScope("ALL",List.of()),List.of("TARGETED"));
    private final Assessment issuance=new Assessment(2L,2,10L,2,2000L,400L,100L,1,2,List.of());
    private final List<SourceRef> sources=List.of(new SourceRef("MS1","PMT","T1","B1",2,"a".repeat(64),"b".repeat(64)));
    private Report report(List<InstancePlan> instances) {
        return new Report("MS1","NORMALIZED",false,rule,instances,List.of(),List.of(),issuance);
    }
    private tools.jackson.databind.JsonNode body() {
        return json.readTree("{\"template\":{\"entityNo\":\"M1\"},\"instances\":[{\"userCouponNo\":\"A\",\"userNo\":\"U1\"},{\"userCouponNo\":\"B\",\"userNo\":\"U2\"}]}");
    }
    @Test void fixedIdentitiesDoNotDependOnInputOrderAndArchivalRowsCannotBecomeGrants() {
        var a=new InstancePlan("A",false,"UNUSED",1,from,until,rule);
        var b=new InstancePlan("B",true,"UNUSED",2,from,until,rule);
        var first=LegacyCouponTargetPlan.build(json,"MP1","M1",from,sources,body(),report(List.of(a,b)));
        var second=LegacyCouponTargetPlan.build(json,"MP1","M1",from,sources,body(),report(List.of(b,a)));
        assertThat(first).isEqualTo(second);assertThat(first.versions()).hasSize(1);
        assertThat(first.assets().getFirst().remainingUnits()).isEqualTo(1);
        assertThat(first.assets().getFirst().instanceNo()).hasSize(64);
        assertThat(first.assets().getLast().instanceNo()).isNull();assertThat(first.assets().getLast().grantNo()).isNull();
        assertThat(first.assets().getLast().versionNo()).isNull();
    }
    @Test void duplicateNormalizedKeysMustNotDropAnotherRecipientOrPlanTwoIdenticalAssets() {
        var a=new InstancePlan("A",false,"UNUSED",1,from,until,rule);
        assertThatThrownBy(()->LegacyCouponTargetPlan.build(json,"MP1","M1",from,sources,body(),report(List.of(a,a))))
                .isInstanceOf(BizException.class);
    }
    @Test void unsupportedHistoricalStateCannotBeAdvertisedAsReadyForPermanentSourceCommit() {
        var a=new InstancePlan("A",false,"UNUSED",1,from,until,rule);
        var b=new InstancePlan("B",true,"UNUSED",2,from,until,rule);
        var plan=LegacyCouponTargetPlan.build(json,"MP1","M1",from,sources,body(),report(List.of(a,b)));
        var stage=new ai.neargo.shop.pay.migration.entity.EntMigrationStage();stage.setHistoryCount(0L);
        assertThatThrownBy(()->ai.neargo.shop.pay.migration.service.LegacyCouponMigrationReadiness.requireSupported(plan,List.of(stage)))
                .isInstanceOf(BizException.class);
    }
}
