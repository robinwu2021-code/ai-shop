package ai.neargo.shop.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import ai.neargo.shop.spi.product.CatalogPriceSourceFacts;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort;
import ai.neargo.shop.spi.trade.PricingTaxSettingPort;
import ai.neargo.shop.spi.user.StoreDirectoryPort;

import org.junit.jupiter.api.Test;

import java.util.Map;

class CatalogScopeSourceTest {
    @Test
    void originalScopeTaxAndMarketNeedNoSkuOrProductPriceTable() {
        new CatalogPriceSourcePortTest()
                .run(
                        (context, sql) -> {
                            sql.execute("DROP TABLE prd_sku");
                            sql.execute("DROP TABLE prd_goods");
                            var scope = new CatalogPriceSourcePort.MarketScope("E", "S");
                            var facts = context.getBean(CatalogPriceSourcePort.class).scope(scope);
                            CatalogPriceSourceFacts.validateScopeFacts(
                                    scope, facts, CatalogPriceSourcePortTest.NOW);
                            assertThat(facts.market().currency()).isEqualTo("AED");
                            assertThat(facts.tax()).isNotNull();
                        });
    }

    @Test
    void readonlyOriginalStoreCanProveRemovalScopeButNeverAZeroSkuPrice() {
        new CatalogPriceSourcePortTest()
                .run(
                        (context, sql) -> {
                            when(context.getBean(StoreDirectoryPort.class).cards(any()))
                                    .thenReturn(
                                            Map.of(
                                                    "S",
                                                    new StoreDirectoryPort.StoreCard(
                                                            "S",
                                                            "Store",
                                                            "E",
                                                            null,
                                                            "READONLY",
                                                            true,
                                                            null,
                                                            null,
                                                            null,
                                                            null,
                                                            0,
                                                            0)));
                            var facts =
                                    context.getBean(CatalogPriceSourcePort.class)
                                            .scope(
                                                    new CatalogPriceSourcePort.MarketScope(
                                                            "E", "S"));
                            assertThat(facts.purpose()).isEqualTo("CATALOG_SCOPE");
                            assertThat(facts.tax()).isNotNull();
                        });
    }

    @Test
    void missingOriginalTaxSourceCannotBeFilledWithZero() {
        new CatalogPriceSourcePortTest()
                .run(
                        (context, sql) -> {
                            when(context.getBean(PricingTaxSettingPort.class).resolve(any()))
                                    .thenReturn(null);
                            assertThatThrownBy(
                                            () ->
                                                    context.getBean(CatalogPriceSourcePort.class)
                                                            .scope(
                                                                    new CatalogPriceSourcePort
                                                                            .MarketScope("E", "S")))
                                    .isInstanceOf(RuntimeException.class);
                        });
    }
}
