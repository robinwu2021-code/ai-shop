package ai.neargo.shop.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.config.PrimaryPersistenceConfiguration;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import ai.neargo.shop.product.mapper.ProductMappers.StoreGoodsMapper;
import ai.neargo.shop.product.mapper.ProductMappers.StorePriceMapper;
import ai.neargo.shop.product.mapper.ProductMappers.StoreStockMapper;
import ai.neargo.shop.product.port.DishOfferQueryPortImpl;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.entity.PrdStoreGoods;
import ai.neargo.shop.product.entity.PrdStorePrice;
import ai.neargo.shop.product.entity.PrdStoreStock;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.time.LocalDateTime;
import ai.neargo.shop.product.port.GoodsQueryPortImpl;
import ai.neargo.shop.product.service.StoreStockReader;
import ai.neargo.shop.spi.marketing.CampaignPort;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

class DishOfferQueryPortTest {
    private void run(BiConsumer<ApplicationContext, JdbcTemplate> test) {
        new ApplicationContextRunner().withUserConfiguration(Fixture.class).withPropertyValues(
                "spring.datasource.url=jdbc:h2:mem:offers_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE",
                "spring.datasource.username=sa", "spring.datasource.password=").run(c -> {
            assertThat(c).hasNotFailed();
            var sql = new JdbcTemplate(c.getBean(DataSource.class));
            for (Class<?> type : List.of(PrdGoods.class, PrdSku.class, PrdStorePrice.class,
                    PrdStoreGoods.class, PrdStoreStock.class)) createTable(sql, type);
            sql.update("INSERT INTO prd_goods(id,goods_no,entity_no,store_no,title,"
                    + "on_sale,audit_status,deleted) VALUES(1,'G','E','S','Dish',TRUE,'APPROVED',0)");
            sql.update("INSERT INTO prd_sku(id,sku_no,goods_no,entity_no,market,price,"
                    + "stock,locked_stock,deleted) VALUES(1,'SKU','G','E','CN',500,10,2,0)");
            test.accept(c, sql);
        });
    }

    // Original snapshot selects complete entities. Fixture adds all mapped columns without inventing data.
    private static void createTable(JdbcTemplate sql, Class<?> type) {
        var table = TableInfoHelper.getTableInfo(type);
        String fields = table.getFieldList().stream().map(field -> {
            Class<?> value = field.getPropertyType();
            String sqlType = value == LocalDateTime.class ? "TIMESTAMP" : value == Boolean.class ? "BOOLEAN"
                    : Number.class.isAssignableFrom(value) ? "BIGINT" : "VARCHAR(1024)";
            return field.getColumn() + " " + sqlType;
        }).collect(Collectors.joining(","));
        sql.execute("CREATE TABLE " + table.getTableName() + " (id BIGINT PRIMARY KEY," + fields + ")");
    }

    @Test void reusesOriginalStoreFlashPriceAndAuthoritativeLockedStoreStock() {
        run((c, sql) -> {
            var port = c.getBean(DishOfferQueryPortImpl.class);
            var offer = port.getOffers("E", "S", List.of("SKU")).getFirst();
            assertThat(offer.priceMinor()).isEqualTo(500);
            assertThat(offer.currency()).isEqualTo("CNY");
            assertThat(offer.pricingStatus()).isEqualTo("KNOWN");
            assertThat(offer.available()).isEqualTo(8);
            assertThat(offer.stockStatus()).isEqualTo("KNOWN");
            sql.update("INSERT INTO prd_store_price(id,sku_no,entity_no,store_no,market,"
                    + "price,deleted) VALUES(1,'SKU','E','S','CN',700,0)");
            sql.update("INSERT INTO prd_store_stock(id,sku_no,entity_no,store_no,stock,"
                    + "locked_stock,deleted) VALUES(1,'SKU','E','S',4,1,0)");
            assertThat(port.getOffers("E", "S", List.of("SKU")).getFirst().priceMinor()).isEqualTo(700);
            assertThat(port.getOffers("E", "S", List.of("SKU")).getFirst().available()).isEqualTo(3);
            when(c.getBean(CampaignPort.class).flashPrices(List.of("G"))).thenReturn(Map.of("G", 600L));
            assertThat(port.getOffers("E", "S", List.of("SKU")).getFirst().priceMinor()).isEqualTo(600);
            assertThat(sql.queryForObject("SELECT stock FROM prd_store_stock", Integer.class)).isEqualTo(4);
            sql.update("UPDATE prd_store_stock SET store_no='OTHER'");
            assertThat(port.getOffers("E", "S", List.of("SKU")).getFirst().available()).isZero();
        });
    }

