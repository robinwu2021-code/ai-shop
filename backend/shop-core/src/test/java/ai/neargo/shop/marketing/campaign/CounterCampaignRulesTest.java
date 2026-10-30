package ai.neargo.shop.marketing.campaign;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.marketing.campaign.entity.MktCampaign;
import ai.neargo.shop.spi.marketing.CampaignPort;
import ai.neargo.shop.spi.marketing.CampaignPort.AppliedActivity;
import ai.neargo.shop.spi.marketing.CampaignPort.CounterActivitySeal;
import ai.neargo.shop.spi.marketing.CampaignPort.CounterRule;
import ai.neargo.shop.spi.marketing.CampaignPort.Discount;
import ai.neargo.shop.spi.marketing.CampaignPort.GoodsLine;
import ai.neargo.shop.spi.marketing.CampaignPort.MerchantAmount;
import ai.neargo.shop.spi.marketing.CampaignPort.MerchantDiscount;
import ai.neargo.shop.spi.marketing.CounterCampaignContract;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.CustomerParty;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Protocol tests deliberately have no database or present-day owner eligibility dependency. */
class CounterCampaignRulesTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final CustomerParty party = new CustomerParty("USER", "U1", null, (Long) null);

    @Test
    void equalAliasesRemainFrozenAndResolveOnce() {
        var requested = Map.of("S1", "A1", "E1", "A1");
        var normalized = CounterCampaignContract.normalizeChoices(groups(), requested);
        assertThat(normalized).containsExactlyEntriesOf(new TreeMap<>(requested));
        assertThat(CounterCampaignContract.resolveChoices(groups(), requested))
                .containsExactly(new CampaignPort.CounterChoice("E1", "S1", "EXPLICIT", "A1"));
        assertThatThrownBy(() -> normalized.put("S1", "NONE")).isInstanceOf(UnsupportedOperationException.class);
        assertChoiceFailure(Map.of("E1", "A1", "S1", "A2"), ErrorCode.BAD_REQUEST);
        assertChoiceFailure(Map.of("FOREIGN", "NONE"), ErrorCode.ACTIVITY_CHOICE_UNAVAILABLE);
        assertChoiceFailure(Map.of("e1", "NONE"), ErrorCode.ACTIVITY_CHOICE_UNAVAILABLE);
        assertChoiceFailure(Map.of(" E1", "NONE"), ErrorCode.BAD_REQUEST);
        assertChoiceFailure(Map.of("E1", " A1"), ErrorCode.BAD_REQUEST);
        assertThat(CounterCampaignContract.resolveChoices(groups(), Map.of()).getFirst().mode()).isEqualTo("AUTO");
        assertThat(CounterCampaignContract.resolveChoices(groups(), Map.of("S1", "NONE")).getFirst().activityNo())
                .isNull();
    }

    @Test
    void canonicalGoodsAggregateExactlyAndSnapshotCollections() {
        var lines = new ArrayList<>(List.of(new GoodsLine("G2", 3000, 1), new GoodsLine("G1", 2000, 1),
                new GoodsLine("G1", 5000, 1)));
        var canonical = CounterCampaignContract.canonicalGroups(
                List.of(new MerchantAmount("E1", 10000, 3, "S1", lines, false)));
        lines.clear();
        assertThat(canonical.getFirst().lines()).containsExactly(new GoodsLine("G1", 7000, 2),
                new GoodsLine("G2", 3000, 1));
        assertThatThrownBy(() -> canonical.getFirst().lines().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> CounterCampaignContract.canonicalGroups(List.of(
                new MerchantAmount("E1", 1, 2, "S1", List.of(new GoodsLine("G1", Long.MAX_VALUE, 1),
                        new GoodsLine("G1", 1, 1)), false)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CounterCampaignContract.canonicalGroups(List.of(
                new MerchantAmount("E1", 10000, 2, "S1", List.of(new GoodsLine("G1", 10000, 1)), false))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rawOwnerAliasesBindToCanonicalAppliedAndSharesWithoutLiveLookup() {
        var rule = rule("main ", "e1 ", "s1 ");
        var seal = CounterCampaignContract.seal(json, party, groups(), Map.of("E1", "A1"), List.of(rule));
        CounterCampaignContract.requireSelection(json, seal, party, groups(), Map.of("E1", "A1"), selected());
        assertThat(seal.selectedRules().getFirst()).isEqualTo(rule);
        assertThat(seal.groups().getFirst().merchantNo()).isEqualTo("E1");
        assertThat(seal.party().identityRevision()).isNull();
        assertThat(seal.selectedRules().getFirst().thresholdMinor()).isNull();
        assertThat(seal.digest()).matches("[0-9a-f]{64}");
        assertThat(CounterCampaignContract.seal(json, party, groups(), Map.of("E1", "A1"), List.of(rule)))
                .isEqualTo(seal);
        assertThatThrownBy(() -> CounterCampaignContract.requireSelection(json, seal, party, groups(),
                Map.of("E1", "A1"), new Discount(1000, List.of(new MerchantDiscount("e1 ", 1000, "s1 ")),
                        selected().applied()))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void digestAndFrozenChoiceShapeRejectTampering() {
        var seal = CounterCampaignContract.seal(json, party, groups(), Map.of(), List.of(rule("MAIN", "E1", null)));
        assertThatThrownBy(() -> CounterCampaignContract.requireSeal(json, new CounterActivitySeal(1, seal.policy(),
                seal.party(), seal.groups(), seal.requestedChoices(), seal.resolvedChoices(), seal.selectedRules(),
                "0".repeat(64)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CounterCampaignContract.seal(json, party, groups(), Map.of("E1", "NONE"),
                seal.selectedRules())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CounterCampaignContract.seal(json, party, groups(), Map.of("E1", "A2"),
                seal.selectedRules())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CounterCampaignContract.seal(json, party, groups(), Map.of(),
                List.of(rule("MAIN", "E1", null), rule("MAIN", "E1", null))))
                .isInstanceOf(IllegalArgumentException.class);
        var empty = CounterCampaignContract.seal(json, party, groups(), Map.of("E1", "NONE"), List.of());
        CounterCampaignContract.requireSelection(json, empty, party, groups(), Map.of("E1", "NONE"), Discount.none());
    }

    @Test
    void digestUsesExactPositionalTupleIncludingNullableFields() throws Exception {
        var rule = rule("main ", "e1 ", null);
        var seal = CounterCampaignContract.seal(json, party, groups(), Map.of(), List.of(rule));
        Object tuple = Arrays.asList("COUNTER_ACTIVITY_SEAL_V1", 1, "LEGACY_FULL_CUT_V1",
                Arrays.asList("USER", "U1", null, null),
                List.of(Arrays.asList("E1", 10000L, 1, "S1", List.of(Arrays.asList("G1", 10000L, 1)), false)),
                Map.of(), List.of(Arrays.asList("E1", "S1", "AUTO", null)),
                List.of(Arrays.asList("main ", "A1", "e1 ", null, 0L, "FULL_CUT", "RUNNING", 0,
                        null, 1000L, 100L, 200L, List.of(), 1000L, "MERCHANT", 0L)));
        String bytes = json.writeValueAsString(tuple);
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(bytes.getBytes(StandardCharsets.UTF_8)));
        assertThat(seal.digest()).isEqualTo(expected);
    }

    @Test
    void originalPartyGroupChoiceAndAllocationMustMatchExactly() {
        var seal = CounterCampaignContract.seal(json, party, groups(), Map.of("E1", "A1"),
                List.of(rule("MAIN", "E1", null)));
        assertThatThrownBy(() -> CounterCampaignContract.requireSelection(json, seal,
                new CustomerParty("USER", "OTHER", null, (Long) null), groups(), Map.of("E1", "A1"), selected()))
                .isInstanceOf(IllegalArgumentException.class);
        var foreign = List.of(new MerchantAmount("E2", 10000, 1, "S1",
                List.of(new GoodsLine("G1", 10000, 1)), false));
        assertThatThrownBy(() -> CounterCampaignContract.requireSelection(json, seal, party, foreign,
                Map.of("E2", "A1"), selected())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CounterCampaignContract.requireSelection(json, seal, party, groups(),
                Map.of("S1", "A1"), selected())).isInstanceOf(IllegalArgumentException.class);
        for (Discount invalid : List.of(Discount.none(), new Discount(999, selected().shares(), selected().applied()),
                new Discount(1000, selected().shares(), List.of(new AppliedActivity("A2", "E1", 1000, 1,
                        0, null, null, "S1"))),
                new Discount(1000, selected().shares(), List.of(new AppliedActivity("A1", "E1", 999, 1,
                        0, null, null, "S1"))))) {
            assertThatThrownBy(() -> CounterCampaignContract.requireSelection(json, seal, party, groups(),
                    Map.of("E1", "A1"), invalid)).isInstanceOf(IllegalArgumentException.class);
        }
        var named = new Discount(1000, selected().shares(), List.of(
                new AppliedActivity("A1", "E1", 1000, 1, 0, null, "display is not authority", "S1")));
        CounterCampaignContract.requireSelection(json, seal, party, groups(), Map.of("E1", "A1"), named);
        assertThatThrownBy(() -> CounterCampaignContract.seal(json, party, groups(), Map.of("E1", "a1"),
                seal.selectedRules())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void strictGoodsParsingCannotTurnMalformedScopeIntoWholeGroup() {
        assertThat(CounterCampaignRules.goodsNos(json, null)).isEmpty();
        assertThat(CounterCampaignRules.goodsNos(json, "  ")).isEmpty();
        assertThat(CounterCampaignRules.goodsNos(json, "[]")).isEmpty();
        assertThat(CounterCampaignRules.goodsNos(json, "[\"G2\",\"G1\",\"G1\"]"))
                .containsExactly("G1", "G2");
        assertThat(CounterCampaignRules.goodsNos(json, json.writeValueAsString("[\"G1\"]")))
                .containsExactly("G1");
        for (String bad : List.of("null", "{}", "[1]", "[null]", "[\" G1\"]", "[", "[] []")) {
            assertThatThrownBy(() -> CounterCampaignRules.goodsNos(json, bad)).isInstanceOf(BizException.class);
        }
    }

    @Test
    void ownerPredicatesUseFrozenObjectiveFactsOnly() {
        var row = new MktCampaign();
        row.setTenantNo("main ");
        row.setEntityNo("e1 ");
        row.setStoreNo("s1 ");
        row.setCampaignNo("A1");
        row.setType("FULL_CUT");
        row.setStatus("RUNNING");
        row.setDeleted(0);
        row.setVersion(0L);
        row.setStartAt(100L);
        row.setEndAt(200L);
        row.setDiscountMinor(1000L);
        row.setThresholdMinor(null);
        assertThat(CounterCampaignRules.supportedEligible(row, List.of(), groups().getFirst(), 100)).isTrue();
        assertThat(CounterCampaignRules.supportedEligible(row, List.of(), groups().getFirst(), 200)).isTrue();
        assertThat(CounterCampaignRules.supportedEligible(row, List.of(), groups().getFirst(), 201)).isFalse();
        assertThat(CounterCampaignRules.scopedFullCutEffect(row, List.of("G1"), groups().getFirst(), 150)).isTrue();
        assertThat(CounterCampaignRules.scopedFullCutEffect(row, List.of("OTHER"), groups().getFirst(), 150)).isFalse();
        var frozen = CounterCampaignRules.selectedRule(row, List.of(), groups().getFirst(), 150);
        assertThat(frozen.ruleVersion()).isZero();
        assertThat(frozen.entityNo()).isEqualTo("e1 ");
        assertThat(CounterCampaignRules.candidate(row, List.of(), groups().getFirst(), 150).merchantNo())
                .isEqualTo("E1");
        assertThat(CounterCampaignRules.seal(json, party, groups(), Map.of(), List.of(frozen)).selectedRules())
                .containsExactly(frozen);
        row.setThresholdMinor(10001L);
        assertThat(CounterCampaignRules.scopedFullCutEffect(row, List.of("G1"), groups().getFirst(), 150)).isFalse();
        row.setThresholdMinor(null);
        row.setVersion(null);
        assertThatThrownBy(() -> CounterCampaignRules.supportedEligible(row, List.of(), groups().getFirst(), 150))
                .isInstanceOfSatisfying(BizException.class, failure -> assertThat(failure.errorCode())
                        .isEqualTo(ErrorCode.CHECKOUT_DEPENDENCY_UNAVAILABLE));
    }

    @Test
    void mandatoryPriceAndGiftPredicatesIgnoreDiscretionaryChoiceButRequireRelevantValidFacts() {
        var row = new MktCampaign();
        row.setDeleted(0);
        row.setStatus("RUNNING");
        row.setStartAt(100L);
        row.setEndAt(200L);
        row.setType("FLASH");
        row.setFlashPriceMinor(500L);
        assertThat(CounterCampaignRules.mandatoryPriceOrGiftEffect(row, List.of("G1"), groups().getFirst(), 150))
                .isTrue();
        assertThat(CounterCampaignRules.mandatoryPriceOrGiftEffect(row, List.of("OTHER"), groups().getFirst(), 150))
                .isFalse();
        row.setType("BUY_GIFT");
        row.setBuyN(2);
        row.setGiftM(1);
        assertThat(CounterCampaignRules.mandatoryPriceOrGiftEffect(row, List.of("G1"), groups().getFirst(), 150))
                .isTrue();
        assertThat(CounterCampaignRules.mandatoryPriceOrGiftEffect(row, List.of("G1"), groups().getFirst(), 99))
                .isFalse();
        row.setGiftM(null);
        assertThatThrownBy(() -> CounterCampaignRules.mandatoryPriceOrGiftEffect(row, List.of("G1"),
                groups().getFirst(), 150)).isInstanceOf(BizException.class);
    }

    @Test
    void sealAndCandidateCollectionsCannotBeMutatedAfterEvaluation() {
        var sourceGoods = new ArrayList<String>();
        var rule = new CounterRule("MAIN", "A1", "E1", null, 0, "FULL_CUT", "RUNNING", 0,
                null, 1000L, 100L, 200L, sourceGoods, 1000, "MERCHANT", 0);
        sourceGoods.add("OTHER");
        assertThat(rule.goodsNos()).isEmpty();
        var seal = CounterCampaignContract.seal(json, party, groups(), Map.of(), List.of(rule));
        var shares = new ArrayList<>(selected().shares());
        var applied = new ArrayList<>(selected().applied());
        var candidates = new ArrayList<>(applied);
        var result = new CampaignPort.CounterCandidates(candidates, new Discount(1000, shares, applied), seal);
        shares.clear();
        applied.clear();
        candidates.clear();
        assertThat(result.selected()).isEqualTo(selected());
        assertThat(result.candidates()).hasSize(1);
        assertThatThrownBy(() -> result.selected().shares().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.seal().selectedRules().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private void assertChoiceFailure(Map<String, String> choices, ErrorCode code) {
        assertThatThrownBy(() -> CounterCampaignContract.normalizeChoices(groups(), choices))
                .isInstanceOfSatisfying(BizException.class, failure -> assertThat(failure.errorCode()).isEqualTo(code));
    }

    private static List<MerchantAmount> groups() {
        return List.of(new MerchantAmount("E1", 10000, 1, "S1", List.of(new GoodsLine("G1", 10000, 1)), false));
    }

    private static CounterRule rule(String tenant, String entity, String store) {
        return new CounterRule(tenant, "A1", entity, store, 0, "FULL_CUT", "RUNNING", 0,
                null, 1000L, 100L, 200L, List.of(), 1000, "MERCHANT", 0);
    }

    private static Discount selected() {
        return new Discount(1000, List.of(new MerchantDiscount("E1", 1000, "S1")),
                List.of(new AppliedActivity("A1", "E1", 1000, 1, 0, null, null, "S1")));
    }
}
