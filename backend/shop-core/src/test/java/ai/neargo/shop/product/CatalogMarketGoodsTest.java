package ai.neargo.shop.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.common.data.scope.DataScopeSpec;
import ai.neargo.shop.auth.ScopeDim;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import ai.neargo.shop.product.port.GoodsCatalogQueryPortImpl;
import ai.neargo.shop.spi.pay.MarketPort;
import ai.neargo.shop.spi.product.CatalogMarketContracts.GoodsQuery;
import ai.neargo.shop.spi.product.CatalogMarketContracts.SkuQuery;
import ai.neargo.shop.spi.product.CatalogMarketFacts;
import ai.neargo.shop.spi.product.CatalogPriceSourceFacts;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort;
import ai.neargo.shop.spi.trade.PricingTaxSettingPort;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

class CatalogMarketGoodsTest {
    private static final GoodsQuery GOODS = new GoodsQuery("E", "S", "G", "AE");
    private static final SkuQuery SKU = new SkuQuery("E", "S", "SKU", "AE");

    static GoodsCatalogQueryPort port(AssertableApplicationContext c, UnaryOperator<CatalogPriceSourcePort> wrap) {
        return new GoodsCatalogQueryPortImpl(c.getBean(GoodsMapper.class), c.getBean(SkuMapper.class), () -> null,
                () -> wrap.apply(c.getBean(CatalogPriceSourcePort.class)), c.getBean(PlatformTransactionManager.class),
                Clock.fixed(CatalogPriceSourcePortTest.NOW, ZoneOffset.UTC));
    }

    @Test void normalSpringWiringResolvesOriginalMarketLazilyAndPropagatesItsOutage() {
        var goods = mock(GoodsMapper.class);
        var skus = mock(SkuMapper.class);
        var owner = mock(CatalogPriceSourcePort.class);
        var failure = new IllegalStateException("original market unavailable");
        var scope = new CatalogPriceSourcePort.MarketScope("E", "S");
        when(owner.market(scope)).thenThrow(failure);
        new ApplicationContextRunner().withUserConfiguration(GoodsCatalogQueryPortImpl.class)
                .withBean(GoodsMapper.class, () -> goods).withBean(SkuMapper.class, () -> skus)
                .withBean("transactionManager", PlatformTransactionManager.class,
                        () -> mock(PlatformTransactionManager.class))
                .withBean(CatalogPriceSourcePort.class, () -> owner).run(c -> {
                    assertThat(c).hasNotFailed();
                    assertThatThrownBy(() -> c.getBean(GoodsCatalogQueryPort.class).getCatalogGoods(GOODS))
                            .isSameAs(failure);
                    verify(owner).market(scope);
                    verifyNoInteractions(goods, skus);
                });
    }

    @Test void actualMarketSelectsMetadataWithoutTaxPriceInventoryOrCnFallback() {
        new CatalogPriceSourcePortTest().run((c, sql) -> {
            var p = port(c, UnaryOperator.identity());
            var g = p.getCatalogGoods(GOODS);
            CatalogMarketFacts.validate(GOODS, g, CatalogPriceSourcePortTest.NOW);
            assertThat(g.marketFacts().market().currency()).isEqualTo("AED");
            assertThat(g.goods().skus()).containsExactly(new GoodsCatalogQueryPort.SkuFact("SKU", null, 8));
            var s = p.getCatalogSku(SKU);
            CatalogMarketFacts.validate(SKU, s, CatalogPriceSourcePortTest.NOW);
            assertThat(s.sku().version()).isEqualTo(8);
            assertThat(s.goodsVersion()).isEqualTo(3);
            assertThat(JsonMapper.builder().build().writeValueAsString(g))
                    .doesNotContain("amountMinor", "stock", "tax", "priceMinor");
            verifyNoInteractions(c.getBean(PricingTaxSettingPort.class));
            sql.update("DELETE FROM prd_sku WHERE market='AE'");
            assertThat(p.getCatalogGoods(GOODS).goods().skus()).isEmpty();
            code(() -> p.getCatalogSku(SKU), ErrorCode.NOT_FOUND);
        });
    }

    @Test void oldCnMethodsAndNewActualCnMethodKeepOriginalVersions() {
        new CatalogPriceSourcePortTest().run((c, sql) -> {
            var p = port(c, UnaryOperator.identity());
            assertThat(p.getGoods("E", "S", "G").skus().getFirst().version()).isEqualTo(7);
            assertThat(p.getSku("E", "S", "SKU").version()).isEqualTo(7);
            when(c.getBean(MerchantQueryPort.class).marketOf("E")).thenReturn("CN");
            var q = new GoodsQuery("E", "S", "G", "CN");
            assertThat(p.getCatalogGoods(q).goods()).isEqualTo(p.getGoods("E", "S", "G"));
            code(() -> p.getCatalogGoods(GOODS), ErrorCode.CONFLICT);
        });
    }

