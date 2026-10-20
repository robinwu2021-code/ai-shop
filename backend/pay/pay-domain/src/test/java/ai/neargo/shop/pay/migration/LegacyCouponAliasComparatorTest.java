package ai.neargo.shop.pay.migration;

import static org.assertj.core.api.Assertions.assertThat;
import ai.neargo.shop.pay.migration.service.LegacyCouponAliasComparator;
import ai.neargo.shop.spi.pay.MarketPort.MarketBrief;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class LegacyCouponAliasComparatorTest {
    private final ObjectMapper json=new ObjectMapper();
    private final MarketBrief market=new MarketBrief("CN","China","CNY",2,"Asia/Shanghai",1,true);
    private ObjectNode source(String system) {
        var node=(ObjectNode)json.readTree("""
                {"manifestVersion":2,"source":{"sourceSystem":"PMT","sourceKind":"TEMPLATE","sourceNo":"T1"},
                 "template":{"couponNo":"T1","entityNo":"M1","funder":"MERCHANT","title":"old",
                 "type":"FULL_CUT","faceMinor":100,"thresholdMinor":50,"benefitMode":"CASH","benefitValue":100,
                 "minAmountMinor":50,"scopeType":"ALL","validityMode":"ABSOLUTE","startAt":1792454400000,
                 "endAt":1800000000000,"timesTotal":1,"issueMode":"CENTER","redeemMode":"ORDER","status":"ACTIVE",
                 "receivedCount":1,"totalCount":10,"perUserLimit":1,"budgetMinor":1000},
                 "instances":[{"userCouponNo":"U1","couponNo":"T1","userNo":"PRIVATE-USER","entityNo":"M1",
                 "status":"UNUSED","timesUsed":0,"receivedAt":1792454400000,"expireAt":1800000000000,"deleted":0}],
                 "issues":[],"applications":[],"scopes":[],"historyAvailable":true,
                 "assessmentFacts":{"version":1,"entityActive":true,"market":"CN","peerTemplateCount":1,"peerInstanceCount":1}}
                """);
        ((ObjectNode)node.path("source")).put("sourceSystem",system);
        node.put("historyAvailable","PMT".equals(system));
        return node;
    }
    @Test void identicalUnspentFactsAreOnlyACandidateWithoutChangingEitherSource() {
        var pmt=source("PMT"); var mkt=source("MKT_LEGACY");
        String before=pmt.toString();
        var result=new LegacyCouponAliasComparator().compare(pmt,mkt,market,Instant.now());
        assertThat(result.status()).isEqualTo("CANDIDATE_ALIAS");
        assertThat(result.importReady()).isFalse();
        assertThat(result.matchedInstanceCount()).isEqualTo(1);
        assertThat(result.comparisonHash()).matches("[a-f0-9]{64}");
        assertThat(result.toString()).doesNotContain("PRIVATE-USER");
        assertThat(pmt.toString()).isEqualTo(before);
        assertThat(result).isEqualTo(new LegacyCouponAliasComparator().compare(pmt,mkt,market,Instant.now()));
    }
    @Test void matchingNumbersNeverOverrideDifferentRecipientsOrRemainingRights() {
        var pmt=source("PMT"); var mkt=source("MKT_LEGACY");
        ((ObjectNode)mkt.path("instances").get(0)).put("userNo","OTHER");
        assertThat(new LegacyCouponAliasComparator().compare(pmt,mkt,market,Instant.now()).differences())
                .contains("INSTANCE_FACTS_DIFFER");
        ((ObjectNode)mkt.path("instances").get(0)).put("userNo","PRIVATE-USER");
        ((ObjectNode)pmt.path("template")).put("timesTotal",3);
        assertThat(new LegacyCouponAliasComparator().compare(pmt,mkt,market,Instant.now()).status())
                .isEqualTo("DIFFERENT");
    }
    @Test void unrelatedCollisionsAndHistoryCannotBeDeclaredCopiedAliases() {
        var pmt=source("PMT"); var mkt=source("MKT_LEGACY");
        ((ObjectNode)pmt.path("assessmentFacts")).put("peerInstanceCount",2);
        assertThat(new LegacyCouponAliasComparator().compare(pmt,mkt,market,Instant.now()).differences())
                .contains("ADDITIONAL_SOURCE_COLLISION");
        ((ObjectNode)pmt.path("assessmentFacts")).put("peerInstanceCount",1);
        ((tools.jackson.databind.node.ArrayNode)pmt.path("applications")).addObject().put("applyNo","HISTORY");
        assertThat(new LegacyCouponAliasComparator().compare(pmt,mkt,market,Instant.now()).differences())
                .contains("HISTORICAL_ALIAS_REQUIRES_PROVENANCE");
    }
    @Test void unusedCashCapsAndZeroThresholdRepresentationsDoNotInventRuleDifferences() {
        var pmt=source("PMT"); var mkt=source("MKT_LEGACY");
        ((ObjectNode)pmt.path("template")).put("benefitCapMinor",200).putNull("minAmountMinor");
        ((ObjectNode)mkt.path("template")).put("maxDiscountMinor",0).put("thresholdMinor",0);
        var result=new LegacyCouponAliasComparator().compare(pmt,mkt,market,Instant.now());
        assertThat(result.status()).as(result.toString()).isEqualTo("CANDIDATE_ALIAS");
    }

}
