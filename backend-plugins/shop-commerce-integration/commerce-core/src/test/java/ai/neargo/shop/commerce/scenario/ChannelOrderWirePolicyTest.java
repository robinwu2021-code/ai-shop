package ai.neargo.shop.commerce.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.spi.commerce.ChannelOrderGateway.Document;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class ChannelOrderWirePolicyTest {
    final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    @Test
    void originalJsonRoundTripsWithoutChangingItsShapeOrInferringMoneyAndEnumValues() {
        var document = new ChannelOrderEconomicPolicyTest().original();
        String original = json.writeValueAsString(document);
        assertThat(json.writeValueAsString(json.readValue(original, Document.class)))
                .isEqualTo(original);
        for (var invalid :
                new String[] {
                    original.replace("\"grossExTaxMinor\":100", "\"grossExTaxMinor\":100.9"),
                    original.replace("\"grossExTaxMinor\":100", "\"grossExTaxMinor\":\"100\""),
                    original.replace("\"grossExTaxMinor\":100", "\"grossExTaxMinor\":null"),
                    original.replace("\"grossExTaxMinor\":100,", ""),
                    original.replace("\"quantity\":\"1\"", "\"quantity\":1"),
                    original.replace("\"state\":\"WAITING_ACCEPTANCE\"", "\"state\":0"),
                    original.replace("\"paymentState\":\"UNPAID\"", "\"paymentState\":1"),
                    original.replace("\"fulfillment\":\"INSTANT_DELIVERY\"", "\"fulfillment\":0"),
                    original.substring(0, original.length() - 1) + ",\"paid\":true}"
                }) {
            assertThatThrownBy(() -> json.readValue(invalid, Document.class))
                    .isInstanceOf(RuntimeException.class);
        }
    }

    @Test
    void zeroAmountIsValidOnlyWhenItWasExplicitlyPresentInTheOriginalSource() {
        String original = json.writeValueAsString(new ChannelOrderEconomicPolicyTest().original());
        assertThat(
                        json.readValue(original, Document.class)
                                .lines()
                                .getFirst()
                                .merchantDiscountExTaxMinor())
                .isZero();
        for (var invalid :
                new String[] {
                    original.replace("\"merchantDiscountExTaxMinor\":0,", ""),
                    original.replace("\"merchantChargesMinor\":0,", ""),
                    original.replace("\"merchantChargesMinor\":0", "\"merchantChargesMinor\":null")
                })
            assertThatThrownBy(() -> json.readValue(invalid, Document.class))
                    .isInstanceOf(RuntimeException.class);
    }
}