    @Test void noNullPriceOrForeignUnapprovedSkuCanBecomeAZeroPriceOffer() {
        run((c, sql) -> {
            var port = c.getBean(DishOfferQueryPortImpl.class);
            assertThat(port.getOffers("OTHER", "S", List.of("SKU"))).isEmpty();
            assertThat(port.getOffers("E", "OTHER", List.of("SKU"))).isEmpty();
            sql.update("UPDATE prd_sku SET price=NULL");
            var unknown = port.getOffers("E", "S", List.of("SKU")).getFirst();
            assertThat(unknown.priceMinor()).isNull();
            assertThat(unknown.currency()).isNull();
            assertThat(unknown.pricingStatus()).isEqualTo("UNAVAILABLE");
            sql.update("UPDATE prd_sku SET price=0");
            assertThat(port.getOffers("E", "S", List.of("SKU")).getFirst().priceMinor()).isZero();
            sql.update("UPDATE prd_goods SET audit_status='PENDING'");
            assertThat(port.getOffers("E", "S", List.of("SKU"))).isEmpty();
        });
    }

    @Test void ambiguousLegacyMultiMarketStockDoesNotMasqueradeAsCurrentCnAvailability() {
        run((c, sql) -> {
            sql.update("INSERT INTO prd_sku(id,sku_no,goods_no,entity_no,market,price,"
                    + "stock,locked_stock,deleted) VALUES(2,'SKU','G','E','AE',900,100,0,0)");
            var offer = c.getBean(DishOfferQueryPortImpl.class).getOffers("E", "S", List.of("SKU")).getFirst();
            assertThat(offer.priceMinor()).isEqualTo(500);
            assertThat(offer.currency()).isEqualTo("CNY");
            assertThat(offer.stockStatus()).isEqualTo("UNKNOWN");
            assertThat(offer.available()).isNull();
        });
    }

    @Test void unknownAuthorityNeverUsesDefaultUnlimitedStockAndBoundsInput() {
        run((c, sql) -> {
            for (String authority : List.of("DUAL", "INVENTORY", "unexpected")) {
                var port = new DishOfferQueryPortImpl(c.getBean(SkuMapper.class), c.getBean(GoodsMapper.class),
                        c.getBean(GoodsQueryPortImpl.class), c.getBean(StoreStockReader.class), authority);
                var offer = port.getOffers("E", "S", List.of("SKU")).getFirst();
                assertThat(offer.available()).isNull();
                assertThat(offer.stockStatus()).isEqualTo("UNKNOWN");
            }
            assertThatThrownBy(() -> c.getBean(DishOfferQueryPortImpl.class)
                    .getOffers("E", "S", List.of("SKU", "SKU"))).isInstanceOf(BizException.class);
            assertThatThrownBy(() -> c.getBean(DishOfferQueryPortImpl.class).getOffers("E", "S",
                    IntStream.range(0, 101).mapToObj(i -> "SKU" + i).toList()))
                    .isInstanceOf(BizException.class);
        });
    }

    @Configuration(proxyBeanMethods=false)
    @Import(PrimaryPersistenceConfiguration.class)
    static class Fixture {
        @Bean SqlSessionTemplate session(SqlSessionFactory factory) { return new SqlSessionTemplate(factory); }
        private static <T> MapperFactoryBean<T> mapper(Class<T> type, SqlSessionTemplate session) {
            var factory = new MapperFactoryBean<>(type); factory.setSqlSessionTemplate(session); return factory;
        }
        @Bean MapperFactoryBean<SkuMapper> skus(SqlSessionTemplate s) { return mapper(SkuMapper.class, s); }
        @Bean MapperFactoryBean<GoodsMapper> goods(SqlSessionTemplate s) { return mapper(GoodsMapper.class, s); }
        @Bean MapperFactoryBean<StorePriceMapper> prices(SqlSessionTemplate s) {
            return mapper(StorePriceMapper.class, s);
        }
        @Bean MapperFactoryBean<StoreGoodsMapper> storeGoods(SqlSessionTemplate s) {
            return mapper(StoreGoodsMapper.class, s);
        }
        @Bean MapperFactoryBean<StoreStockMapper> stockMapper(SqlSessionTemplate s) {
            return mapper(StoreStockMapper.class, s);
        }
        @Bean CampaignPort campaigns() { return mock(CampaignPort.class); }
        @Bean StoreStockReader stocks(SkuMapper s, StoreStockMapper st) { return new StoreStockReader(s, st); }
        @Bean GoodsQueryPortImpl original(SkuMapper s, GoodsMapper g, StorePriceMapper p, StoreGoodsMapper sg,
                CampaignPort c, StoreStockReader st) {
            return new GoodsQueryPortImpl(s,g,new ObjectMapper(),p,sg,c,st);
        }
        @Bean DishOfferQueryPortImpl offers(SkuMapper s, GoodsMapper g, GoodsQueryPortImpl p, StoreStockReader st) {
            return new DishOfferQueryPortImpl(s, g, p, st, "PLATFORM");
        }
    }
}
