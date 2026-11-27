package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.common.AuditMetaObjectHandler;
import ai.neargo.shop.common.config.IndustryTableMapper;
import ai.neargo.shop.common.config.ModuleDataSourceRegistrar;
import ai.neargo.shop.common.config.PrimaryPersistenceConfiguration;
import ai.neargo.shop.food.config.FoodIndustryTables;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration;
import ai.neargo.shop.food.config.FoodProperties;
import ai.neargo.shop.food.mapper.FoodMappers;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.Arrays;
import java.util.UUID;

/**
 * 新库测试（TDD-餐饮域迁移新库 P1，AC1、AC4、AC5）：行业句柄绑到按 {@code db/n_industry/V1、V2} 新建的一次性库，
 * 交易句柄留在主库（P2 前）。主库按餐饮旧迁移建表后删掉行业句柄负责的表，走错句柄的访问会直接报表不存在。
 */
@EnabledIfEnvironmentVariable(named = "FOOD_TEST_DISPOSABLE", matches = "true")
class FoodIndustryDatabaseTest {

    @Test
    void industryMappersResolveOnTheNewIndustryDatabaseAndPrimaryNoLongerHoldsThem() {
        String url = System.getenv("FOOD_TEST_JDBC_URL");
        assertThat(url).matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/(?:mysql|information_schema)");
        String user = System.getenv().getOrDefault("FOOD_TEST_DB_USER", "root");
        String password = System.getenv().getOrDefault("FOOD_TEST_DB_PASSWORD", "");
        String base = url.substring(0, url.lastIndexOf('/') + 1);
        String primary = "food_ind_it_" + UUID.randomUUID().toString().replace("-", "");
        String industry = primary + "_industry";
        var admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        try {
            admin.execute("CREATE DATABASE " + primary);
            admin.execute("CREATE DATABASE " + industry);
            var primarySource = new DriverManagerDataSource(base + primary, user, password);
            var industrySource = new DriverManagerDataSource(base + industry, user, password);
            new ResourceDatabasePopulator(new ClassPathResource("food-primary-fixture.sql"))
                    .execute(primarySource);
            Flyway.configure().dataSource(primarySource).table("fnb_flyway_history")
                    .locations("classpath:db/industry/food").baselineOnMigrate(true)
                    .baselineVersion("0").load().migrate();
            var primarySql = new JdbcTemplate(primarySource);
            for (String table : FoodIndustryTables.NAMES) primarySql.execute("DROP TABLE IF EXISTS " + table);
            for (String table : FoodIndustryTables.RENAMED.keySet())
                primarySql.execute("DROP TABLE IF EXISTS " + table);
            new ResourceDatabasePopulator(
                            new ClassPathResource("db/n_industry/V1__industry_design.sql"),
                            new ClassPathResource("db/n_industry/V2__industry_supplement.sql"))
                    .execute(industrySource);

            new ApplicationContextRunner()
                    .withUserConfiguration(PersistenceOnly.class)
                    .withBean(FoodProperties.class,
                            () -> new FoodProperties(true, FoodProperties.Deployment.STANDALONE))
                    .withBean(AuditMetaObjectHandler.class)
                    .withPropertyValues(
                            "shop.food.enabled=true",
                            "spring.datasource.url=" + base + primary,
                            "spring.datasource.username=" + user,
                            "spring.datasource.password=" + password,
                            "shop.modules.industry.datasource=foodIndustryTest",
                            "shop.datasources.foodIndustryTest.url=" + base + industry,
                            "shop.datasources.foodIndustryTest.username=" + user,
                            "shop.datasources.foodIndustryTest.password=" + password)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        // 行业句柄下的每个注解 Mapper 都能在行业库上查询；主库里已经没有这些表。
                        var industryMappers = Arrays.stream(FoodMappers.class.getDeclaredClasses())
                                .filter(IndustryTableMapper.class::isAssignableFrom)
                                .toList();
                        assertThat(industryMappers).isNotEmpty();
                        for (var type : industryMappers) {
                            var mapper = (IndustryTableMapper<?>) context.getBean(type);
                            // selectList 会按实体的全部字段取列：列名对不上会直接报错。
                            assertThat(mapper.selectList(null)).as(type.getSimpleName()).isEmpty();
                        }
                        assertThat(primarySql.queryForObject(
                                        "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=?"
                                                + " AND table_name='fnb_table'",
                                        Integer.class,
                                        primary))
                                .isZero();
                    });
        } finally {
            admin.execute("DROP DATABASE IF EXISTS " + industry);
            admin.execute("DROP DATABASE IF EXISTS " + primary);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import({PrimaryPersistenceConfiguration.class, ModuleDataSourceRegistrar.class,
            FoodPersistenceConfiguration.class})
    static class PersistenceOnly {}
}
