package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import ai.neargo.shop.common.AuditMetaObjectHandler;
import ai.neargo.shop.product.mapper.ProductMappers.*;
import ai.neargo.shop.product.policy.ExecutionStockRetentionGuard;
import ai.neargo.shop.product.port.RetailReplenishmentTargetPortImpl;
import ai.neargo.shop.product.port.StockPortImpl;
import ai.neargo.shop.product.service.StoreStockReader;
import ai.neargo.shop.spi.product.LocalStockControlPort.Scope;
import ai.neargo.shop.spi.product.LocalStockControlPort.QuantityScope;
import ai.neargo.shop.spi.product.RetailReplenishmentTargetPort;
import ai.neargo.shop.spi.product.RetailReplenishmentTargetPort.*;
import ai.neargo.shop.spi.product.StockPort;
import ai.neargo.shop.spi.product.StockPort.SkuQty;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.trade.order.service.create.OrderStockAllocator;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/** Actual local tables/mappers/transactions; store directory and retention guard are explicit test doubles. */
@EnabledIfEnvironmentVariable(named = "R01_MYSQL_URL", matches = "jdbc:mysql://127\\.0\\.0\\.1:3308/.*")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RetailReplenishmentTargetNativeMysqlTest {
    private static final Scope S = new Scope("MAIN", "E", "S");
    private final String schema = "i04d_" + UUID.randomUUID().toString().replace("-", "");
    private JdbcTemplate admin, db;
    private DataSourceTransactionManager manager;
    private TransactionTemplate transaction;
    private SqlSessionTemplate sessions;
    private StoreStockReader reader;
    private MerchantQueryPort merchants;
    private StockPort stock;
    private RetailReplenishmentTargetPort targets;
    private static Mapping mapping(String qty, String factor) {
        return new Mapping("SKU", "EA", new BigDecimal(qty), new BigDecimal(factor));
    }
    private Inspection inspect() { return targets.inspect(S, mapping("20", "1")); }

    @BeforeAll void database() throws Exception {
        var url = System.getenv("R01_MYSQL_URL");
        assertThat(url).startsWith("jdbc:mysql://127.0.0.1:3308/information_schema");
        String user = System.getenv("R01_MYSQL_USER"), pass = System.getenv("R01_MYSQL_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, pass));
        assertThat(admin.queryForObject("SELECT VERSION()", String.class)).startsWith("9.");
        admin.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
        var source = new DriverManagerDataSource(url.replace("information_schema", schema), user, pass);
        db = new JdbcTemplate(source);
        String ddl = new ClassPathResource("schema-test.sql").getContentAsString(StandardCharsets.UTF_8);
        for (String table : List.of("prd_goods", "prd_sku", "prd_store_stock", "prd_stock_lock", "prd_store_stock_sync")) {
            var match = Pattern.compile("(?s)CREATE TABLE IF NOT EXISTS " + table + "\\s*\\(.*?\n\\);").matcher(ddl);
            assertThat(match.find()).as(table).isTrue();
            db.execute(match.group());
        }
        manager = new DataSourceTransactionManager(source);
        transaction = new TransactionTemplate(manager);
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source); factory.setConfiguration(config);
        factory.setGlobalConfig(new GlobalConfig().setMetaObjectHandler(new AuditMetaObjectHandler()));
        var plugins = new com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor();
        plugins.addInnerInterceptor(new com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor());
        factory.setPlugins(plugins);
        var sessionFactory = factory.getObject();
        for (var type : List.of(SkuMapper.class, StoreStockMapper.class, GoodsMapper.class,
                StockLockMapper.class, StoreStockSyncMapper.class)) sessionFactory.getConfiguration().addMapper(type);
        sessions = new SqlSessionTemplate(sessionFactory);
        reader = new StoreStockReader(sessions.getMapper(SkuMapper.class), sessions.getMapper(StoreStockMapper.class));
    }

    @BeforeEach void localSeventeen() {
        for (String table : List.of("prd_stock_lock", "prd_store_stock", "prd_store_stock_sync", "prd_sku", "prd_goods"))
            db.execute("DELETE FROM " + table);
        db.update("INSERT INTO prd_goods(goods_no,entity_no,title,type,created_at,updated_at) VALUES('G','E','item','NORMAL',NOW(),NOW())");
        db.update("INSERT INTO prd_sku(sku_no,goods_no,entity_no,market,price,stock,locked_stock,sale_unit,created_at,updated_at) "
                + "VALUES('SKU','G','E','CN',100,17,0,'EA',NOW(),NOW())");
        merchants = mock(MerchantQueryPort.class);
        when(merchants.storeProfileOf(anyString())).thenAnswer(call -> Optional.of(new MerchantQueryPort.StoreProfile(
                call.getArgument(0), "E", "store", "address", "CN")));
        when(merchants.storeBusinessTypeOf(anyString())).thenReturn(Optional.of("RETAIL"));
        var raw = new StockPortImpl(sessions.getMapper(SkuMapper.class), sessions.getMapper(StockLockMapper.class),
                sessions.getMapper(StoreStockMapper.class), reader);
        raw.setRetentionGuard(mock(ExecutionStockRetentionGuard.class));
        stock = proxy(raw, StockPort.class);
        targets = targetPort(stock);
    }

    private RetailReplenishmentTargetPort targetPort(StockPort executor) {
        return targetPort(executor, sessions.getMapper(GoodsMapper.class));
    }

    private RetailReplenishmentTargetPort targetPort(StockPort executor, GoodsMapper goods) {
        var beans = new StaticListableBeanFactory(); beans.addBean("stock", executor);
        var raw = new RetailReplenishmentTargetPortImpl(reader, goods,
                sessions.getMapper(StoreStockSyncMapper.class), merchants, beans.getBeanProvider(StockPort.class));
        return proxy(raw, RetailReplenishmentTargetPort.class);
    }

    @SuppressWarnings("deprecation")
    private <T> T proxy(Object target, Class<T> type) {
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return type.cast(proxy.getProxy());
    }

    private void storeRow(String store, int quantity) {
        db.update("INSERT INTO prd_store_stock(store_no,sku_no,entity_no,stock,locked_stock,created_at,updated_at) "
                + "VALUES(?,'SKU','E',?,0,NOW(),NOW())", store, quantity);
    }

    @AfterAll void cleanup() { if (admin != null) admin.execute("DROP DATABASE IF EXISTS " + schema); }

    @Test void paidAndGiftUseOriginalLocalPoolAndIssuanceDoesNotInvalidateItsIdentity() {
        var before = inspect();
        assertThat(before.ready()).isTrue(); assertThat(before.writeEnabled()).isFalse();
        assertThat(before.target().quantityScope()).isEqualTo(QuantityScope.ENTITY);
        assertThat(before.target().poolRef()).isEqualTo("E");
        assertThat(before.onHand()).isEqualTo(17);
        var allocator = new OrderStockAllocator(stock);
        var paid = List.of(new SkuQty("SKU", 3, "S", "FUL", 2));
        var gifts = List.of(new SkuQty("SKU", 5, "S", "FUL", 2));
        assertThat(allocator.reserve("PAY", gifts, paid)).isTrue();
        assertThat(inspect().reserved()).isEqualTo(5);
        stock.confirm("PAY", 2);
        assertThat(inspect().onHand()).isEqualTo(17);
        stock.issue("FUL"); stock.issue("FUL");
        var after = transaction.execute(status -> targets.requireCurrent(S, mapping("20", "1"), before.target()));
        assertThat(after.ready()).isTrue(); assertThat(after.onHand()).isEqualTo(12);
        assertThat(after.reserved()).isZero();
        assertThat(after.target()).isEqualTo(before.target());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM prd_store_stock", Integer.class)).isZero();
    }

    @Test void cancellationOnlyReleasesAndStoreFulfillmentDeductsOnlyItsPool() {
        storeRow("S", 7); storeRow("OTHER", 11);
        stock.lock("CANCEL", List.of(new SkuQty("SKU", 2, "S")));
        stock.release("CANCEL");
        assertThat(inspect().onHand()).isEqualTo(7); assertThat(inspect().reserved()).isZero();
        stock.lock("PAY", List.of(new SkuQty("SKU", 3, "S", "FUL", 2)));
        stock.confirm("PAY", 2); stock.issue("FUL");
        assertThat(inspect().onHand()).isEqualTo(4);
        assertThat(db.queryForObject("SELECT stock FROM prd_sku", Integer.class)).isEqualTo(17);
        assertThat(db.queryForObject("SELECT stock FROM prd_store_stock WHERE store_no='OTHER'", Integer.class)).isEqualTo(11);
    }

    @Test void absentStoreRowIsZeroForSellingButCannotReceiveIntoTheEntityPool() {
        storeRow("OTHER", 11);
        assertThat(reader.observe("E", "SKU", "S").onHand()).isZero();
        assertThat(inspect().issues()).containsExactly(Issue.POOL_UNCONFIGURED);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM prd_store_stock", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT stock FROM prd_sku", Integer.class)).isEqualTo(17);
    }

    @Test void switchingPoolAndRecreatingAccountingRowCannotRebindAnOriginal() {
        var entity = inspect().target(); storeRow("S", 7);
        assertThat(transaction.execute(status -> targets.requireCurrent(S, mapping("20", "1"), entity)).issues())
                .containsExactly(Issue.TARGET_CHANGED);
        var firstStore = inspect().target();
        db.update("DELETE FROM prd_store_stock WHERE store_no='S'"); storeRow("S", 7);
        assertThat(transaction.execute(status -> targets.requireCurrent(S, mapping("20", "1"), firstStore)).issues())
                .containsExactly(Issue.TARGET_CHANGED);
    }

    @Test void unitsMarketsAndForeignRowsAreNeverGuessed() {
        db.update("UPDATE prd_sku SET sale_unit='BOX'");
        assertThat(inspect().issues()).containsExactly(Issue.UNIT_MISMATCH);
        db.update("UPDATE prd_sku SET sale_unit='EA',market='AE'");
        assertThat(inspect().issues()).containsExactly(Issue.AMBIGUOUS_STOCK);
        db.update("UPDATE prd_sku SET market='CN'");
        db.update("INSERT INTO prd_sku(sku_no,goods_no,entity_no,market,price,stock,sale_unit,created_at,updated_at) "
                + "VALUES('SKU','G','E','AE',100,100,'EA',NOW(),NOW())");
        assertThat(inspect().issues()).containsExactly(Issue.AMBIGUOUS_STOCK);
        db.update("DELETE FROM prd_sku WHERE market='AE'"); storeRow("OTHER", 1);
        db.update("UPDATE prd_store_stock SET entity_no='FOREIGN'");
        assertThat(inspect().issues()).containsExactly(Issue.STOCK_OWNER_MISMATCH);
    }

    @Test void onlyExactPositiveIntegerConversionFitsTheExistingLedger() {
        assertThat(targets.inspect(S, mapping("0.5", "2")).receivingQuantity()).isEqualTo(1);
        for (var mapping : List.of(mapping("1.5", "1"), mapping("0", "1"), mapping("-1", "1"),
                mapping("2147483648", "1"), mapping("0.0000001", "10000000")))
            assertThat(targets.inspect(S, mapping).issues()).containsExactly(Issue.INVALID_QUANTITY);
        assertThat(targets.inspect(S, mapping("2147483647", "1")).ready()).isTrue();
        assertThat(inspect().onHand()).isEqualTo(17);
    }

    @Test void actualRetailScopeAndConcreteExecutorAreMandatory() {
        when(merchants.storeBusinessTypeOf("S")).thenReturn(Optional.of("FOOD"));
        assertThat(inspect().issues()).containsExactly(Issue.NOT_RETAIL);
        when(merchants.storeBusinessTypeOf("S")).thenReturn(Optional.empty());
        assertThat(inspect().issues()).containsExactly(Issue.NOT_RETAIL);
        when(merchants.storeBusinessTypeOf("S")).thenReturn(Optional.of("RETAIL"));
        assertThat(targets.inspect(new Scope("MAIN", "FOREIGN", "S"), mapping("1", "1")).issues())
                .containsExactly(Issue.STORE_SCOPE_MISMATCH);
        assertThat(targetPort(mock(StockPort.class)).inspect(S, mapping("1", "1")).issues())
                .containsExactly(Issue.EXECUTOR_NOT_PLATFORM);
    }

    @Test void writebackAndBrokenBalancesRejectWithoutRepairingAnything() {
        db.update("INSERT INTO prd_store_stock_sync(store_no,entity_no,enabled,created_at,updated_at) "
                + "VALUES('OTHER','E',1,NOW(),NOW())");
        assertThat(inspect().issues()).containsExactly(Issue.LEGACY_WRITEBACK_ENABLED);
        db.update("UPDATE prd_store_stock_sync SET enabled=0");
        assertThat(inspect().ready()).isTrue();
        db.update("UPDATE prd_sku SET locked_stock=18");
        assertThat(inspect().issues()).containsExactly(Issue.INVALID_BALANCE);
        assertThat(db.queryForObject("SELECT locked_stock FROM prd_sku", Integer.class)).isEqualTo(18);
    }

    @Test void mandatoryRecheckSeesCurrentRowsEvenAfterAnOlderRepeatableRead() {
        var original = inspect().target();
        transaction.executeWithoutResult(status -> {
            assertThat(reader.observe("E", "SKU", "S").onHand()).isEqualTo(17);
            try (var pool = Executors.newSingleThreadExecutor()) {
                pool.submit(() -> db.update("UPDATE prd_sku SET stock=14,version=version+1"))
                        .get(10, TimeUnit.SECONDS);
            } catch (Exception failure) { throw new AssertionError(failure); }
            var current = targets.requireCurrent(S, mapping("20", "1"), original);
            assertThat(current.ready()).isTrue(); assertThat(current.onHand()).isEqualTo(14);
        });
        assertThatThrownBy(() -> targets.requireCurrent(S, mapping("20", "1"), original))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    @Test void presaleConfirmationKeepsItsOriginalNonPhysicalMeaning() {
        db.update("UPDATE prd_sku SET stock=0,presale_quota=5,cutoff_at=DATE_ADD(NOW(),INTERVAL 1 DAY)");
        var original = inspect().target();
        stock.lock("PRESALE", List.of(new SkuQty("SKU", 2, "S", "FUL", 2)));
        stock.confirm("PRESALE", 2); stock.issue("FUL");
        assertThat(inspect().onHand()).isZero(); assertThat(inspect().reserved()).isZero();
        assertThat(inspect().target()).isEqualTo(original);
        assertThat(db.queryForObject("SELECT presale FROM prd_stock_lock", Boolean.class)).isTrue();
    }

    @Test void observationUsesTheSameGoodsThenSkuLockOrderAsProductEditing() throws Exception {
        var reachedGoodsLock = new CountDownLatch(1);
        var goods = mock(GoodsMapper.class);
        when(goods.selectOne(any())).thenAnswer(call -> {
            reachedGoodsLock.countDown();
            return sessions.getMapper(GoodsMapper.class).selectOne(call.getArgument(0));
        });
        var concurrent = targetPort(stock, goods);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var inspection = transaction.execute(status -> {
                db.queryForObject("SELECT id FROM prd_goods WHERE goods_no='G' FOR UPDATE", Long.class);
                var pending = pool.submit(() -> concurrent.inspect(S, mapping("1", "1")));
                try { assertThat(reachedGoodsLock.await(10, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
                db.update("UPDATE prd_sku SET stock=15,version=version+1 WHERE sku_no='SKU'");
                return pending;
            });
            var result = inspection.get(10, TimeUnit.SECONDS);
            assertThat(result.ready()).isTrue(); assertThat(result.onHand()).isEqualTo(15);
        }
    }

    @Test void aStaleDiscoveryCannotLockTheOldGoodsAndApproveItsReassignedSku() {
        db.update("INSERT INTO prd_goods(goods_no,entity_no,title,type,created_at,updated_at) VALUES('G2','E','other','NORMAL',NOW(),NOW())");
        var original = inspect().target();
        transaction.executeWithoutResult(status -> {
            assertThat(reader.observe("E", "SKU", "S").goodsNo()).isEqualTo("G");
            try (var pool = Executors.newSingleThreadExecutor()) {
                pool.submit(() -> db.update("UPDATE prd_sku SET goods_no='G2' WHERE sku_no='SKU'"))
                        .get(10, TimeUnit.SECONDS);
            } catch (Exception failure) { throw new AssertionError(failure); }
            assertThat(targets.requireCurrent(S, mapping("20", "1"), original).issues())
                    .containsExactly(Issue.TARGET_CHANGED);
        });
        assertThat(transaction.execute(status -> targets.requireCurrent(S, mapping("20", "1"), original)).issues())
                .containsExactly(Issue.TARGET_CHANGED);
    }
}