    @Test void pausedStoresCanReadManagementMetadataAndUnsellableGoodsRemainReadable() {
        new CatalogPriceSourcePortTest().run((c, sql) -> {
            when(c.getBean(StoreDirectoryPort.class).cards(List.of("S"))).thenReturn(Map.of("S",
                    new StoreDirectoryPort.StoreCard("S", "Store", "E", null, "READONLY", true,
                            null, null, null, null, 0, 0)));
            sql.update("UPDATE prd_goods SET on_sale=FALSE,audit_status='PENDING'");
            var p = port(c, UnaryOperator.identity());
            assertThat(p.getCatalogGoods(GOODS).goods().baseOnSale()).isFalse();
            assertThat(p.getCatalogSku(SKU).sku().skuNo()).isEqualTo("SKU");
            verifyNoInteractions(c.getBean(PricingTaxSettingPort.class));
        });
    }

    @Test void scopeDeletedSkusAndDeletedParentsAreCheckedByOriginalOwners() {
        new CatalogPriceSourcePortTest().run((c, sql) -> {
            var p = port(c, UnaryOperator.identity());
            for (var q : List.of(new GoodsQuery("OTHER", "S", "G", null),
                    new GoodsQuery("E", "OTHER", "G", null))) {
                code(() -> p.getCatalogGoods(q), ErrorCode.FORBIDDEN);
            }
            code(() -> p.getCatalogGoods(new GoodsQuery("E", "S", "MISSING", null)), ErrorCode.NOT_FOUND);
            sql.update("UPDATE prd_sku SET deleted=1 WHERE market='AE'");
            code(() -> p.getCatalogSku(SKU), ErrorCode.NOT_FOUND);
            sql.update("UPDATE prd_sku SET deleted=0");
            sql.update("UPDATE prd_goods SET store_no='OTHER'");
            code(() -> p.getCatalogSku(SKU), ErrorCode.NOT_FOUND);
            sql.update("UPDATE prd_goods SET store_no='S',deleted=1");
            code(() -> p.getCatalogGoods(GOODS), ErrorCode.NOT_FOUND);
            code(() -> p.getCatalogSku(SKU), ErrorCode.NOT_FOUND);
        });
    }

    @Test void sqlEquivalentButNonExactSkuOwnershipCannotEnterMarketGoodsEvidence() {
        for (var variant : Map.of("entity_no", "e", "goods_no", "g", "market", "ae").entrySet()) {
            new CatalogPriceSourcePortTest().run((c, sql) -> {
                sql.execute("ALTER TABLE prd_sku ALTER COLUMN " + variant.getKey() + " VARCHAR_IGNORECASE(32)");
                sql.update("UPDATE prd_sku SET " + variant.getKey() + "=? WHERE id=2", variant.getValue());
                assertThat(sql.queryForObject("SELECT COUNT(*) FROM prd_sku WHERE entity_no='E'"
                        + " AND goods_no='G' AND market='AE'", Integer.class)).isEqualTo(1);
                code(() -> port(c, UnaryOperator.identity()).getCatalogGoods(GOODS), ErrorCode.CONFLICT);
            });
        }
    }

    @Test void originalMarketChangeBetweenMetadataReadsIsRejected() {
        new CatalogPriceSourcePortTest().run((c, sql) -> {
            when(c.getBean(MerchantQueryPort.class).marketOf("E")).thenReturn("AE", "AE", "CN", "CN");
            code(() -> port(c, UnaryOperator.identity()).getCatalogGoods(new GoodsQuery("E", "S", "G", null)),
                    ErrorCode.CONFLICT);
        });
    }

    @Test void metadataSkuAndParentVersionChangesAreDetectedOutsideTransactions() {
        for (String mutation : List.of("UPDATE prd_sku SET version=version+1 WHERE market='AE'",
                "UPDATE prd_goods SET version=version+1")) {
            new CatalogPriceSourcePortTest().run((c, sql) -> {
                var count = new AtomicInteger();
                var p = port(c, original -> new CatalogPriceSourcePort() {
                    @Override public MarketFacts market(MarketScope scope) {
                        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                        var result = original.market(scope);
                        if (count.incrementAndGet() == 2) sql.update(mutation);
                        return result;
                    }
                });
                code(() -> p.getCatalogSku(SKU), ErrorCode.CONFLICT);
            });
        }
    }

