package ai.neargo.shop.spi.storedvalue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.storedvalue.StoredValueWallets.ExecutionScope;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class StoredValueWalletsCompatibilityTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ExecutionScope legacy = new ExecutionScope("MAIN", "A", null, "E", "S", 1,
            null, null, -1, "CNY", -1);

    @Test void omittedLegacyProtocolRoundTripsWithoutChangingFrozenJsonOrHash() {
        String frozen = json.writeValueAsString(legacy);
        assertThat(frozen).doesNotContain("coreFinanceProtocol");
        var restored = json.readValue(frozen, ExecutionScope.class);
        assertThat(restored).isEqualTo(legacy);
        assertThat(restored.coreFinanceProtocol()).isZero();
        assertThat(json.writeValueAsString(restored)).isEqualTo(frozen);
        assertThat(PricingSnapshotHash.of(restored)).isEqualTo(PricingSnapshotHash.of(legacy));
    }

    @Test void explicitZeroAndNonnegativeProtocolsRetainTheirExactVersion() {
        String frozen = json.writeValueAsString(legacy);
        for (int version : new int[] {0, 1, 2, 3, Integer.MAX_VALUE}) {
            var restored = json.readValue(frozen.substring(0, frozen.length() - 1)
                    + ",\"coreFinanceProtocol\":" + version + "}", ExecutionScope.class);
            assertThat(restored.coreFinanceProtocol()).isEqualTo(version);
            assertThat(json.readValue(json.writeValueAsString(restored), ExecutionScope.class))
                    .isEqualTo(restored);
        }
    }

    @Test void invalidProtocolAndOtherMissingPrimitiveFactsRemainStrict() {
        String frozen = json.writeValueAsString(legacy);
        for (String value : new String[] {"null", "-1", "1.5", "\"2\"", "2147483648"}) {
            assertThatThrownBy(() -> json.readValue(frozen.substring(0, frozen.length() - 1)
                    + ",\"coreFinanceProtocol\":" + value + "}", ExecutionScope.class))
                    .as("protocol %s", value).isInstanceOf(RuntimeException.class);
        }
        assertThatThrownBy(() -> json.readValue(frozen.replace("\"storeRevision\":1,", ""),
                ExecutionScope.class)).isInstanceOf(RuntimeException.class);
    }
}
