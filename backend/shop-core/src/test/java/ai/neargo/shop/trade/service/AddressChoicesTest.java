package ai.neargo.shop.trade.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TDD-多地址下单：{@link OrderService.CreateOrderCommand#addressFor(String)} 的逻辑。
 */
@DisplayName("多地址下单：addressFor 地址解析")
class AddressChoicesTest {

    private static OrderService.CreateOrderCommand cmd(String addressId, Map<String, String> addressChoices) {
        return new OrderService.CreateOrderCommand(
                List.of(), "EXPRESS", null, addressId, null, 0L, null, null, null, null, null,
                null, false, null, null, addressChoices);
    }

    @Test
    @DisplayName("AC2: 不传 addressChoices → 全部用全局地址")
    void noOverrides_usesGlobalAddress() {
        var c = cmd("ADDR-GLOBAL", null);
        assertThat(c.addressFor("M0001")).isEqualTo("ADDR-GLOBAL");
        assertThat(c.addressFor("M0002")).isEqualTo("ADDR-GLOBAL");
    }

    @Test
    @DisplayName("AC2: 空 map → 全部用全局地址")
    void emptyOverrides_usesGlobalAddress() {
        var c = cmd("ADDR-GLOBAL", Map.of());
        assertThat(c.addressFor("M0001")).isEqualTo("ADDR-GLOBAL");
    }

    @Test
    @DisplayName("AC4: 有覆盖的商家用覆盖地址，没覆盖的用全局")
    void overriddenMerchant_usesOverrideAddress() {
        var c = cmd("ADDR-GLOBAL", Map.of("M0002", "ADDR-FRIEND"));
        assertThat(c.addressFor("M0001")).as("没覆盖").isEqualTo("ADDR-GLOBAL");
        assertThat(c.addressFor("M0002")).as("覆盖了").isEqualTo("ADDR-FRIEND");
    }

    @Test
    @DisplayName("覆盖值为空串时回退全局")
    void blankOverride_fallsBackToGlobal() {
        var c = cmd("ADDR-GLOBAL", Map.of("M0001", "  "));
        assertThat(c.addressFor("M0001")).isEqualTo("ADDR-GLOBAL");
    }

    @Test
    @DisplayName("AC5: 向后兼容——不传 addressChoices 的老版本行为不变")
    void backwardCompatible_nullChoices() {
        var old = new OrderService.CreateOrderCommand(
                List.of(), "EXPRESS", null, "ADDR-OLD", null, 0L, null, null, null, null, null);
        assertThat(old.addressFor("M0001")).isEqualTo("ADDR-OLD");
        assertThat(old.addressChoices()).isNull();
    }
}
