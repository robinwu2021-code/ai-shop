package ai.neargo.shop.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.config.PrimaryPersistenceConfiguration;
import ai.neargo.shop.common.pricing.MerchantTaxSnapshot;
import ai.neargo.shop.common.pricing.MerchantTaxSnapshot.Source;
import ai.neargo.shop.common.pricing.PriceTaxMode;
import ai.neargo.shop.common.pricing.TaxSettingDefinition;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.entity.PrdStoreGoods;
import ai.neargo.shop.product.entity.PrdStorePrice;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import ai.neargo.shop.product.mapper.ProductMappers.StoreGoodsMapper;
import ai.neargo.shop.product.mapper.ProductMappers.StorePriceMapper;
import ai.neargo.shop.product.port.CatalogPriceSourcePortImpl;
import ai.neargo.shop.spi.pay.MarketPort;
import ai.neargo.shop.spi.pay.MarketPort.MarketBrief;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort.Request;
import ai.neargo.shop.spi.trade.PricingTaxSettingPort;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import ai.neargo.shop.spi.user.StoreDirectoryPort.StoreCard;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;

import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import javax.sql.DataSource;

class CatalogPriceSourcePortTest {
    static final Instant NOW = Instant.parse("2026-10-26T08:00:00Z");
    static final Request REQUEST = new Request("E", "S", List.of("SKU"));

