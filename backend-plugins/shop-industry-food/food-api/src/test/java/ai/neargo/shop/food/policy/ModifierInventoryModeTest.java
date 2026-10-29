package ai.neargo.shop.food.policy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModifierInventoryModeTest {
    @Test
    void missingModePreservesLegacyInterpretation() {
        assertThat(ModifierInventoryMode.resolve("COMBO", "SKU", null)).isEqualTo("SALES_SKU");
        assertThat(ModifierInventoryMode.resolve("COMBO", null, null)).isEqualTo("COMPONENTS");
        assertThat(ModifierInventoryMode.resolve("ADDON", "SKU", null)).isEqualTo("SALES_SKU");
        assertThat(ModifierInventoryMode.resolve("ADDON", null, null)).isEqualTo("NONE");
    }

    @Test
    void legacyModifierDataKeepsTheOriginalSerializedShape() {
        var json = tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build();
        var legacy = new ai.neargo.shop.food.dto.ModifierConfigVO.Data("name", "SKU", null,
                java.util.List.of());
        var encoded = json.writeValueAsString(legacy);
        assertThat(json.readTree(encoded).has("inventoryMode")).isFalse();
        assertThat(json.readValue(encoded, ai.neargo.shop.food.dto.ModifierConfigVO.Data.class)
                .inventoryMode()).isNull();
        var explicit = new ai.neargo.shop.food.dto.ModifierConfigVO.Data("name", "SKU", null,
                java.util.List.of(), "COMPONENTS");
        assertThat(json.readTree(json.writeValueAsString(explicit)).path("inventoryMode").asString())
                .isEqualTo("COMPONENTS");
    }

    @Test
    void legacyCommandJsonDoesNotGainANullModeField() {
        var json = tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build();
        var oldCreate = new ai.neargo.shop.food.dto.CreateModifierReq("name", "ADDON", "SKU", java.util.List.of());
        var oldUpdate = new ai.neargo.shop.food.dto.UpdateModifierReq("name", "ADDON", "SKU", java.util.List.of(), 1L);
        assertThat(json.readTree(json.writeValueAsString(oldCreate)).has("inventoryMode")).isFalse();
        assertThat(json.readTree(json.writeValueAsString(oldUpdate)).has("inventoryMode")).isFalse();
        var explicit = new ai.neargo.shop.food.dto.CreateModifierReq("name", "ADDON", "SKU",
                java.util.List.of(), "SALES_SKU");
        assertThat(json.readTree(json.writeValueAsString(explicit)).path("inventoryMode").asString())
                .isEqualTo("SALES_SKU");
    }

    @Test
    void explicitComponentExpansionIsReservedForCombos() {
        assertThat(ModifierInventoryMode.resolve("COMBO", "PRICE_SKU", "COMPONENTS"))
                .isEqualTo("COMPONENTS");
        assertThatThrownBy(() -> ModifierInventoryMode.resolve("ADDON", "SKU", "COMPONENTS"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModifierInventoryMode.resolve("COMBO", null, "SALES_SKU"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModifierInventoryMode.resolve("COMBO", "SKU", "UNKNOWN"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
