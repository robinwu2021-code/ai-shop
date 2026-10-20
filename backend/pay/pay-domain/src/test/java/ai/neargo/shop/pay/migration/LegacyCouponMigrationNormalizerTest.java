package ai.neargo.shop.pay.migration;

import static org.assertj.core.api.Assertions.assertThat;
import ai.neargo.shop.pay.migration.service.LegacyCouponMigrationNormalizer;
import ai.neargo.shop.spi.pay.MarketPort.MarketBrief;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class LegacyCouponMigrationNormalizerTest {
    private final ObjectMapper json=new ObjectMapper();
    private final MarketBrief cn=new MarketBrief("CN","China","CNY",2,"Asia/Shanghai",1,true);
    private tools.jackson.databind.JsonNode source() {
        return json.readTree("""
                {"manifestVersion":2,"source":{"sourceSystem":"PMT","sourceKind":"TEMPLATE","sourceNo":"T1"},
                 "template":{"couponNo":"T1","entityNo":"M1","funder":"MERCHANT","title":"legacy",
                 "benefitMode":"CASH","benefitValue":100,"minAmountMinor":50,"minQty":4,"scopeType":"GOODS",
                 "validityMode":"ABSOLUTE","startAt":1792490400000,"endAt":1800000000000,"timesTotal":3,
                 "issueMode":"CENTER","redeemMode":"ORDER","status":"ACTIVE","receivedCount":1,\n                 "totalCount":10,"perUserLimit":2,"budgetMinor":3000},
                 "instances":[{"userCouponNo":"U1","couponNo":"T1","userNo":"P1","entityNo":"M1",
                 "status":"UNUSED","timesUsed":0,"receivedAt":1792454400000,"expireAt":1800000000000,"deleted":0}],
                 "issues":[],"applications":[],"scopes":[{"scopeType":"GOODS","refNo":"G1","deleted":0}],
                 "historyAvailable":true,"assessmentFacts":{"version":1,"entityActive":true,"market":"CN",
                 "peerTemplateCount":0,"peerInstanceCount":0}}
                """);
    }
    @Test void sourceStillReferencedByLegacyProgramsCannotBeCutOver() {
        var body=(tools.jackson.databind.node.ObjectNode)source();
        ((tools.jackson.databind.node.ObjectNode)body.path("assessmentFacts")).put("legacyProgramReferenceCount",1);
        var report=new LegacyCouponMigrationNormalizer().normalize("MS1",body,cn,Instant.now());
        assertThat(report.status()).isEqualTo("BLOCKED");
        assertThat(report.blockers()).contains("LEGACY_PROGRAM_CONVERSION_REQUIRED");
    }
    @Test void effectiveOrderRulesAndOwnedDatesArePreservedWithoutEnablingPreviouslyIgnoredConstraints() {
        var report=new LegacyCouponMigrationNormalizer().normalize("MS1",source(),cn,Instant.parse("2026-10-20T01:00:00Z"));
        assertThat(report.status()).isEqualTo("NORMALIZED");
        assertThat(report.importReady()).isFalse();
        assertThat(report.definition().currency()).isEqualTo("CNY");
        assertThat(report.definition().minQty()).isNull();
        assertThat(report.definition().redemptionScope().mode()).isEqualTo("ALL");
        assertThat(report.notices()).contains("LEGACY_ORDER_SCOPE_NOT_ENFORCED","LEGACY_ORDER_MIN_QTY_NOT_ENFORCED");
        var item=report.instances().getFirst();
        assertThat(item.remainingUnits()).isEqualTo(3);
        assertThat(item.effectiveFrom()).isEqualTo(Instant.ofEpochMilli(1792454400000L));
        assertThat(item.effectiveUntil()).isEqualTo(Instant.ofEpochMilli(1800000000001L));
        assertThat(item.historicalDefinition().validity().startAt()).isEqualTo(item.effectiveFrom());
    }
    @Test void uncertainCurrencyAndOverlappingSourcesAreBlockedRatherThanAssumedOrMerged() {
        var body=(tools.jackson.databind.node.ObjectNode)source();
        ((tools.jackson.databind.node.ObjectNode)body.path("assessmentFacts")).put("peerTemplateCount",1);
        var result=new LegacyCouponMigrationNormalizer().normalize("MS1",body,null,Instant.now());
        assertThat(result.status()).isEqualTo("BLOCKED");
        assertThat(result.blockers()).contains("SOURCE_NUMBER_COLLISION","MARKET_FACTS_UNAVAILABLE");
        assertThat(result.importReady()).isFalse();
    }
    @Test void inconsistentHistoricalCountersAreReportedWithoutRepairingTheSource() {
        var body=(tools.jackson.databind.node.ObjectNode)source();
        ((tools.jackson.databind.node.ObjectNode)body.path("instances").get(0)).put("timesUsed",1);
        var report=new LegacyCouponMigrationNormalizer().normalize("MS1",body,cn,Instant.now());
        assertThat(report.status()).isEqualTo("BLOCKED");
        assertThat(report.blockers()).contains("HISTORY_COUNTER_MISMATCH");
        assertThat(body.path("instances").get(0).path("timesUsed").asInt()).isEqualTo(1);
    }
    @Test void legacyBasisPointsAndDeletedRowsRemainHistoricalWithoutInventingUsageAmounts() {
        var body=(tools.jackson.databind.node.ObjectNode)source();
        ((tools.jackson.databind.node.ObjectNode)body.path("source")).put("sourceSystem","MKT_LEGACY");
        var root=(tools.jackson.databind.node.ObjectNode)body.path("template");
        root.put("type","DISCOUNT").put("discountRate",8500).put("maxDiscountMinor",200).put("thresholdMinor",50);
        body.put("historyAvailable",false);
        var instance=(tools.jackson.databind.node.ObjectNode)body.path("instances").get(0);
        instance.put("status","USED").put("deleted",1);
        var result=new LegacyCouponMigrationNormalizer().normalize("MS1",body,cn,Instant.now());
        assertThat(result.definition().benefitValue()).isEqualTo(8500L);
        assertThat(result.instances().getFirst().archivalOnly()).isTrue();
        assertThat(result.instances().getFirst().remainingUnits()).isZero();
        assertThat(result.blockers()).contains("SOURCE_HISTORY_UNAVAILABLE");
        assertThat(result.importReady()).isFalse();
    }
    @Test void overflowDatesInactiveTemplatesAndUnsupportedCodesCannotBecomeUsableRights() {
        var body=(tools.jackson.databind.node.ObjectNode)source();
        var root=(tools.jackson.databind.node.ObjectNode)body.path("template");
        root.put("status","PAUSED").put("redeemMode","STORE_CODE");
        ((tools.jackson.databind.node.ObjectNode)body.path("instances").get(0)).put("expireAt",Long.MAX_VALUE);
        var result=new LegacyCouponMigrationNormalizer().normalize("MS1",body,cn,Instant.now());
        assertThat(result.blockers()).contains("LEGACY_TEMPLATE_NOT_ACTIVE","LEGACY_REDEMPTION_BRIDGE_REQUIRED",
                "INVALID_INSTANCE_VALIDITY");
        assertThat(result.instances().getFirst().historicalDefinition()).isNull();
    }
    @Test void historicalDefinitionsMayBeExpiredAndMustNotRunNewPublicationValidation() {
        var result=new LegacyCouponMigrationNormalizer().normalize("MS1",source(),cn,Instant.parse("2030-01-01T00:00:00Z"));
        assertThat(result.status()).isEqualTo("NORMALIZED");
        assertThat(result.importReady()).isFalse();
    }

    @Test void malformedOptionalMonetaryFieldsCannotSilentlyBecomeUnrestrictedRules() {
        var body=(tools.jackson.databind.node.ObjectNode)source();
        ((tools.jackson.databind.node.ObjectNode)body.path("template")).put("benefitCapMinor","bad-data");
        assertThat(new LegacyCouponMigrationNormalizer().normalize("MS1",body,cn,Instant.now()).blockers())
                .contains("INVALID_NUMERIC_FIELD");
    }

    @Test void normalizedRulesCannotHideAnUnfundedLifetimeIssuancePromise() {
        var body=(tools.jackson.databind.node.ObjectNode)source();
        ((tools.jackson.databind.node.ObjectNode)body.path("template")).put("budgetMinor",100);
        var result=new LegacyCouponMigrationNormalizer().normalize("MS1",body,cn,Instant.now());
        assertThat(result.status()).isEqualTo("BLOCKED");
        assertThat(result.issuance().lifetimeIssuedExposureMinor()).isEqualTo(300);
        assertThat(result.blockers()).contains("ISSUANCE_EXPOSURE_EXCEEDS_BUDGET");
    }

    @Test void activityIssuanceRetainsItsExplicitModeAndUnknownModesAreNotGuessed() {
        var body=(tools.jackson.databind.node.ObjectNode)source();
        var root=(tools.jackson.databind.node.ObjectNode)body.path("template");
        root.put("issueMode","ACTIVITY");
        var activity=new LegacyCouponMigrationNormalizer().normalize("MS1",body,cn,Instant.now());
        assertThat(activity.status()).isEqualTo("NORMALIZED");
        assertThat(activity.definition().allowedIssueModes()).containsExactly("TARGETED","ACTIVITY");
        root.put("issueMode","UNKNOWN");
        assertThat(new LegacyCouponMigrationNormalizer().normalize("MS1",body,cn,Instant.now()).blockers())
                .contains("UNSUPPORTED_ISSUE_MODE");
    }
    @Test void historicalUseMustBelongToTheOriginalInstanceRecipientIncludingReversedRecords() {
        var body=(tools.jackson.databind.node.ObjectNode)source();
        var uses=(tools.jackson.databind.node.ArrayNode)body.path("applications");
        uses.addObject().put("applyNo","A1").put("promoType","COUPON").put("promoNo","U1")
                .put("userNo","OTHER").put("entityNo","M1").put("funder","MERCHANT").put("redeemMode","ORDER")
                .put("amountMinor",20).put("appliedAt",1792454400001L).put("revertedAt",1792454400002L);
        var wrongRecipient=new LegacyCouponMigrationNormalizer().normalize("MS1",body,cn,Instant.now());
        assertThat(wrongRecipient.blockers()).contains("INVALID_HISTORY_RECIPIENT");
        ((tools.jackson.databind.node.ObjectNode)uses.get(0)).put("userNo","P1").put("promoNo","MISSING");
        assertThat(new LegacyCouponMigrationNormalizer().normalize("MS1",body,cn,Instant.now()).blockers())
                .contains("INVALID_HISTORY_BINDING");
    }

    @Test void legacyRelativeDaysAreFixedTwentyFourHoursAndOwnedExpiryIsNeverRecalculated() {
        var body=(tools.jackson.databind.node.ObjectNode)source();
        ((tools.jackson.databind.node.ObjectNode)body.path("template")).put("validityMode","RELATIVE").put("validDays",7);
        var dstMarket=new MarketBrief("CN","configured market","CNY",2,"America/New_York",1,true);
        var result=new LegacyCouponMigrationNormalizer().normalize("MS1",body,dstMarket,Instant.now());
        assertThat(result.status()).isEqualTo("NORMALIZED");
        assertThat(result.definition().validity().zone()).isEqualTo("UTC");
        assertThat(result.notices()).contains("LEGACY_RELATIVE_FIXED_24H","LEGACY_RELATIVE_INCLUSIVE_ENDPOINT");
        assertThat(result.instances().getFirst().effectiveUntil()).isEqualTo(Instant.ofEpochMilli(1800000000001L));
    }

}