    void run(BiConsumer<AssertableApplicationContext, JdbcTemplate> test) {
        new ApplicationContextRunner()
                .withUserConfiguration(Fixture.class)
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:catalog_price_"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE",
                        "spring.datasource.username=sa",
                        "spring.datasource.password=")
                .run(
                        c -> {
                            assertThat(c).hasNotFailed();
                            var sql = new JdbcTemplate(c.getBean(DataSource.class));
                            for (Class<?> type :
                                    List.of(
                                            PrdGoods.class,
                                            PrdSku.class,
                                            PrdStorePrice.class,
                                            PrdStoreGoods.class)) createTable(sql, type);
                            sql.update(
                                    "INSERT INTO"
                                        + " prd_goods(id,goods_no,entity_no,store_no,title,on_sale,audit_status,version,deleted)"
                                        + " VALUES(1,'G','E','S','Dish',TRUE,'APPROVED',3,0)");
                            sql.update(
                                    "INSERT INTO"
                                        + " prd_sku(id,sku_no,goods_no,entity_no,market,price,version,deleted)"
                                        + " VALUES(1,'SKU','G','E','CN',500,7,0),(2,'SKU','G','E','AE',900,8,0)");
                            test.accept(c, sql);
                        });
    }

    static void createTable(JdbcTemplate sql, Class<?> type) {
        var table = TableInfoHelper.getTableInfo(type);
        String fields =
                table.getFieldList().stream()
                        .map(
                                field -> {
                                    Class<?> value = field.getPropertyType();
                                    String sqlType =
                                            value == LocalDateTime.class
                                                    ? "TIMESTAMP"
                                                    : value == Boolean.class
                                                            ? "BOOLEAN"
                                                            : Number.class.isAssignableFrom(value)
                                                                    ? "BIGINT"
                                                                    : "VARCHAR(4096)";
                                    return field.getColumn() + " " + sqlType;
                                })
                        .collect(Collectors.joining(","));
        sql.execute(
                "CREATE TABLE " + table.getTableName() + " (id BIGINT PRIMARY KEY," + fields + ")");
    }

    static MerchantTaxSnapshot tax(int bp, PriceTaxMode mode, long version) {
        var definition = new TaxSettingDefinition(true, bp, mode);
        return new MerchantTaxSnapshot(
                "MAIN",
                "E",
                "S",
                version,
                Source.MERCHANT_CUSTOM,
                "E",
                version,
                bp,
                mode,
                definition.snapshotHash("MERCHANT_CUSTOM:E", version));
    }

    @Test
    void returnsOriginalMarketCurrencyWithoutFallingBackToCnCheckout() {
        run(
                (c, sql) -> {
                    var source = c.getBean(CatalogPriceSourcePort.class);
                    var facts = source.capture(REQUEST);
                    assertThat(facts.market().market()).isEqualTo("AE");
                    assertThat(facts.market().currency()).isEqualTo("AED");
                    assertThat(facts.prices().getFirst().amountMinor()).isEqualTo(900);
                    assertThat(facts.prices().getFirst().versions().sku()).isEqualTo(8);
                    assertThat(facts.tax().rateBp()).isEqualTo(500);
                    assertThat(facts.tax().priceTaxMode()).isEqualTo("EXCLUSIVE");
                    assertThat(facts.verifiedAt()).isEqualTo(NOW);
                    assertThat(facts.validUntil()).isEqualTo(NOW.plusSeconds(30));
                    assertThat(facts.receiptNo()).hasSize(64);
                    when(c.getBean(MerchantQueryPort.class).marketOf("E")).thenReturn("CN");
                    var cn = source.capture(REQUEST);
                    assertThat(cn.prices().getFirst().amountMinor()).isEqualTo(500);
                    assertThat(cn.market().currency()).isEqualTo("CNY");
                    assertThat(cn.prices().getFirst().sourceRevision())
                            .isNotEqualTo(facts.prices().getFirst().sourceRevision());
                });
    }

    @Test
    void originalStorePriceAndSourceVersionsAreScopedToMarketAndStore() {
        run(
                (c, sql) -> {
                    sql.update(
                            "INSERT INTO"
                                + " prd_store_price(id,entity_no,store_no,sku_no,market,price,version,deleted)"
                                + " VALUES(1,'E','S','SKU','AE',800,4,0),(2,'E','OTHER','SKU','AE',100,4,0),"
                                + "(3,'E','S','SKU','CN',50,4,0),(4,'FOREIGN','S','SKU','AE',1,4,0)");
                    var source = c.getBean(CatalogPriceSourcePort.class);
                    var first = source.capture(REQUEST).prices().getFirst();
                    assertThat(first.amountMinor()).isEqualTo(800);
                    assertThat(first.sourceKind()).isEqualTo("STORE_PRICE");
                    assertThat(first.versions().storePrice()).isEqualTo(4);
                    sql.update("UPDATE prd_store_price SET price=810,version=5 WHERE id=1");
                    var next = source.capture(REQUEST).prices().getFirst();
                    assertThat(next.sourceRevision()).isNotEqualTo(first.sourceRevision());
                    assertThat(next.amountMinor()).isEqualTo(810);
                    sql.update("UPDATE prd_store_price SET price=NULL,version=6 WHERE id=1");
                    var fallback = source.capture(REQUEST).prices().getFirst();
                    assertThat(fallback.amountMinor()).isEqualTo(900);
                    assertThat(fallback.sourceKind()).isEqualTo("ENTITY_SKU");
                    assertThat(fallback.versions().storePrice()).isEqualTo(6);
                });
    }

    @Test
    void unknownPriceIsNotZeroAndExplicitZeroRemainsValidButInvalidMoneyIsRejected() {
        run(
                (c, sql) -> {
                    var source = c.getBean(CatalogPriceSourcePort.class);
                    sql.update("UPDATE prd_sku SET price=NULL WHERE market='AE'");
                    var unknown = source.capture(REQUEST).prices().getFirst();
                    assertThat(unknown.amountMinor()).isNull();
                    assertThat(unknown.status()).isEqualTo("UNAVAILABLE");
                    sql.update("UPDATE prd_sku SET price=0 WHERE market='AE'");
                    assertThat(source.capture(REQUEST).prices().getFirst().amountMinor()).isZero();
                    for (long invalid : List.of(-1L, 9_007_199_254_740_992L)) {
                        sql.update("UPDATE prd_sku SET price=? WHERE market='AE'", invalid);
                        assertThatThrownBy(() -> source.capture(REQUEST))
                                .isInstanceOf(IllegalStateException.class);
                    }
                });
    }

    @Test
    void originalStoreVisibilityDoesNotLeakThroughOtherStoreOrEntityOverlayRows() {
        run(
                (c, sql) -> {
                    var source = c.getBean(CatalogPriceSourcePort.class);
                    sql.update(
                            "INSERT INTO"
                                + " prd_store_goods(id,entity_no,store_no,goods_no,on_sale,version,deleted)"
                                + " VALUES(1,'FOREIGN','OTHER','G',TRUE,1,0)");
                    assertThat(source.capture(REQUEST).prices()).hasSize(1);
                    sql.update(
                            "INSERT INTO"
                                + " prd_store_goods(id,entity_no,store_no,goods_no,on_sale,version,deleted)"
                                + " VALUES(2,'E','OTHER','G',TRUE,2,0)");
                    code(() -> source.capture(REQUEST), ErrorCode.NOT_FOUND);
                    sql.update(
                            "INSERT INTO"
                                + " prd_store_goods(id,entity_no,store_no,goods_no,on_sale,version,deleted)"
                                + " VALUES(3,'E','S','G',FALSE,3,0)");
                    code(() -> source.capture(REQUEST), ErrorCode.NOT_FOUND);
                    sql.update("UPDATE prd_store_goods SET on_sale=TRUE,version=4 WHERE id=3");
                    assertThat(source.capture(REQUEST).prices().getFirst().versions().storeGoods())
                            .isEqualTo(4);
                    sql.update("UPDATE prd_goods SET store_no='OTHER'");
                    code(() -> source.capture(REQUEST), ErrorCode.NOT_FOUND);
                });
    }

    @Test
    void missingSkuForeignGoodsAndUnapprovedOrOffSaleHeadsCannotProducePartialSuccess() {
        run(
                (c, sql) -> {
                    var source = c.getBean(CatalogPriceSourcePort.class);
                    code(
                            () -> source.capture(new Request("E", "S", List.of("SKU", "MISSING"))),
                            ErrorCode.NOT_FOUND);
                    for (String statement :
                            List.of(
                                    "UPDATE prd_goods SET audit_status='PENDING'",
                                    "UPDATE prd_goods SET on_sale=FALSE",
                                    "UPDATE prd_goods SET entity_no='FOREIGN'")) {
                        sql.update(statement);
                        code(() -> source.capture(REQUEST), ErrorCode.NOT_FOUND);
                        sql.update(
                                "UPDATE prd_goods SET"
                                        + " audit_status='APPROVED',on_sale=TRUE,entity_no='E'");
                    }
                    sql.update("UPDATE prd_sku SET entity_no='FOREIGN' WHERE market='AE'");
                    code(() -> source.capture(REQUEST), ErrorCode.NOT_FOUND);
                });
    }

    @Test
    void missingMarketCurrencyTaxOrStoreAuthorityNeverUsesAConfiguredCnPriceAsFallback() {
        run(
                (c, sql) -> {
                    var source = c.getBean(CatalogPriceSourcePort.class);
                    var merchants = c.getBean(MerchantQueryPort.class);
                    when(merchants.marketOf("E")).thenReturn(null);
                    code(() -> source.capture(REQUEST), ErrorCode.PRICING_OWNER_UNSUPPORTED);
                    when(merchants.marketOf("E")).thenReturn("AE");
                    when(c.getBean(MarketPort.class).find("AE")).thenReturn(Optional.empty());
                    code(() -> source.capture(REQUEST), ErrorCode.PRICING_OWNER_UNSUPPORTED);
                    when(c.getBean(MarketPort.class).find("AE"))
                            .thenReturn(
                                    Optional.of(
                                            new MarketBrief(
                                                    "AE", "UAE", "AED", 2, "UTC", 1, true)));
                    when(c.getBean(PricingTaxSettingPort.class).resolve(any())).thenReturn(null);
                    code(() -> source.capture(REQUEST), ErrorCode.PRICING_OWNER_UNSUPPORTED);
                    when(c.getBean(StoreDirectoryPort.class).cards(List.of("S")))
                            .thenReturn(Map.of());
                    code(() -> source.capture(REQUEST), ErrorCode.FORBIDDEN);
                });
    }

    @Test
    void originalOwnerContextIsReadOutsideTransactionsAndChangesAbortMixedEvidence() {
        run(
                (c, sql) -> {
                    var taxes = c.getBean(PricingTaxSettingPort.class);
                    when(taxes.resolve(any()))
                            .thenAnswer(
                                    call -> {
                                        assertThat(
                                                        TransactionSynchronizationManager
                                                                .isActualTransactionActive())
                                                .isFalse();
                                        return tax(500, PriceTaxMode.INCLUSIVE, 2);
                                    });
                    var source = c.getBean(CatalogPriceSourcePort.class);
                    assertThat(source.capture(REQUEST).tax().priceTaxMode()).isEqualTo("INCLUSIVE");
                    when(taxes.resolve(any()))
                            .thenReturn(
                                    tax(500, PriceTaxMode.INCLUSIVE, 2),
                                    tax(600, PriceTaxMode.EXCLUSIVE, 3));
                    code(() -> source.capture(REQUEST), ErrorCode.CONFLICT);
                });
    }

    @Test
    void inputBoundsVersionsAndThreeDecimalMarketAreHandledExplicitly() {
        run(
                (c, sql) -> {
                    var source = c.getBean(CatalogPriceSourcePort.class);
                    code(
                            () -> source.capture(new Request("E", "S", List.of("SKU", "SKU"))),
                            ErrorCode.BAD_REQUEST);
                    code(
                            () -> source.capture(new Request("E", "S", List.of())),
                            ErrorCode.BAD_REQUEST);
                    sql.update(
                            "INSERT INTO"
                                + " prd_sku(id,sku_no,goods_no,entity_no,market,price,version,deleted)"
                                + " VALUES(3,'SKU','G','E','KW',1234,9,0)");
                    when(c.getBean(MerchantQueryPort.class).marketOf("E")).thenReturn("KW");
                    when(c.getBean(MarketPort.class).find("KW"))
                            .thenReturn(
                                    Optional.of(
                                            new MarketBrief(
                                                    "KW", "Kuwait", "KWD", 3, "UTC", 1, true)));
                    var facts = source.capture(REQUEST);
                    assertThat(facts.market().currencyScale()).isEqualTo(3);
                    assertThat(facts.prices().getFirst().amountMinor()).isEqualTo(1234);
                    sql.update("UPDATE prd_sku SET version=NULL WHERE market='KW'");
                    assertThatThrownBy(() -> source.capture(REQUEST))
                            .isInstanceOf(IllegalStateException.class);
                });
    }

    @Test
    void everyPresentOverlayRequiresARealVersionEvenWhenItsPriceFallsBack() {
        run(
                (c, sql) -> {
                    var source = c.getBean(CatalogPriceSourcePort.class);
                    sql.update(
                            "INSERT INTO"
                                + " prd_store_price(id,entity_no,store_no,sku_no,market,price,version,deleted)"
                                + " VALUES(1,'E','S','SKU','AE',NULL,NULL,0)");
                    assertThatThrownBy(() -> source.capture(REQUEST))
                            .isInstanceOf(IllegalStateException.class);
                    sql.update("UPDATE prd_store_price SET version=1");
                    assertThat(source.capture(REQUEST).prices().getFirst().amountMinor())
                            .isEqualTo(900);
                    sql.update(
                            "INSERT INTO"
                                + " prd_store_goods(id,entity_no,store_no,goods_no,on_sale,version,deleted)"
                                + " VALUES(1,'E','S','G',TRUE,NULL,0)");
                    assertThatThrownBy(() -> source.capture(REQUEST))
                            .isInstanceOf(IllegalStateException.class);
                });
    }

    static void code(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        BizException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(expected));
    }

    @Configuration(proxyBeanMethods = false)
    @Import(PrimaryPersistenceConfiguration.class)
    static class Fixture {
        @Bean
        SqlSessionTemplate session(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        static <T> MapperFactoryBean<T> mapper(Class<T> type, SqlSessionTemplate s) {
            var bean = new MapperFactoryBean<>(type);
            bean.setSqlSessionTemplate(s);
            return bean;
        }

        @Bean
        MapperFactoryBean<SkuMapper> skus(SqlSessionTemplate s) {
            return mapper(SkuMapper.class, s);
        }

        @Bean
        MapperFactoryBean<GoodsMapper> goods(SqlSessionTemplate s) {
            return mapper(GoodsMapper.class, s);
        }

        @Bean
        MapperFactoryBean<StorePriceMapper> prices(SqlSessionTemplate s) {
            return mapper(StorePriceMapper.class, s);
        }

        @Bean
        MapperFactoryBean<StoreGoodsMapper> visibility(SqlSessionTemplate s) {
            return mapper(StoreGoodsMapper.class, s);
        }

        @Bean
        StoreDirectoryPort stores() {
            var port = mock(StoreDirectoryPort.class);
            when(port.cards(List.of("S")))
                    .thenReturn(
                            Map.of(
                                    "S",
                                    new StoreCard(
                                            "S", "Store", "E", null, "ACTIVE", true, null, null,
                                            null, null, 0, 0)));
            return port;
        }

        @Bean
        MerchantQueryPort merchants() {
            var port = mock(MerchantQueryPort.class);
            when(port.marketOf("E")).thenReturn("AE");
            return port;
        }

        @Bean
        MarketPort markets() {
            var port = mock(MarketPort.class);
            when(port.find("AE"))
                    .thenReturn(
                            Optional.of(new MarketBrief("AE", "UAE", "AED", 2, "UTC", 1, true)));
            when(port.find("CN"))
                    .thenReturn(
                            Optional.of(new MarketBrief("CN", "China", "CNY", 2, "UTC", 1, true)));
            return port;
        }

        @Bean
        PricingTaxSettingPort taxes() {
            var port = mock(PricingTaxSettingPort.class);
            when(port.resolve(any())).thenReturn(tax(500, PriceTaxMode.EXCLUSIVE, 2));
            return port;
        }

        @Bean
        CatalogPriceSourcePort source(
                SkuMapper s,
                GoodsMapper g,
                StorePriceMapper p,
                StoreGoodsMapper v,
                StoreDirectoryPort stores,
                MerchantQueryPort merchants,
                MarketPort markets,
                PricingTaxSettingPort taxes,
                PlatformTransactionManager manager) {
            return new CatalogPriceSourcePortImpl(
                    s,
                    g,
                    p,
                    v,
                    stores,
                    merchants,
                    markets,
                    taxes,
                    manager,
                    Clock.fixed(NOW, ZoneOffset.UTC));
        }
    }
}
