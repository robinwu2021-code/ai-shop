package ai.neargo.shop.common.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class TaxSettingDefinitionTest {
    @Test
    void roundTripsExplicitZeroAndBothPriceModesWithoutAssumingMissingTaxIsZero() {
        for (int rate : new int[] {0, 500, 10000}) {
            for (var mode : PriceTaxMode.values()) {
                var value = new TaxSettingDefinition(true, rate, mode);
                assertThatNoException().isThrownBy(() -> TaxSettingDefinition.parse(value.canonicalJson()));
                assertThat(TaxSettingDefinition.parse(value.canonicalJson())).isEqualTo(value);
            }
        }
        var inherited = new TaxSettingDefinition(false, null, null);
        assertThatNoException().isThrownBy(() -> TaxSettingDefinition.parse(inherited.canonicalJson()));
        assertThat(TaxSettingDefinition.parse(inherited.canonicalJson())).isEqualTo(inherited);
        assertThat(inherited.rateBp()).isNull();
    }

    @Test
    void rejectsInvalidConstructedRatesAndContradictoryInheritance() {
        for (int rate : new int[] {-1, 10001}) {
            assertThatThrownBy(() -> new TaxSettingDefinition(true, rate, PriceTaxMode.EXCLUSIVE))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new TaxSettingDefinition(true, null, PriceTaxMode.INCLUSIVE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TaxSettingDefinition(true, 500, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TaxSettingDefinition(false, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TaxSettingDefinition(false, null, PriceTaxMode.INCLUSIVE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsCoercionMissingFieldsReviewFlagsAndDuplicateKeys() {
        String valid = "{\"schemaVersion\":1,\"custom\":true,\"rateBp\":500,"
                + "\"priceTaxMode\":\"EXCLUSIVE\"}";
        for (String value : List.of(valid.replace("500", "\"500\""), valid.replace("500", "500.5"),
                valid.replace("500", "null"), valid.replace("true", "\"true\""),
                valid.replace("1,", "2,"), valid.replace("EXCLUSIVE", "OTHER"),
                valid.replace("\"custom\":true,", ""), valid.replace("{", "{\"approved\":true,"),
                valid.replace("{", "{\"rateBp\":1,"), valid + "{}", "null", "{")) {
            assertThatThrownBy(() -> TaxSettingDefinition.parse(value)).as(value)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void hashesTheExactSourceVersionAndValueWithCanonicalObjectOrdering() {
        String source = "{\"priceTaxMode\":\"EXCLUSIVE\",\"rateBp\":500,\"custom\":true,\"schemaVersion\":1}";
        assertThatNoException().isThrownBy(() -> TaxSettingDefinition.parse(source));
        var value = TaxSettingDefinition.parse(source);
        assertThat(value.snapshotHash("MERCHANT:M1", 1)).hasSize(64)
                .isEqualTo(new TaxSettingDefinition(true, 500, PriceTaxMode.EXCLUSIVE)
                        .snapshotHash("MERCHANT:M1", 1))
                .isNotEqualTo(value.snapshotHash("MERCHANT:M2", 1))
                .isNotEqualTo(value.snapshotHash("MERCHANT:M1", 2))
                .isNotEqualTo(new TaxSettingDefinition(true, 0, PriceTaxMode.EXCLUSIVE)
                        .snapshotHash("MERCHANT:M1", 1));
        assertThatThrownBy(() -> value.snapshotHash("MERCHANT:M1", -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> value.snapshotHash("MERCHANT:M1", 9007199254740992L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
