package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.ShopApplication;
import ai.neargo.shop.idem.SysIdempotentMapper;
import ai.neargo.shop.inventory.mapper.InventoryExactSupportMapper;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanPort;
import ai.neargo.shop.trade.mapper.IndustryPartialFulfillmentMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Real embedded host startup against a disposable native database; never uses deployment
 * credentials.
 */
@EnabledIfEnvironmentVariable(named = "FOOD_HOST_SMOKE_DISPOSABLE", matches = "true")
@ActiveProfiles({"api", "ops", "testcfg"})
@SpringBootTest(
        classes = ShopApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "shop.food.enabled=true",
            "shop.food.deployment=embedded",
            "shop.food.migration.mode=migrate",
            "shop.seed.enabled=false",
            "shop.job.enabled=false",
            "shop.outbox.enabled=false",
            "shop.pay.deployment=standalone",
            "spring.sql.init.mode=never",
            "spring.flyway.enabled=true",
            "spring.flyway.placeholder-replacement=false"
        })
class FoodEmbeddedStartupIT {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String root = System.getenv("FOOD_HOST_SMOKE_JDBC_URL");
        if (root == null || !root.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/food_host_smoke")) {
            throw new IllegalArgumentException("Disposable loopback database required");
        }
        registry.add(
                "spring.datasource.url", () -> root + "?allowPublicKeyRetrieval=true&useSSL=false");
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("shop.inventory.enabled", () -> "true");
        registry.add(
                "shop.inventory.datasource.url",
                () -> root + "_inv?allowPublicKeyRetrieval=true&useSSL=false");
        registry.add("shop.inventory.datasource.username", () -> "root");
        registry.add("shop.inventory.datasource.password", () -> "");
    }

    @Autowired ApplicationContext context;
    @Autowired JdbcTemplate sql;

    @Test
    void nativeMigrationsAndRealHostStartTogether() throws ClassNotFoundException {
        assertThat(context.getBean("foodMpCatalogController")).isNotNull();
        assertThat(context.getBean("mpCatalogController")).isNotNull();
        assertThat(context.getBeansOfType(SysIdempotentMapper.class)).hasSize(1);
        assertThat(context.getBeansOfType(InventoryDemandPlanPort.class)).hasSize(1);
        assertThat(context.getBeansOfType(Class.forName("ai.neargo.shop.food.service.CheckBusinessGuardService"))).hasSize(1);
        assertThat(context.getBeansOfType(Class.forName("ai.neargo.shop.food.service.DiningOrderAuthorityService"))).hasSize(1);
        assertThat(context.getBean(IndustryPartialFulfillmentMapper.class).pending(1)).isEmpty();
        assertThat(
                        context.getBean(InventoryExactSupportMapper.class)
                                .policy("absent", "absent", false))
                .isEmpty();
        assertThat(
                        sql.queryForObject(
                                "SELECT MAX(CAST(version AS UNSIGNED)) FROM fnb_flyway_history"
                                    + " WHERE success=1",
                                Integer.class))
                .isEqualTo(25);
    }
}
