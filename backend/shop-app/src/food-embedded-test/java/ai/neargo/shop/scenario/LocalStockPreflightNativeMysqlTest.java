package ai.neargo.shop.scenario;

import static ai.neargo.shop.spi.product.LocalStockControlPort.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.neargo.shop.common.config.PrimaryPersistenceConfiguration;
import ai.neargo.shop.food.entity.FnbStockBalance;
import ai.neargo.shop.food.entity.FnbStockItem;
import ai.neargo.shop.food.mapper.FoodMappers.StockBalanceMapper;
import ai.neargo.shop.food.mapper.FoodMappers.StockItemMapper;
import ai.neargo.shop.food.port.FoodLocalStockPortImpl;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.entity.PrdStoreStock;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import ai.neargo.shop.product.mapper.ProductMappers.StoreStockMapper;
import ai.neargo.shop.product.port.FoodLocalStockFactsPortImpl;
import ai.neargo.shop.product.port.PlatformLocalStockFactsPortImpl;
import ai.neargo.shop.product.service.LocalStockControlService;
import ai.neargo.shop.product.service.StoreStockReader;
import ai.neargo.shop.product.service.impl.LocalStockControlServiceImpl;
import ai.neargo.shop.spi.food.FoodLocalStockPort;
import ai.neargo.shop.spi.product.LocalStockFactsPort;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/** Real local MySQL queries/owner implementations, no inventory source, isolated mapped-table fixtures. */
class LocalStockPreflightNativeMysqlTest {
    private static final Scope SCOPE = new Scope("MAIN", "E", "S");
    private Request request(Industry industry, String item, String unit, String qty, Action action) {
        return new Request(industry, Strategy.LOCAL, industry == Industry.FOOD ? ItemKind.MATERIAL : ItemKind.SKU,
                item, unit, new BigDecimal(qty), Set.of(action));
    }
    private void run(BiConsumer<ApplicationContext, JdbcTemplate> test) throws Exception {
        assumeTrue("true".equals(System.getenv("I04A_NATIVE_MYSQL")));
        String root = System.getenv("I04A_MYSQL_URL"), user = System.getenv("I04A_MYSQL_USER"), password = System.getenv("I04A_MYSQL_PASSWORD");
        assertThat(root).startsWith("jdbc:mysql://127.0.0.1:3308/?");
        String schema = "i04a_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(root, user, password); var sql = connection.createStatement()) {
            sql.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            try {
                new ApplicationContextRunner().withUserConfiguration(Fixture.class).withPropertyValues(
                        "spring.datasource.url=" + root.replace("/?", "/" + schema + "?"),
                        "spring.datasource.username=" + user, "spring.datasource.password=" + password).run(c -> {
                    assertThat(c).hasNotFailed();
                    var db = new JdbcTemplate(c.getBean(DataSource.class));
                    for (Class<?> type : List.of(PrdGoods.class, PrdSku.class, PrdStoreStock.class,
                            FnbStockItem.class, FnbStockBalance.class)) createTable(db, type);
                    db.update("INSERT INTO prd_goods(id,tenant_no,goods_no,entity_no,type,deleted) VALUES(1,'MAIN','G','E','NORMAL',0)");
                    db.update("INSERT INTO prd_sku(id,tenant_no,sku_no,goods_no,entity_no,market,sale_unit,stock,locked_stock,deleted) VALUES(1,'MAIN','SKU','G','E','CN','EA',10,2,0)");
                    db.update("INSERT INTO fnb_stock_item(id,tenant_no,entity_no,item_no,item_kind,base_unit,tracking_mode,status,deleted) VALUES(1,'MAIN','E','RAW','MATERIAL','G','TRACKED','ACTIVE',0)");
                    db.update("INSERT INTO fnb_stock_balance(id,tenant_no,entity_no,store_no,balance_no,item_no,tracking_mode,status,on_hand,reserved_qty,deleted) VALUES(1,'MAIN','E','S','B','RAW','TRACKED','ACTIVE',5.125,1.125,0)");
                    assertThat(db.queryForList("SHOW TABLES LIKE 'inv_%'")).isEmpty();
                    test.accept(c, db);
                });
            } finally { sql.execute("DROP DATABASE " + schema); }
        }
    }
    private static void createTable(JdbcTemplate db, Class<?> type) {
        var table = TableInfoHelper.getTableInfo(type);
        String columns = table.getFieldList().stream().map(f -> {
            Class<?> v = f.getPropertyType();
            String sqlType = v == LocalDateTime.class ? "DATETIME(6)" : v == BigDecimal.class ? "DECIMAL(24,6)"
                    : v == Boolean.class || v == boolean.class ? "TINYINT" : Number.class.isAssignableFrom(v) ? "BIGINT" : "TEXT";
            return "`" + f.getColumn() + "` " + sqlType;
        }).collect(Collectors.joining(","));
        db.execute("CREATE TABLE " + table.getTableName() + " (id BIGINT PRIMARY KEY," + columns + ") ENGINE=InnoDB");
    }
    @Test void realRetailAndBeautyFactsKeepStoreCoverageAndOriginalLedgerReadOnly() throws Exception {
        run((c, db) -> {
            var service = c.getBean(LocalStockControlService.class); var reader = c.getBean(StoreStockReader.class);
            for (var industry : List.of(Industry.RETAIL, Industry.BEAUTY)) {
                var result = service.preflight(SCOPE, request(industry, "SKU", "EA", "3", Action.RESERVE));
                assertThat(result.allowed()).isTrue(); assertThat(result.route().owner()).isEqualTo("PLATFORM");
                assertThat(result.stock().quantityScope()).isEqualTo(QuantityScope.ENTITY);
                assertThat(result.stock().availableQuantity()).isEqualByComparingTo("8");
            }
            db.update("INSERT INTO prd_store_stock(id,tenant_no,entity_no,store_no,sku_no,stock,locked_stock,deleted) VALUES(1,'MAIN','E','OTHER','SKU',4,1,0)");
            assertThat(service.preflight(SCOPE, request(Industry.RETAIL,"SKU","EA","1",Action.RESERVE)).stock().availableQuantity()).isEqualByComparingTo("0");
            assertThat(reader.available("SKU", "S")).isZero(); assertThat(reader.total(List.of("SKU"), "S").get("SKU")).isZero();
            db.update("UPDATE prd_store_stock SET store_no='S'");
            var before = db.queryForList("SELECT * FROM prd_store_stock");
            for (var industry : List.of(Industry.RETAIL, Industry.BEAUTY)) {
                var result = service.preflight(SCOPE, request(industry,"SKU","EA","3",Action.RESERVE));
                assertThat(result.allowed()).isTrue(); assertThat(result.stock().availableQuantity()).isEqualByComparingTo("3");
                assertThat(result.stock().quantityScope()).isEqualTo(QuantityScope.STORE);
            }
            assertThat(reader.available("SKU", "S")).isEqualTo(3);
            assertThat(reader.total(List.of("SKU"), "S").get("SKU")).isEqualTo(4);
            assertThat(service.preflight(new Scope("MAIN","FOREIGN","S"),request(Industry.RETAIL,"SKU","EA","1",Action.QUERY)).issues()).containsExactly(Issue.ITEM_NOT_FOUND);
            assertThat(db.queryForList("SELECT * FROM prd_store_stock")).isEqualTo(before);
            assertThat(db.queryForMap("SELECT stock,locked_stock FROM prd_sku")).containsEntry("stock",10L).containsEntry("locked_stock",2L);
        });
    }
    @Test void realAmbiguousUnitsAndUninitializedQuantitiesAreNotInvented() throws Exception {
        run((c, db) -> {
            var service = c.getBean(LocalStockControlService.class);
            db.update("INSERT INTO prd_sku(id,tenant_no,sku_no,goods_no,entity_no,market,sale_unit,stock,locked_stock,deleted) VALUES(2,'MAIN','SKU','G','E','AE','EA',100,0,0)");
            assertThat(service.preflight(SCOPE,request(Industry.RETAIL,"SKU","EA","1",Action.QUERY)).issues()).containsExactly(Issue.AMBIGUOUS_STOCK);
            db.update("DELETE FROM prd_sku WHERE id=2"); db.update("UPDATE prd_sku SET stock=NULL");
            var unknown = service.preflight(SCOPE,request(Industry.RETAIL,"SKU","EA","1",Action.RESERVE));
            assertThat(unknown.stock().availableQuantity()).isNull(); assertThat(unknown.issues()).containsExactly(Issue.ITEM_NOT_READY);
            db.update("UPDATE prd_sku SET stock=10,sale_unit=NULL");
            assertThat(service.preflight(SCOPE,request(Industry.RETAIL,"SKU","EA","1",Action.QUERY)).issues()).containsExactly(Issue.UNIT_UNCONFIGURED);
            db.update("UPDATE prd_sku SET sale_unit='EA'");
            assertThat(service.preflight(SCOPE,request(Industry.RETAIL,"SKU","KG","1",Action.RESERVE)).issues()).containsExactly(Issue.UNIT_MISMATCH);
            db.update("UPDATE prd_goods SET type='SERVICE'");
            assertThat(service.preflight(SCOPE,request(Industry.BEAUTY,"SKU","EA","1",Action.QUERY)).issues()).containsExactly(Issue.NOT_PHYSICAL_STOCK);
        });
    }
    @Test void actualFoodLocalOwnerPreservesExactDecimalsAndNoInventoryFallbackOrWrites() throws Exception {
        run((c, db) -> {
            var before = db.queryForList("SELECT * FROM fnb_stock_balance"); var service = c.getBean(LocalStockControlService.class);
            var result = service.preflight(SCOPE,request(Industry.FOOD,"RAW","G","0.125",Action.ISSUE_PRODUCTION));
            assertThat(result.allowed()).isTrue(); assertThat(result.route().owner()).isEqualTo("FOOD_LOCAL");
            assertThat(result.stock().availableQuantity()).isEqualByComparingTo("4.000000");
            assertThat(service.preflight(new Scope("MAIN","E","OTHER"),request(Industry.FOOD,"RAW","G","1",Action.RESERVE)).issues()).containsExactly(Issue.ITEM_NOT_READY);
            assertThat(service.preflight(SCOPE,request(Industry.FOOD,"RAW","EA","1",Action.RESERVE)).stock().availableQuantity()).isNull();
            assertThat(db.queryForList("SELECT * FROM fnb_stock_balance")).isEqualTo(before);
            db.update("UPDATE fnb_stock_balance SET tracking_mode='UNTRACKED'");
            var untracked = service.preflight(SCOPE,request(Industry.FOOD,"RAW","G","1",Action.QUERY));
            assertThat(untracked.allowed()).isTrue(); assertThat(untracked.stock().availableQuantity()).isNull();
            assertThat(untracked.stock().status()).isEqualTo(StockStatus.UNTRACKED);
            assertThat(service.preflight(SCOPE,request(Industry.FOOD,"RAW","G","1",Action.RESERVE)).issues()).contains(Issue.NOT_TRACKED);
        });
    }
    @Configuration(proxyBeanMethods=false) @Import(PrimaryPersistenceConfiguration.class)
    static class Fixture {
        private static <T> MapperFactoryBean<T> mapper(Class<T> type, SqlSessionTemplate s) {
            var factory = new MapperFactoryBean<>(type); factory.setSqlSessionTemplate(s); return factory;
        }
        @Bean MapperFactoryBean<SkuMapper> sku(SqlSessionTemplate s) { return mapper(SkuMapper.class,s); }
        @Bean MapperFactoryBean<GoodsMapper> goods(SqlSessionTemplate s) { return mapper(GoodsMapper.class,s); }
        @Bean MapperFactoryBean<StoreStockMapper> stores(SqlSessionTemplate s) { return mapper(StoreStockMapper.class,s); }
        @Bean MapperFactoryBean<StockItemMapper> foodItems(SqlSessionTemplate s) { return mapper(StockItemMapper.class,s); }
        @Bean MapperFactoryBean<StockBalanceMapper> foodBalances(SqlSessionTemplate s) { return mapper(StockBalanceMapper.class,s); }
        @Bean StoreStockReader reader(SkuMapper s,StoreStockMapper stores) { return new StoreStockReader(s,stores); }
        @Bean LocalStockFactsPort platform(StoreStockReader s,GoodsMapper g) { return new PlatformLocalStockFactsPortImpl(s,g); }
        @Bean FoodLocalStockPort food(StockItemMapper i,StockBalanceMapper b,PlatformTransactionManager tx) {
            return new FoodLocalStockPortImpl(i,b,null,null,null,null,JsonMapper.builder().build(),tx,null);
        }
        @Bean LocalStockFactsPort foodFacts(ObjectProvider<FoodLocalStockPort> food) { return new FoodLocalStockFactsPortImpl(food); }
        @Bean LocalStockControlService service(List<LocalStockFactsPort> sources) { return new LocalStockControlServiceImpl(sources); }
    }
}