    @Test void goodsMetadataChangesAreDetectedEvenWithoutVersionIncrement() {
        new CatalogPriceSourcePortTest().run((c, sql) -> {
            var count = new AtomicInteger();
            var p = port(c, original -> new CatalogPriceSourcePort() {
                @Override public MarketFacts market(MarketScope scope) {
                    var result = original.market(scope);
                    if (count.incrementAndGet() == 2) sql.update("UPDATE prd_goods SET title='changed'");
                    return result;
                }
            });
            code(() -> p.getCatalogGoods(GOODS), ErrorCode.CONFLICT);
        });
    }

    @Test void missingDisabledOwnersOuterTransactionsAndMalformedQueriesFailClosed() {
        new CatalogPriceSourcePortTest().run((c, sql) -> {
            var p = port(c, original -> null);
            code(() -> p.getCatalogGoods(GOODS), ErrorCode.PRICING_OWNER_UNSUPPORTED);
            var live = port(c, UnaryOperator.identity());
            when(c.getBean(MarketPort.class).find("AE")).thenReturn(Optional.empty());
            code(() -> live.getCatalogSku(SKU), ErrorCode.PRICING_OWNER_UNSUPPORTED);
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try { code(() -> live.getCatalogGoods(GOODS), ErrorCode.CONFLICT); }
            finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
            code(() -> live.getCatalogGoods(new GoodsQuery(" E", "S", "G", "AE")), ErrorCode.BAD_REQUEST);
            code(() -> live.getCatalogSku(new SkuQuery("E", "S", "SKU", "ae")), ErrorCode.BAD_REQUEST);
        });
    }

    @Test void originalReceiptLifetimeCapsMetadataAndExpiredFactsAreRejected() {
        new CatalogPriceSourcePortTest().run((c, sql) -> {
            var p = port(c, original -> new CatalogPriceSourcePort() {
                @Override public MarketFacts market(MarketScope scope) {
                    var f = original.market(scope);
                    return CatalogPriceSourceFacts.sealMarket(new MarketFacts(1, f.purpose(), scope, f.market(),
                            f.verifiedAt(), f.verifiedAt().plusSeconds(5), null));
                }
            });
            assertThat(p.getCatalogGoods(GOODS).validUntil())
                    .isEqualTo(CatalogPriceSourcePortTest.NOW.plusSeconds(5));
            assertThat(p.getCatalogSku(SKU).validUntil()).isEqualTo(CatalogPriceSourcePortTest.NOW.plusSeconds(5));
            var expired = port(c, original -> new CatalogPriceSourcePort() {
                @Override public MarketFacts market(MarketScope scope) {
                    var f = original.market(scope);
                    return CatalogPriceSourceFacts.sealMarket(new MarketFacts(1, f.purpose(), scope, f.market(),
                            f.verifiedAt().minusSeconds(31), f.verifiedAt().minusSeconds(1), null));
                }
            });
            code(() -> expired.getCatalogGoods(GOODS), ErrorCode.CONFLICT);
        });
    }

    @Test void boundedExpansionIsCompleteSortedAndRestoresCallerScope() {
        new CatalogPriceSourcePortTest().run((c, sql) -> {
            for (int i = 0; i < 255; i++) {
                sql.update("INSERT INTO prd_sku(id,sku_no,goods_no,entity_no,market,version,deleted)"
                        + " VALUES(? ,?,'G','E','AE',1,0)", 10 + i, "V" + i);
            }
            var p = port(c, UnaryOperator.identity());
            var scope = DataScopeSpec.of(ScopeDim.SELF, Set.of("BUYER"));
            DataScopeContext.set(scope);
            try {
                var facts = p.getCatalogGoods(GOODS);
                assertThat(facts.goods().skus()).hasSize(256);
                assertThat(facts.goods().skus()).extracting(GoodsCatalogQueryPort.SkuFact::skuNo).isSorted();
                assertThat(DataScopeContext.current()).isSameAs(scope);
                sql.update("INSERT INTO prd_sku(id,sku_no,goods_no,entity_no,market,version,deleted)"
                        + " VALUES(999,'OVERFLOW','G','E','AE',1,0)");
                code(() -> p.getCatalogGoods(GOODS), ErrorCode.CONFLICT);
                assertThat(DataScopeContext.current()).isSameAs(scope);
            } finally { DataScopeContext.clear(); }
        });
    }

    private static void code(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BizException.class,
                e -> assertThat(e.errorCode()).isEqualTo(expected));
    }
}
