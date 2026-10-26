package ai.neargo.shop.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.pricing.MoneyMinor;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.common.pricing.PricingUnit;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Allocation;
import ai.neargo.shop.trade.policy.IndustryOrderAllocationPolicy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

class IndustryOrderAllocationPolicyTest {
    final ObjectMapper json = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"[]", "{\"allocations\":[],\"evidence\":[{\"owner\":\"original\"}]}"})
    void readsOnlyTheExplicitAllocationArray(String document) {
        assertThat(IndustryOrderAllocationPolicy.read(json, document)).isEqualTo(List.of());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "{\"allocations\":{}}", "{\"evidence\":[]}"})
    void missingOrWrongAllocationShapeFailsClosed(String document) {
        assertThatThrownBy(() -> IndustryOrderAllocationPolicy.read(json, document))
                .isInstanceOf(BizException.class);
    }

    final PricingUnit included =
            new PricingUnit(
                    "L1",
                    "C1",
                    "G1",
                    "S1",
                    "R1",
                    "I1",
                    BigDecimal.ZERO,
                    BigDecimal.ONE,
                    new MoneyMinor("CNY", 0),
                    new MoneyMinor("CNY", 0));
    final Map<String, Object> price = Map.of("kind", "BUNDLE_INCLUDED", "receivableMinor", 0);
    final Allocation allocation =
            new Allocation(
                    "L1",
                    "C1",
                    17,
                    1,
                    0,
                    "CNY",
                    PricingSnapshotHash.of(
                            List.of("INDUSTRY_ITEM_ALLOCATION_V1", "O1", 17, included, price)));

    Object evidence(Object source, Object priceEvidence) {
        return Map.of("allocation", allocation, "source", source, "price", priceEvidence);
    }

    String document(List<Object> evidence) {
        return json.writeValueAsString(
                Map.of("allocations", List.of(allocation), "evidence", evidence));
    }

    @Test
    void acceptsOnlyOriginalIncludedZeroEvidence() {
        IndustryOrderAllocationPolicy.requireIncludedZero(
                json, document(List.of(evidence(included, price))), "O1", allocation);
    }

    @Test
    void rejectsHistoricalAndMissingAndDuplicateEvidence() {
        for (String snapshot :
                List.of(
                        json.writeValueAsString(List.of(allocation)),
                        document(List.of()),
                        document(List.of(evidence(included, price), evidence(included, price))))) {
            assertThatThrownBy(
                            () ->
                                    IndustryOrderAllocationPolicy.requireIncludedZero(
                                            json, snapshot, "O1", allocation))
                    .isInstanceOf(BizException.class);
        }
    }

    @Test
    void rejectsWrongKindAndTamperedSourceAndOtherOrder() {
        var altered =
                new PricingUnit(
                        "L1",
                        "C1",
                        "G2",
                        "S2",
                        "R1",
                        "I1",
                        BigDecimal.ZERO,
                        BigDecimal.ONE,
                        new MoneyMinor("CNY", 0),
                        new MoneyMinor("CNY", 0));
        for (Object entry :
                List.of(
                        evidence(included, Map.of("kind", "GOODS", "receivableMinor", 0)),
                        evidence(altered, price))) {
            assertThatThrownBy(
                            () ->
                                    IndustryOrderAllocationPolicy.requireIncludedZero(
                                            json, document(List.of(entry)), "O1", allocation))
                    .isInstanceOf(BizException.class);
        }
        assertThatThrownBy(
                        () ->
                                IndustryOrderAllocationPolicy.requireIncludedZero(
                                        json,
                                        document(List.of(evidence(included, price))),
                                        "O2",
                                        allocation))
                .isInstanceOf(BizException.class);
    }
}
