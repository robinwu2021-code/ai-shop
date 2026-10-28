package ai.neargo.shop.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.pay.MarketPort;
import ai.neargo.shop.spi.product.CatalogPriceSourceFacts;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort.MarketScope;
import ai.neargo.shop.spi.trade.PricingTaxSettingPort;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.spi.user.StoreDirectoryPort;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.Optional;

class CatalogMarketSourceTest {
    static final MarketScope SCOPE = new MarketScope("E", "S");

    @Test
    void pausedStoresCanConfigureMarketButStillCannotCaptureSellablePrices() {
        new CatalogPriceSourcePortTest().run((c, sql) -> {
            var stores = c.getBean(StoreDirectoryPort.class);
            var old = stores.cards(List.of("S")).get("S");
            var paused = new StoreDirectoryPort.StoreCard(old.storeNo(), old.storeName(), old.entityNo(), old.logo(),
                    "READONLY", old.isDefault(), old.openHours(), old.address(), old.latE6(), old.lngE6(),
                    old.rating(), old.ratingCount());
            when(stores.cards(List.of("S"))).thenReturn(Map.of("S", paused));
            var source = c.getBean(CatalogPriceSourcePort.class);
            assertThat(source.market(SCOPE).market().market()).isEqualTo("AE");
            assertThatThrownBy(() -> source.capture(CatalogPriceSourcePortTest.REQUEST))
                    .isInstanceOf(BizException.class);
        });
    }

    @Test
    void readsOriginalMarketWithoutSkuPricesOrTax() {
        new CatalogPriceSourcePortTest().run((c, sql) -> {
            sql.update("DELETE FROM prd_sku");
            sql.update("DELETE FROM prd_goods");
            var facts = c.getBean(CatalogPriceSourcePort.class).market(SCOPE);
            CatalogPriceSourceFacts.validateMarketFacts(SCOPE, facts, CatalogPriceSourcePortTest.NOW);
            assertThat(facts.market().market()).isEqualTo("AE");
            assertThat(facts.market().currency()).isEqualTo("AED");
            verifyNoInteractions(c.getBean(PricingTaxSettingPort.class));
        });
    }

    @Test
    void refusesForeignStoresDisabledMarketsAndOuterTransactions() {
        new CatalogPriceSourcePortTest().run((c, sql) -> {
            var source = c.getBean(CatalogPriceSourcePort.class);
            assertThatThrownBy(() -> source.market(new MarketScope("OTHER", "S"))).isInstanceOf(BizException.class);
            when(c.getBean(MarketPort.class).find("AE")).thenReturn(Optional.empty());
            assertThatThrownBy(() -> source.market(SCOPE)).isInstanceOf(BizException.class);
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try { assertThatThrownBy(() -> source.market(SCOPE)).isInstanceOf(IllegalStateException.class); }
            finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
        });
    }

    @Test
    void detectsOriginalMarketChangesBetweenReads() {
        new CatalogPriceSourcePortTest().run((c, sql) -> {
            when(c.getBean(MerchantQueryPort.class).marketOf("E")).thenReturn("AE", "CN");
            assertThatThrownBy(() -> c.getBean(CatalogPriceSourcePort.class).market(SCOPE))
                    .isInstanceOf(BizException.class);
        });
    }

    @Test
    void scopedReceiptAndLifetimeAreRevalidated() {
        new CatalogPriceSourcePortTest().run((c, sql) -> {
            var facts = c.getBean(CatalogPriceSourcePort.class).market(SCOPE);
            assertThatThrownBy(() -> CatalogPriceSourceFacts.validateMarketFacts(
                    new MarketScope("E", "OTHER"), facts, CatalogPriceSourcePortTest.NOW))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> CatalogPriceSourceFacts.validateMarketFacts(
                    SCOPE, facts, CatalogPriceSourcePortTest.NOW.plusSeconds(30))).isInstanceOf(RuntimeException.class);
            var tampered = new CatalogPriceSourcePort.MarketFacts(1, facts.purpose(), SCOPE,
                    facts.market(), facts.verifiedAt(), facts.validUntil(), "bad");
            assertThatThrownBy(() -> CatalogPriceSourceFacts.validateMarketFacts(
                    SCOPE, tampered, CatalogPriceSourcePortTest.NOW)).isInstanceOf(RuntimeException.class);
        });
    }
}
