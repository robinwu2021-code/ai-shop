package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** Exercises the merchant stock API against a disposable MySQL copy. */
@TestPropertySource(properties = "shop.food.local-stock.enabled=true")
class FoodStockManagementHttpNativeIT extends FoodNonPaymentNativeHttpIT {
    @Test
    void merchantInitializesAdjustsAndReadsStockWithIdempotentCommands() throws Exception {
        String token = token();
        String base = "/biz/x/food/stores/" + STORE;
        String createKey = key();
        var create = Map.of("itemKind", "MATERIAL", "name", "验收物料 " + key(),
                "baseUnit", "KG", "trackingMode", "TRACKED", "weighed", 1);
        var item = post(base + "/stock-items", create, token, createKey);
        String itemNo = item.path("itemNo").asString();
        assertThat(itemNo).isNotBlank();
        assertThat(post(base + "/stock-items", create, token, createKey).path("itemNo").asString())
                .isEqualTo(itemNo);
        String initializeKey = key();
        var initial = Map.of("itemNo", itemNo, "trackingMode", "TRACKED", "status", "ACTIVE",
                "initialQty", "5.250000", "reasonCode", "COUNT", "reasonDetail", "期初盘点");
        var balance = post(base + "/stock-balances", initial, token, initializeKey);
        assertThat(new BigDecimal(balance.path("onHand").asString())).isEqualByComparingTo("5.25");
        assertThat(post(base + "/stock-balances", initial, token, initializeKey).path("version").asLong())
                .isEqualTo(balance.path("version").asLong());
        String adjustKey = key();
        var adjustment = Map.of("delta", "-1.125000", "version", balance.path("version").asLong(),
                "reasonCode", "COUNT", "reasonDetail", "盘点调整");
        String path = base + "/stock-balances/" + itemNo + ":adjust";
        var adjusted = post(path, adjustment, token, adjustKey);
        assertThat(new BigDecimal(adjusted.path("onHand").asString())).isEqualByComparingTo("4.125");
        assertThat(post(path, adjustment, token, adjustKey).path("version").asLong())
                .isEqualTo(adjusted.path("version").asLong());
        var actual = ok("GET", base + "/stock-balances/" + itemNo, null, token);
        assertThat(new BigDecimal(actual.path("onHand").asString())).isEqualByComparingTo("4.125");
    }
}
