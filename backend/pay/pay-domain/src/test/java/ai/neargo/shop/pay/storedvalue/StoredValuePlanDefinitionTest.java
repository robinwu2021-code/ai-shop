package ai.neargo.shop.pay.storedvalue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.spi.storedvalue.StoredValuePlans.Create;
import org.junit.jupiter.api.Test;

class StoredValuePlanDefinitionTest {
    @Test void fixedPaidAndGiftAmountsAreValidatedInMinorUnits() {
        assertThat(new Create("STORE", "充50送10", "CNY", 5000, 1000).totalMinor()).isEqualTo(6000);
        assertThat(new Create("STORE", "普通储值", "CNY", 10000, 0).totalMinor()).isEqualTo(10000);
        assertThatThrownBy(() -> new Create("STORE", "无实收", "CNY", 0, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Create("STORE", "负赠送", "CNY", 100, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Create("STORE", "金额溢出", "CNY", Long.MAX_VALUE, 1)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void aPlanAlwaysBelongsToAStoreAndHasADisplayName() {
        assertThatThrownBy(() -> new Create(null, "方案", "CNY", 100, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Create("STORE", "  ", "CNY", 100, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
