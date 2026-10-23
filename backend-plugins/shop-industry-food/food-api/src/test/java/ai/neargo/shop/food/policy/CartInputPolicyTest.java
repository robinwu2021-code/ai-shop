package ai.neargo.shop.food.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.food.dto.CartAccess;
import ai.neargo.shop.food.dto.CartBundleChoiceInput;
import ai.neargo.shop.food.dto.CartSelectionInput;
import ai.neargo.shop.food.dto.OrderLineInput;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class CartInputPolicyTest {
    @Test void strictJsonRejectsSpoofedIdentityNumbersAndFractionalCount() {
        var json = new ObjectMapper();
        String valid = "{\"sourceLineRef\":\"L\",\"menuNo\":\"M\",\"menuItemNo\":\"I\","
                + "\"menuRevisionNo\":\"R\",\"goodsNo\":\"G\",\"skuNo\":\"S\",\"qty\":2,"
                + "\"selections\":[],\"bundleChoices\":[]}";
        assertThat(CartInputPolicy.line(json.readValue(valid, OrderLineInput.class)).qty())
                .isEqualByComparingTo("2");
        for (String input : List.of(valid.replace("\"qty\":2", "\"qty\":\"2\""),
                valid.replace("\"qty\":2", "\"qty\":1.5"), valid.replace("\"skuNo\":\"S\"", "\"skuNo\":12"),
                valid.replace("{", "{\"entityNo\":\"OTHER\","))) {
            assertThatThrownBy(() -> CartInputPolicy.line(json.readValue(input, OrderLineInput.class)))
                    .isInstanceOf(RuntimeException.class);
        }
        assertThat(new CartAccess("SESSION", "private-secret", "BUYER").toString())
                .doesNotContain("private-secret");
    }
    @Test void duplicateSelectionsAndChoiceIdsCannotInflateOrBypassConstraints() {
        var selection = new CartSelectionInput("GROUP", "SPEC_VALUE", "VALUE", BigDecimal.ONE, 0L);
        var choice = new CartBundleChoiceInput("CHOICE", BigDecimal.ONE);
        for (var line : List.of(line(List.of(selection, selection), List.of()),
                line(List.of(), List.of(choice, choice)))) {
            assertThatThrownBy(() -> CartInputPolicy.line(line)).isInstanceOf(RuntimeException.class);
        }
    }
    private OrderLineInput line(List<CartSelectionInput> selections, List<CartBundleChoiceInput> choices) {
        return new OrderLineInput("L", "M", "I", "R", "G", "S", BigDecimal.ONE, null, null, selections, choices);
    }
}
