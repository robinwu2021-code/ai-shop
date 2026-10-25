package ai.neargo.shop.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.trade.policy.IndustryOrderAllocationPolicy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import tools.jackson.databind.ObjectMapper;

import java.util.List;

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
}
