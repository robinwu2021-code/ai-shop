package ai.neargo.inventory.svc;

import ai.neargo.inventory.domain.config.InventoryDataSourceConfig.InvMigrated;
import ai.neargo.inventory.domain.service.StockQueryService;
import ai.neargo.inventory.guard.InstanceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 闸门 I5：只连库存库、空库、无 Redis、无任何商城配置，进程起得来，迁移跑完。
 *
 * <p>迁移用 H2 等价脚本（{@code db/inventory-h2}，由 gen-test-schema.py 从真迁移重放生成）——
 * 走的是真实的建表路径，不是绕过它。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "inventory.flyway-locations=classpath:db/inventory-h2",
        "inventory.datasource.url=jdbc:h2:mem:inv_standalone;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "inventory.datasource.username=sa",
        "inventory.datasource.password=",
        "inventory.instance.code=TEST",
        "inventory.instance.allowed-systems=SHOP,SHAREHUB,CHARGING"})
class StandaloneContextTest {

    @Autowired ApplicationContext context;
    @Autowired InvMigrated migrated;
    @Autowired StockQueryService stockQuery;
    @Autowired InstanceProperties instance;
    @Autowired @Qualifier("invDataSource") DataSource invDataSource;

    @Test
    void bootsOnEmptyDatabaseWithoutAnyShopBean() {
        assertThat(migrated.historyTable()).isEqualTo("inv_flyway_history");
        assertThat(stockQuery).isNotNull();
        String[] shopBeans = Arrays.stream(context.getBeanDefinitionNames())
                .filter(name -> {
                    Class<?> type = context.getType(name);
                    return type != null && type.getName().startsWith("ai.neargo.shop.");
                })
                .toArray(String[]::new);
        assertThat(shopBeans).as("独立进程里不该有任何商城 Bean").isEmpty();
    }

    @Test
    void migrationsCreatedTheInventoryTables() {
        Integer tables = new JdbcTemplate(invDataSource).queryForObject(
                "select count(*) from information_schema.tables where table_name like 'inv\\_%'", Integer.class);
        assertThat(tables).isGreaterThanOrEqualTo(30);
    }

    @Test
    void instanceConfigurationComesFromEnvironment() {
        assertThat(instance.getCode()).isEqualTo("TEST");
        assertThat(instance.getAllowedSystems()).containsExactly("SHOP", "SHAREHUB", "CHARGING");
    }
}
