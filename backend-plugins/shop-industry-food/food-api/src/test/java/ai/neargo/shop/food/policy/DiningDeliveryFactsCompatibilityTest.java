package ai.neargo.shop.food.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.food.dto.DiningDeliveryContracts.DeliverySnapshot;
import ai.neargo.shop.food.dto.DiningPriceFacts;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;

/**
 * Legacy pickup hashes stay unchanged while every delivery address/instruction mutation changes
 * source identity. Execution deferred.
 */
class DiningDeliveryFactsCompatibilityTest {
    @Test
    void absentDeliveryPreservesOriginalPayloadJsonAndCanonicalHash() {
        var payload =
                new DiningPriceFacts.Payload(
                        "E", null, null, List.of(), List.of(), List.of(), List.of(), List.of(),
                        null, "CNY", 0L);
        var legacy = new LinkedHashMap<String, Object>();
        legacy.put("entityNo", "E");
        legacy.put("subjectNo", null);
        legacy.put("cart", null);
        legacy.put("menus", List.of());
        legacy.put("identities", List.of());
        legacy.put("offers", List.of());
        legacy.put("restrictions", List.of());
        legacy.put("units", List.of());
        legacy.put("composition", null);
        legacy.put("currency", "CNY");
        legacy.put("amountMinor", 0L);
        assertThat(JsonMapper.builder().build().valueToTree(payload).has("delivery")).isFalse();
        assertThat(PricingSnapshotHash.of(payload)).isEqualTo(PricingSnapshotHash.of(legacy));
    }

    @Test
    void optionalCoordinateFieldsPreserveOldDeliveryJsonAndRejectUnpairedFacts() {
        var old =
                new DeliverySnapshot("E", "S", "C", 1, 0, "Receiver", "12345678", "Address", null);
        var tree = JsonMapper.builder().build().valueToTree(old);
        assertThat(tree.has("latE6")).isFalse();
        assertThat(tree.has("lngE6")).isFalse();
        assertThatThrownBy(
                        () ->
                                new DeliverySnapshot(
                                        "E",
                                        "S",
                                        "C",
                                        1,
                                        0,
                                        "Receiver",
                                        "12345678",
                                        "Address",
                                        null,
                                        0,
                                        180000001))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void coordinatesAndInstructionsChangeHashEvenWhenAllVersionsRemainIdentical() {
        var first =
                new DeliverySnapshot(
                        "E",
                        "S",
                        "C",
                        1,
                        0,
                        "Receiver",
                        "12345678",
                        "Address",
                        "door",
                        22301234,
                        114123456);
        var point =
                new DeliverySnapshot(
                        "E",
                        "S",
                        "C",
                        1,
                        0,
                        "Receiver",
                        "12345678",
                        "Address",
                        "door",
                        22301235,
                        114123456);
        var instruction =
                new DeliverySnapshot(
                        "E",
                        "S",
                        "C",
                        1,
                        0,
                        "Receiver",
                        "12345678",
                        "Address",
                        "desk",
                        22301234,
                        114123456);
        assertThat(PricingSnapshotHash.of(first)).isNotEqualTo(PricingSnapshotHash.of(point));
        assertThat(PricingSnapshotHash.of(first)).isNotEqualTo(PricingSnapshotHash.of(instruction));
    }

    @Test
    void persistedDeliveryFactsParticipateInCanonicalSourceHash() {
        var first =
                new DeliverySnapshot(
                        "E", "S", "C", 1, 0, "Receiver", "12345678", "Address A", "door");
        var changed =
                new DeliverySnapshot(
                        "E", "S", "C", 1, 0, "Receiver", "12345678", "Address B", "door");
        var a =
                new DiningPriceFacts.Payload(
                        "E", null, null, List.of(), List.of(), List.of(), List.of(), List.of(),
                        null, "CNY", 0L, first);
        var b =
                new DiningPriceFacts.Payload(
                        "E", null, null, List.of(), List.of(), List.of(), List.of(), List.of(),
                        null, "CNY", 0L, changed);
        assertThat(PricingSnapshotHash.of(a)).isNotEqualTo(PricingSnapshotHash.of(b));
        assertThat(
                        JsonMapper.builder()
                                .build()
                                .valueToTree(a)
                                .path("delivery")
                                .path("address")
                                .asString())
                .isEqualTo("Address A");
    }
}
