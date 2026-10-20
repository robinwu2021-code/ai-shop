package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.neargo.shop.common.AuditMetaObjectHandler;
import ai.neargo.shop.food.config.FoodSchemaReady;
import ai.neargo.shop.food.mapper.FoodMappers;
import ai.neargo.shop.food.server.FoodApplication;
import ai.neargo.shop.food.spi.StoreOperationInternalApi;
import ai.neargo.shop.svc.InternalHttp;
import org.springframework.http.MediaType;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import java.nio.file.Path;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;

@EnabledIfEnvironmentVariable(named = "FOOD_TEST_DISPOSABLE", matches = "true")
class FoodServerPrimaryDatabaseTest {
    @Test void actualStandaloneServerReusesPrimaryAssemblyAndPlatformAudit() {
        String adminUrl = System.getenv("FOOD_TEST_JDBC_URL");
        assertThat(adminUrl).matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/mysql");
        var admin = new JdbcTemplate(new DriverManagerDataSource(adminUrl, "root", ""));
        String database = "food_server_it_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE " + database);
        try {
            String url = adminUrl.replace("/mysql", "/" + database);
            var source = new DriverManagerDataSource(url, "root", "");
            var fixture = Path.of(System.getProperty("food.reactor.root"), "food-core/src/test/resources/food-primary-fixture.sql");
            new ResourceDatabasePopulator(new FileSystemResource(fixture)).execute(source);
            Flyway.configure().dataSource(source).locations("classpath:db/industry/food").table("fnb_flyway_history")
                    .baselineOnMigrate(true).baselineVersion("0").load().migrate();
            new WebApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                    .withUserConfiguration(FoodApplication.class)
                    .withPropertyValues("spring.datasource.url=" + url, "spring.datasource.username=root", "spring.datasource.password=",
                            "shop.services.internal-token=food-disposable-server-test",
                            "spring.autoconfigure.exclude=org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration,org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration")
                    .run(context -> {
                        assertThat(context).hasNotFailed().hasSingleBean(DataSource.class).hasSingleBean(SqlSessionFactory.class)
                                .hasSingleBean(PlatformTransactionManager.class).hasSingleBean(MetaObjectHandler.class)
                                .hasSingleBean(FoodMappers.TableAreaMapper.class).doesNotHaveBean(Flyway.class);
                        assertThat(context.getBean(MetaObjectHandler.class)).isInstanceOf(AuditMetaObjectHandler.class);
                        assertThat(context.getBean(FoodSchemaReady.class).ready()).isTrue();
                        assertThat(context.getEnvironment().getProperty("spring.flyway.enabled", Boolean.class)).isFalse();
                        var sql = new JdbcTemplate(context.getBean(DataSource.class));
                        sql.update("INSERT INTO fnb_store_policy(tenant_no,entity_no,policy_no,store_no,timezone,"
                                + "cutover_minute,effective_from,pay_policy,capabilities,settings,created_at,created_by,updated_at,updated_by) VALUES "
                                + "('MAIN','E','P1','A','Asia/Shanghai',240,'2026-10-01 00:00:00',"
                                + "'POSTPAY','[\"TABLE_CONFIG\"]','{}',NOW(3),'SYSTEM',NOW(3),'SYSTEM')");
                        var mvc = MockMvcBuilders.webAppContextSetup(context)
                                .addFilters(context.getBean(FilterChainProxy.class)).build();
                        String body = "{\"entityNo\":\"E\",\"storeNo\":\"A\",\"at\":\"2026-10-20T12:00:00Z\"}";
                        mvc.perform(post(StoreOperationInternalApi.POLICY_PATH).contentType(MediaType.APPLICATION_JSON)
                                        .content(body)).andExpect(status().isUnauthorized());
                        mvc.perform(post(StoreOperationInternalApi.POLICY_PATH)
                                        .header(InternalHttp.TOKEN_HEADER,"food-disposable-server-test")
                                        .contentType(MediaType.APPLICATION_JSON).content(body))
                                .andExpect(status().isOk()).andExpect(jsonPath("$.policyNo").value("P1"))
                                .andExpect(jsonPath("$.payPolicy").value("POSTPAY"))
                                .andExpect(jsonPath("$.data").doesNotExist());
                        mvc.perform(post(StoreOperationInternalApi.POLICY_PATH)
                                        .header(InternalHttp.TOKEN_HEADER,"food-disposable-server-test")
                                        .contentType(MediaType.APPLICATION_JSON).content(body.replace("\"E\"","\"FOREIGN\"")))
                                .andExpect(status().isNotFound());
                        mvc.perform(post(StoreOperationInternalApi.POLICY_PATH)
                                        .header(InternalHttp.TOKEN_HEADER,"food-disposable-server-test")
                                        .contentType(MediaType.APPLICATION_JSON).content("{\"storeNo\":\"A\"}"))
                                .andExpect(status().isBadRequest());
                        assertThat(sql.queryForObject("SELECT COUNT(*) FROM fnb_store_policy",Integer.class)).isEqualTo(1);
                        assertThat(sql.queryForObject("SELECT COUNT(*) FROM sys_idempotent",Integer.class)).isZero();
                        assertThat(sql.queryForObject("SELECT COUNT(*) FROM sys_outbox",Integer.class)).isZero();
                    });
        } finally { admin.execute("DROP DATABASE " + database); }
    }
}
