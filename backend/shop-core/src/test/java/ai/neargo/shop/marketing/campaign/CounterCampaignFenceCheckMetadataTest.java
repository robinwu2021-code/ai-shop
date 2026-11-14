package ai.neargo.shop.marketing.campaign;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
class CounterCampaignFenceCheckMetadataTest {
    @Test void mysqlEscapedMetadataDelimitersRetainTheSameExactAllowedValues() {
        assertThat(CounterCampaignFence.check("(`scope_kind` in (_utf8mb4\\'PLATFORM\\',_utf8mb4\\'ENTITY\\'))"))
                .isEqualTo(CounterCampaignFence.check("scope_kind IN ('PLATFORM','ENTITY')"));
    }
    @Test void differentValuesAndAdditionalPredicatesCannotBeNormalizedIntoTheContract() {
        var expected=CounterCampaignFence.check("scope_kind IN ('PLATFORM','ENTITY')");
        assertThat(CounterCampaignFence.check("scope_kind IN (_utf8mb4\\'OTHER\\',_utf8mb4\\'ENTITY\\')")).isNotEqualTo(expected);
        assertThat(CounterCampaignFence.check("scope_kind IN (_utf8mb4\\'PLATFORM\\',_utf8mb4\\'ENTITY\\') OR 1=1")).isNotEqualTo(expected);
    }
}
