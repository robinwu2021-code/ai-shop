package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.inventory.domain.config.InventoryDataSourceConfig;
import ai.neargo.shop.inventory.port.MaterialCatalogQueryPortImpl;
import ai.neargo.inventory.domain.service.impl.InventoryAclServiceImpl;
import ai.neargo.inventory.domain.service.impl.LocationServiceImpl;
import ai.neargo.shop.spi.inventory.MaterialCatalogQueryPort;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import javax.sql.DataSource;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Original inventory Flyway and Mappers prove owner isolation, no writes, and the actual inventory transaction. */
class MaterialCatalogQueryPortTest {
    void run(BiConsumer<MaterialCatalogQueryPort, JdbcTemplate> action) {
        String adminUrl = System.getenv("FOOD_TEST_JDBC_URL");
        boolean mysql = adminUrl != null;
        String name = "food_material_it_" + UUID.randomUUID().toString().replace("-", "");
        JdbcTemplate admin = null;
        String url = "jdbc:h2:mem:" + name + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        if (mysql) {
            assertThat(System.getenv("FOOD_TEST_DISPOSABLE")).isEqualTo("true");
            assertThat(adminUrl).matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/mysql");
            admin = new JdbcTemplate(new DriverManagerDataSource(adminUrl, "root", ""));
            admin.execute("CREATE DATABASE " + name);
            url = adminUrl.replace("/mysql", "/" + name);
        }
        try {
            new ApplicationContextRunner().withUserConfiguration(Fixture.class)
                    .withPropertyValues("shop.inventory.enabled=true", "shop.inventory.datasource.url=" + url,
                            "shop.inventory.datasource.username=" + (mysql ? "root" : "sa"),
                            "shop.inventory.datasource.password=", "shop.inventory.flyway-locations=classpath:db/"
                                    +(mysql ? "inventory" : "inventory-h2"))
                    // 领域包读 inventory.*（ADR-046）；整机由 application.yml 桥接，这里不加载它，要直接给
                    .withPropertyValues("inventory.enabled=true", "inventory.datasource.url=" + url,
                            "inventory.datasource.username=" + (mysql ? "root" : "sa"),
                            "inventory.datasource.password=", "inventory.flyway-locations=classpath:db/"
                                    + (mysql ? "inventory" : "inventory-h2"))
                    .run(c -> {
                        assertThat(c).hasNotFailed();
                        var source = c.getBean("invDataSource", DataSource.class);
                        var sql = new JdbcTemplate(source);
                        sql.update("INSERT INTO inv_owner(owner_id,external_ref,name,status) "
                                + "VALUES('O','E','店','ACTIVE')");
                        sql.update("INSERT INTO inv_owner(owner_id,external_ref,name,status) "
                                + "VALUES('OTHER','OTHER','外店','ACTIVE')");
                        item(sql, "O", "MILK", "OWN", "KG");
                        item(sql, "O", "TEA", "SYNCED", "G");
                        item(sql, "OTHER", "MILK", "OWN", "ML");
                        item(sql, "OTHER", "FOREIGN", "OWN", "KG");
                        c.getBean("invSqlSessionFactory", SqlSessionFactory.class).getConfiguration()
                                .addInterceptor(new ReadOnlyProbe(source));
                        var primary = new JdbcTemplate(c.getBean("primary", DataSource.class));
                        primary.execute("CREATE TABLE inv_item(item_id VARCHAR(32))");
                        primary.update("INSERT INTO inv_item VALUES('PRIMARY_ONLY')");
                        action.accept(c.getBean(MaterialCatalogQueryPort.class), sql);
                    });
        } finally {
            if (admin != null) admin.execute("DROP DATABASE " + name);
        }
    }
    static void item(JdbcTemplate sql, String owner, String item, String source, String unit) {
        sql.update("INSERT INTO inv_item(owner_id,item_id,name,base_uom,weighed,data_source,status,source_on_sale) "
                + "VALUES(?,?,?, ?,1,?,'ACTIVE',0)", owner, item,"原料" + item, unit, source);
    }
    @Test
    void returnsOwnMaterialsWithoutSalesSkuAndNeverLeaksAnotherOwnersSameItemKey() {
        run((port, sql) -> {
            var facts = port.getMaterials("E", List.of("TEA", "MILK", "FOREIGN", "PRIMARY_ONLY"));
            assertThat(facts).hasSize(2);
            assertThat(facts).extracting(MaterialCatalogQueryPort.MaterialFact::itemId).containsExactly("MILK", "TEA");
            assertThat(facts.getFirst().entityNo()).isEqualTo("E");
            assertThat(facts.getFirst().baseUom()).isEqualTo("KG");
            assertThat(facts.getFirst().unitSupport()).isEqualTo("BASE_ONLY");
            assertThat(facts.getFirst().postingSupport()).isEqualTo("INTEGER_ONLY");
            assertThat(facts.getFirst().definitionHash()).matches("[a-f0-9]{64}");
            assertThat(facts.getFirst().weighed()).isEqualTo(1);
        });
    }
    @Test
    void missingOwnerAndMaterialReadsDoNotCreateOwnersItemsLocationsBalancesOrLedgers() {
        run((port, sql) -> {
            var before = Map.of("inv_owner", 2, "inv_item", 4, "inv_location", 0,
                    "inv_stock_balance", 0, "inv_ledger", 0);
            assertThat(port.getMaterials("MISSING", List.of("MILK"))).isEmpty();
            assertThat(port.getMaterials("E", List.of("MISSING"))).isEmpty();
            before.forEach((table, count) -> assertThat(
                    sql.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class))
                    .as(table).isEqualTo(count));
        });
    }
    @Test
    void archivedRetiredAndMissingUnitsAreOmittedWithoutInventingPieceOrReadingDormantDictionary() {
        run((port, sql) -> {
            item(sql, "O", "ARCHIVED", "OWN", "KG");
            item(sql, "O", "RETIRED", "SYNCED", "KG");
            item(sql,"O", "NO_UNIT", "OWN", "");
            sql.update("UPDATE inv_item SET status='ARCHIVED' WHERE item_id='ARCHIVED'");
            sql.update("UPDATE inv_item SET retired_at=CURRENT_TIMESTAMP WHERE item_id='RETIRED'");
            sql.execute("DROP TABLE inv_uom");
            assertThat(port.getMaterials("E", List.of("ARCHIVED", "RETIRED", "NO_UNIT", "MILK")))
                    .extracting(MaterialCatalogQueryPort.MaterialFact::itemId).containsExactly("MILK");
            sql.update("UPDATE inv_owner SET status='ARCHIVED' WHERE external_ref='E'");
            assertThat(port.getMaterials("E", List.of("MILK"))).isEmpty();
        });
    }
    @Test
    void collationEquivalentOwnerAliasesNeverRelabelOrHashFactsForAnUnknownExactKey() {
        run((port, sql) -> {
            boolean h2;
            try (var connection = sql.getDataSource().getConnection()) {
                h2 = connection.getMetaData().getDatabaseProductName().equals("H2");
            } catch (SQLException failure) {
                throw new IllegalStateException(failure);
            }
            if (h2) sql.execute("ALTER TABLE inv_owner ALTER COLUMN external_ref VARCHAR_IGNORECASE(64)");
            // H2 proves the case path; the original MariaDB collation also proves the accent path.
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM inv_owner WHERE external_ref=?",
                    Integer.class, "e")).isEqualTo(1);
            assertThat(port.getMaterials("e", List.of("MILK"))).isEmpty();
            if (!h2) {
                assertThat(sql.queryForObject("SELECT COUNT(*) FROM inv_owner WHERE external_ref=?",
                        Integer.class, "É")).isEqualTo(1);
                assertThat(port.getMaterials("É", List.of("MILK"))).isEmpty();
            }
            assertThat(port.getMaterials("E", List.of("MILK"))).hasSize(1);
        });
    }

    @Test
    void contentHashTracksDefinitionsAndDoesNotPretendToBeAnInventoryRevisionOrBalanceVersion() {
        run((port, sql) -> {
            var one = port.getMaterials("E", List.of("MILK")).getFirst();
            sql.update("UPDATE inv_item SET default_cost_minor=999,safety_stock=5 "
                    + "WHERE owner_id='O' AND item_id='MILK'");
            assertThat(port.getMaterials("E", List.of("MILK")).getFirst()).isEqualTo(one);
            sql.update("UPDATE inv_item SET base_uom='G' WHERE owner_id='O' AND item_id='MILK'");
            var two = port.getMaterials("E", List.of("MILK")).getFirst();
            assertThat(two.definitionHash()).isNotEqualTo(one.definitionHash());
            assertThat(two.baseUom()).isEqualTo("G");
        });
    }
    @Test
    void badInputsFailBeforeSqlAndDatabaseFailureNeverBecomesEmptyOrReadsPrimary() {
        run((port, sql) -> {
            sql.execute("DROP TABLE inv_item");
            for (var ids : List.of(List.<String>of(), List.of("MILK", "MILK"),
                    List.of("x".repeat(33)), List.of(" MILK"))) {
                assertThatThrownBy(() -> port.getMaterials("E", ids)).isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
            }
            assertThatThrownBy(() -> port.getMaterials("E", List.of("MILK"))).isInstanceOf(RuntimeException.class);
        });
    }
    @Intercepts(@Signature(type = Executor.class, method = "query",
            args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}))
    static class ReadOnlyProbe implements Interceptor {
        private final DataSource source;
        ReadOnlyProbe(DataSource source) { this.source = source; }
        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
            assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                    .isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            assertThat(TransactionSynchronizationManager.hasResource(source)).isTrue();
            return invocation.proceed();
        }
    }
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    @Import({InventoryDataSourceConfig.class, InventoryAclServiceImpl.class, LocationServiceImpl.class,
            MaterialCatalogQueryPortImpl.class})
    static class Fixture {
        @Bean
        @Primary
        DataSource primary() {
            return new DriverManagerDataSource("jdbc:h2:mem:primary_" + UUID.randomUUID()
                    + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        }
    }
}
