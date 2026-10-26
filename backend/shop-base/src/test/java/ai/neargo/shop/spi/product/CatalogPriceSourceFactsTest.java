package ai.neargo.shop.spi.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.pricing.PriceTaxMode;
import ai.neargo.shop.common.pricing.TaxSettingDefinition;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort.Facts;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort.Market;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort.Price;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort.Request;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort.TaxFact;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort.Versions;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;

class CatalogPriceSourceFactsTest {
    static final Instant NOW = Instant.parse("2026-10-26T08:00:00Z");
    static final Request REQUEST = new Request("E", "S", List.of("SKU"));
    final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    Facts facts() {
        var market = new Market("AE", "AED", 2);
        var definition = new TaxSettingDefinition(true, 500, PriceTaxMode.EXCLUSIVE);
        var tax =
                new TaxFact(
                        "MERCHANT_CUSTOM",
                        "E",
                        2L,
                        2L,
                        500,
                        "EXCLUSIVE",
                        definition.snapshotHash("MERCHANT_CUSTOM:E", 2));
        var price =
                new Price(
                        "SKU",
                        "G",
                        "KNOWN",
                        900L,
                        "ENTITY_SKU",
                        new Versions(3L, 8L, null, null),
                        null);
        var row =
                new Price(
                        "SKU",
                        "G",
                        "KNOWN",
                        900L,
                        "ENTITY_SKU",
                        price.versions(),
                        CatalogPriceSourceFacts.revision(REQUEST, market, price));
        return seal(
                new Facts(
                        1,
                        "CATALOG_BASE",
                        REQUEST,
                        market,
                        tax,
                        List.of(row),
                        NOW,
                        NOW.plusSeconds(30),
                        null));
    }

    static Facts seal(Facts f) {
        return new Facts(
                f.schemaVersion(),
                f.purpose(),
                f.request(),
                f.market(),
                f.tax(),
                f.prices(),
                f.verifiedAt(),
                f.validUntil(),
                CatalogPriceSourceFacts.receipt(f));
    }

    @Test
    void strictWireRoundTripPreservesOptionalMissingOverlayVersionsAndOriginalTaxMode() {
        var original = facts();
        var received = json.readValue(json.writeValueAsString(original), Facts.class);
        CatalogPriceSourceFacts.validate(REQUEST, received, NOW);
        assertThat(received).isEqualTo(original);
        assertThat(received.prices().getFirst().versions().storePrice()).isNull();
        assertThat(CatalogPriceSourceFacts.taxSnapshot(REQUEST, received.tax()).priceTaxMode())
                .isEqualTo(PriceTaxMode.EXCLUSIVE);
    }

    @Test
    void rejectsRoundedStringsAndOverflowingScalarsBeforeTheyBecomeValidFacts() {
        String valid = json.writeValueAsString(facts());
        for (String key :
                List.of(
                        "schemaVersion",
                        "currencyScale",
                        "rateBp",
                        "goods",
                        "sku",
                        "managedVersion",
                        "sourceVersion",
                        "amountMinor")) {
            String target = "\"" + key + "\":\\d+";
            for (String invalid : List.of("0.5", "\"1\"", "9223372036854775808")) {
                String malformed = valid.replaceFirst(target, "\"" + key + "\":" + invalid);
                assertThat(malformed).isNotEqualTo(valid);
                assertThatThrownBy(() -> json.readValue(malformed, Facts.class))
                        .isInstanceOf(RuntimeException.class);
            }
        }
        for (String invalid :
                List.of(
                        valid.substring(0, valid.length() - 1) + ",\"ok\":true}",
                        valid.replace("\"storePrice\":null", "\"storePrice\":1.2"),
                        valid.replace("\"entityNo\":\"E\"", "\"entityNo\":1")))
            assertThatThrownBy(() -> json.readValue(invalid, Facts.class))
                    .isInstanceOf(RuntimeException.class);
    }

    @Test
    void sourceRevisionAndReceiptCannotBeReplayedAcrossScopeOrMarket() {
        var f = facts();
        var other = new Request("OTHER", "S", List.of("SKU"));
        assertThatThrownBy(() -> CatalogPriceSourceFacts.validate(other, f, NOW))
                .isInstanceOf(IllegalStateException.class);
        var wrong =
                seal(
                        new Facts(
                                1,
                                f.purpose(),
                                REQUEST,
                                new Market("CN", "CNY", 2),
                                f.tax(),
                                f.prices(),
                                f.verifiedAt(),
                                f.validUntil(),
                                null));
        assertThatThrownBy(() -> CatalogPriceSourceFacts.validate(REQUEST, wrong, NOW))
                .isInstanceOf(IllegalStateException.class);
        var row = f.prices().getFirst();
        assertThat(
                        CatalogPriceSourceFacts.revision(
                                new Request("E", "S", List.of("SKU", "ANOTHER")), f.market(), row))
                .isEqualTo(row.sourceRevision());
    }

    @Test
    void recomputedOuterReceiptCannotHideMissingRowsWrongTaxOrVersions() {
        var f = facts();
        var badTax =
                new TaxFact(
                        "MERCHANT_CUSTOM",
                        "FOREIGN",
                        2L,
                        2L,
                        500,
                        "EXCLUSIVE",
                        f.tax().contentHash());
        for (var bad :
                List.of(
                        seal(
                                new Facts(
                                        1,
                                        f.purpose(),
                                        REQUEST,
                                        f.market(),
                                        f.tax(),
                                        List.of(),
                                        NOW,
                                        NOW.plusSeconds(30),
                                        null)),
                        seal(
                                new Facts(
                                        1,
                                        f.purpose(),
                                        REQUEST,
                                        f.market(),
                                        badTax,
                                        f.prices(),
                                        NOW,
                                        NOW.plusSeconds(30),
                                        null)),
                        seal(
                                new Facts(
                                        1,
                                        f.purpose(),
                                        REQUEST,
                                        new Market("AE", "AED", 3),
                                        f.tax(),
                                        f.prices(),
                                        NOW,
                                        NOW.plusSeconds(30),
                                        null)),
                        seal(
                                new Facts(
                                        1,
                                        "CHECKOUT",
                                        REQUEST,
                                        f.market(),
                                        f.tax(),
                                        f.prices(),
                                        NOW,
                                        NOW.plusSeconds(30),
                                        null)))) {
            assertThatThrownBy(() -> CatalogPriceSourceFacts.validate(REQUEST, bad, NOW))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void expiryFutureAndOverlongProofsRemainUnavailable() {
        var f = facts();
        for (var bad :
                List.of(
                        seal(
                                new Facts(
                                        1,
                                        f.purpose(),
                                        REQUEST,
                                        f.market(),
                                        f.tax(),
                                        f.prices(),
                                        NOW.minusSeconds(30),
                                        NOW,
                                        null)),
                        seal(
                                new Facts(
                                        1,
                                        f.purpose(),
                                        REQUEST,
                                        f.market(),
                                        f.tax(),
                                        f.prices(),
                                        NOW.plusSeconds(1),
                                        NOW.plusSeconds(30),
                                        null)),
                        seal(
                                new Facts(
                                        1,
                                        f.purpose(),
                                        REQUEST,
                                        f.market(),
                                        f.tax(),
                                        f.prices(),
                                        NOW,
                                        NOW.plusSeconds(31),
                                        null)))) {
            assertThatThrownBy(() -> CatalogPriceSourceFacts.validate(REQUEST, bad, NOW))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
