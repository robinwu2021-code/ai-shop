package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.config.PrimaryPersistenceConfiguration;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import ai.neargo.shop.spi.product.GoodsQueryPort;
import ai.neargo.shop.spi.product.ServiceProductFactsPort;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

/** Actual product SQL enforces ownership; the existing product quote contract supplies all pricing. */
class ServiceProductFactsPortTest {
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(PrimaryPersistenceConfiguration.class)
                .withPropertyValues("spring.datasource.url=jdbc:h2:mem:service_" + UUID.randomUUID()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "spring.datasource.username=sa",
                                "spring.datasource.password=");
    }
    private GoodsQueryPort quotes(boolean onSale) {
        var quotes = mock(GoodsQueryPort.class);
        when(quotes.snapshot(List.of("SKU"), Map.of("E", "S"))).thenReturn(Map.of("SKU",
                new GoodsQueryPort.SkuSnapshot("SKU", "G", "E", "service", null, null, "SERVICE", null,
                        9900, 0, onSale, List.of("SERVICE"), null, null, "NORMAL", null, null, "S")));
        return quotes;
    }
    private void verify(String mutation, boolean onSale, boolean succeeds) {
        runner().run(context -> {
            var sql = new JdbcTemplate(context.getBean(DataSource.class));
            sql.execute("CREATE TABLE prd_sku (sku_no VARCHAR(32), goods_no VARCHAR(32), entity_no VARCHAR(32),"
                    + "tenant_no VARCHAR(32), deleted INT, market VARCHAR(8),version BIGINT DEFAULT 0)");
            sql.execute("CREATE TABLE prd_goods (goods_no VARCHAR(32),entity_no VARCHAR(32),store_no VARCHAR(32),"
                    + "type VARCHAR(32),duration_min INT,tenant_no VARCHAR(32),deleted INT,version BIGINT DEFAULT 0)");
            sql.execute("INSERT INTO prd_sku VALUES ('SKU','G','E','MAIN',0,'CN',0)");
            sql.execute("INSERT INTO prd_goods VALUES ('G','E','S','SERVICE',60,'MAIN',0,7)");
            if (mutation != null) { sql.execute(mutation); }
            var factory = context.getBean(SqlSessionFactory.class);
            factory.getConfiguration().addMapper(SkuMapper.class);
            factory.getConfiguration().addMapper(GoodsMapper.class);
            var template = new SqlSessionTemplate(factory);
            Class<?> impl = assertDoesNotThrow(() -> Class.forName(
                    "ai.neargo.shop.product.port.ServiceProductFactsPortImpl"));
            var port = (ServiceProductFactsPort) impl.getConstructor(SkuMapper.class, GoodsMapper.class,
                    GoodsQueryPort.class).newInstance(template.getMapper(SkuMapper.class),
                            template.getMapper(GoodsMapper.class), quotes(onSale));
            if (succeeds) {
                var fact = port.get("E", "S", "SKU");
                assertThat(fact.durationMin()).isEqualTo(60);
                assertThat(fact.priceMinor()).isEqualTo(9900);
                assertThat(fact.goodsRevision()).isEqualTo(7);
                assertThat(fact.skuRevision()).isZero();
                var legacy = new ServiceProductFactsPort.Fact("E", "S", "SKU", "G", 9900, 60);
                assertThat(legacy.goodsRevision()).isNull();
                assertThat(legacy.skuRevision()).isNull();
            } else { assertThatThrownBy(() -> port.get("E", "S", "SKU")).isInstanceOf(BizException.class); }
        });
    }
    @Test void readsSharedDurationAndCurrentStoreQuote() { verify(null, true, true); }
    @Test void rejectsForeignEntitySku() { verify("UPDATE prd_sku SET entity_no='FOREIGN'", true, false); }
    @Test void rejectsForeignStore() { verify("UPDATE prd_goods SET store_no='OTHER'", true, false); }
    @Test void rejectsNonServiceProduct() { verify("UPDATE prd_goods SET type='PHYSICAL'", true, false); }
    @Test void isolatesCnMarketFromOtherSkuMarkets() {
        assertDoesNotThrow(() -> verify("INSERT INTO prd_sku VALUES ('SKU','G','E','MAIN',0,'AE',0)", true, true));
    }
    @Test void rejectsOffSaleService() { verify(null, false, false); }
}
