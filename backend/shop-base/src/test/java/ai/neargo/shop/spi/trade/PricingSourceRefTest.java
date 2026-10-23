package ai.neargo.shop.spi.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PricingSourceRefTest {
    @Test
    void preservesExplicitWholeSourceOrVersionedSubset() {
        PricingSourceRef whole = new PricingSourceRef("FOOD", "CART", "C1", 0, null, null);
        assertThat(whole.scopeNo()).isNull();
        PricingSourceRef subset = new PricingSourceRef("CORE", "UNPAID_ORDER", "O1", 4, "SCOPE1", 0L);
        assertThat(subset.scopeVersion()).isZero();
        assertThat(subset).isNotEqualTo(new PricingSourceRef("CORE", "UNPAID_ORDER", "O1", 4, "SCOPE2", 0L));
    }

    @Test
    void scopeIdentityAndVersionMustAppearTogether() {
        assertThatThrownBy(() -> new PricingSourceRef("FOOD", "CART", "C1", 0, "SCOPE1", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PricingSourceRef("FOOD", "CART", "C1", 0, null, 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidIdentitiesAndNegativeVersions() {
        for (String no : new String[] {null, "", " ", " C1", "C1 ", "C\n1", "C".repeat(33)}) {
            assertThatThrownBy(() -> new PricingSourceRef("FOOD", "CART", no, 0, null, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new PricingSourceRef("FOOD", "CART", "C1", -1, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PricingSourceRef("FOOD", "CART", "C1", 0, "SCOPE1", -1L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
