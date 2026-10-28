package ai.neargo.shop.food.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PublicationCurrencyCompatibilityTest {
    @Test void sixLegacyKindsRetainExactPrechangeBytesHashesAndValidationResults() throws Exception {
        try (var input = getClass().getResourceAsStream("/catalog-currency-legacy-golden.json")) {
            assertThat(input).isNotNull();
            var golden = CurrencyPublicationFixtures.JSON.readValue(input, Map.class);
            assertThat(golden.get("baseCommit")).isEqualTo("aa48f65750e0e24d55e1c03b0835a4024e849b02");
            var documents = (Map<?, ?>) golden.get("documents");
            var hashes = (Map<?, ?>) golden.get("hashes");
            for (String kind : List.of("MODIFIER", "OPTION_GROUP", "BUNDLE", "RECIPE", "MENU", "STATION")) {
                String document = CurrencyPublicationFixtures.freeze(CurrencyPublicationFixtures.legacy(kind));
                assertThat(document).as(kind).isEqualTo(documents.get(kind)).doesNotContain("marketContext");
                assertThat(PublicationDocuments.hash(CurrencyPublicationFixtures.JSON, document))
                        .as(kind).isEqualTo(hashes.get(kind));
                int expected = switch (kind) {
                    case "MODIFIER", "OPTION_GROUP" -> 1;
                    case "BUNDLE" -> 2;
                    case "RECIPE" -> 3;
                    case "MENU" -> 4;
                    default -> 5;
                };
                assertThat(PublicationDocuments.validate(CurrencyPublicationFixtures.JSON, kind,
                        CurrencyPublicationFixtures.number(kind), document)).isEqualTo(expected);
            }
        }
    }

    @Test void oldFormatsCannotCarryNewMarketMeaningUnderTheirLegacySchemaNumbers() {
        var context = CurrencyMarketFixtures.context("AE", "AED", 2);
        for (String kind : List.of("MODIFIER", "OPTION_GROUP", "BUNDLE", "RECIPE", "MENU")) {
            int schema = switch (kind) {
                case "MODIFIER", "OPTION_GROUP" -> 1;
                case "BUNDLE" -> 2;
                case "RECIPE" -> 3;
                default -> 4;
            };
            String document = CurrencyPublicationFixtures.freeze(CurrencyMarketFixtures.upgrade(kind, schema, context));
            assertThatThrownBy(() -> PublicationDocuments.validate(CurrencyPublicationFixtures.JSON, kind,
                    CurrencyPublicationFixtures.number(kind), document))
                    .as(kind).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test void stationKeepsFormatFiveAndCannotBorrowTheSchemaSixFamily() {
        String old = CurrencyPublicationFixtures.freeze(CurrencyPublicationFixtures.station());
        assertThatThrownBy(() -> PublicationDocuments.validate(CurrencyPublicationFixtures.JSON, "STATION", "STATION",
                old.replace("\"contentSchema\":5", "\"contentSchema\":6")))
                .isInstanceOf(IllegalStateException.class);
    }
}
