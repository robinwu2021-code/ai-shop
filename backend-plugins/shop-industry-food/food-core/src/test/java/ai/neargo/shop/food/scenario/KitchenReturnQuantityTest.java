package ai.neargo.shop.food.scenario;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.food.service.impl.KitchenReturnServiceImpl;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Original source ratios preserve fractional component demand without truncation or client task quantities. */
class KitchenReturnQuantityTest {
    @Test void scalesPartialReturnUsingOriginalFrozenComponentQuantity() {
        assertThat(KitchenReturnServiceImpl.quantity(new BigDecimal("1.5"),new BigDecimal("0.6"),
                new BigDecimal("3"))).isEqualByComparingTo("0.3");
    }
    @Test void preservesSmallestSupportedQuantity() {
        assertThat(KitchenReturnServiceImpl.quantity(new BigDecimal("0.000001"),BigDecimal.ONE,
                BigDecimal.ONE)).isEqualByComparingTo("0.000001");
    }
    @Test void rejectsUnrepresentableComponentRatioRatherThanRoundingFoodAway() {
        assertThatThrownBy(() -> KitchenReturnServiceImpl.quantity(BigDecimal.ONE,BigDecimal.ONE,
                new BigDecimal("3"))).isInstanceOf(BizException.class);
    }
    @Test void rejectsNonpositiveSourceAndTargetQuantities() {
        assertThatThrownBy(() -> KitchenReturnServiceImpl.quantity(BigDecimal.ZERO,BigDecimal.ONE,
                BigDecimal.ONE)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> KitchenReturnServiceImpl.quantity(BigDecimal.ONE,BigDecimal.ONE,
                BigDecimal.ZERO)).isInstanceOf(BizException.class);
    }
}
