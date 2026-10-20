package ai.neargo.shop.pay.migration;
import static org.assertj.core.api.Assertions.*;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.pay.migration.service.LegacyCouponHistoricalUsageProjector;
import ai.neargo.shop.pay.migration.service.LegacyCouponTargetPlan;
import ai.neargo.shop.pay.migration.service.LegacyCouponTargetPlan.Asset;
import ai.neargo.shop.pay.migration.service.LegacyCouponTargetPlan.SourceRef;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
class LegacyCouponHistoricalUsageProjectorTest {
    private final ObjectMapper json=new ObjectMapper();
    private final Instant from=Instant.parse("2026-10-01T00:00:00Z"),until=Instant.parse("2026-11-01T00:00:00Z");
    private LegacyCouponTargetPlan plan(boolean archival) {
        return new LegacyCouponTargetPlan(1,"MP1","M1","ET1",from,
                List.of(new SourceRef("MS1","PMT","T1","B1",2,"a".repeat(64),"b".repeat(64))),List.of(),
                List.of(new Asset("OLD1","U1",archival,"UNUSED",archival?null:"EI1",archival?null:"MG1",archival?null:"EV1",2,1,from,until)),null,List.of());
    }
    private tools.jackson.databind.JsonNode manifest() {
        return json.readTree("""
                {"historyAvailable":true,"applications":[{"applyNo":"AP1","promoType":"COUPON","promoNo":"OLD1",
                "userNo":"U1","entityNo":"M1","storeNo":null,"operatorNo":null,"orderNo":"O1","funder":"MERCHANT",
                "amountMinor":0,"appliedAt":1790812800000,"revertedAt":null}]}
                """);
    }
    @Test void originalKnownZeroAndUnknownStoreOperatorStayDistinctFromInventedFacts() {
        var result=new LegacyCouponHistoricalUsageProjector(json).project("MI1",plan(false),manifest());
        assertThat(result).hasSize(1);var row=result.getFirst();
        assertThat(row.sourceUsageNo()).isEqualTo("AP1");assertThat(row.amountMinor()).isZero();
        assertThat(row.instanceNo()).isEqualTo("EI1");assertThat(row.orderNo()).isEqualTo("O1");
        assertThat(row.storeNo()).isNull();assertThat(row.operatorNo()).isNull();assertThat(row.revertedAt()).isNull();
        assertThat(row.occurredAt()).isEqualTo(Instant.ofEpochMilli(1790812800000L));
        assertThat(row.sourceHash()).hasSize(64);
        assertThat(new LegacyCouponHistoricalUsageProjector(json).project("MI1",plan(true),manifest()).getFirst().instanceNo()).isNull();
    }
    @Test void duplicateOrForeignSourceRowsCannotCreateNewConsumption() {
        var body=(tools.jackson.databind.node.ObjectNode)manifest();
        var rows=(tools.jackson.databind.node.ArrayNode)body.path("applications");rows.add(rows.get(0).deepCopy());
        var duplicated=body;
        assertThatThrownBy(()->new LegacyCouponHistoricalUsageProjector(json).project("MI1",plan(false),duplicated)).isInstanceOf(BizException.class);
        body=(tools.jackson.databind.node.ObjectNode)manifest();
        ((tools.jackson.databind.node.ObjectNode)body.path("applications").get(0)).put("userNo","FOREIGN");
        var changed=body;
        assertThatThrownBy(()->new LegacyCouponHistoricalUsageProjector(json).project("MI1",plan(false),changed)).isInstanceOf(BizException.class);
    }
}
