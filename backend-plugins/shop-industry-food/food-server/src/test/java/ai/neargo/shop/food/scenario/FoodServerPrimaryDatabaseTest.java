package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.neargo.shop.common.AuditMetaObjectHandler;
import ai.neargo.shop.event.outbox.runtime.JdbcOutboxStore;
import ai.neargo.shop.event.outbox.runtime.UnifiedOutboxFacade;
import ai.neargo.shop.food.config.FoodSchemaReady;
import ai.neargo.shop.food.mapper.FoodMappers;
import ai.neargo.shop.food.server.FoodApplication;
import ai.neargo.shop.food.spi.ServiceCalendarInternalApi;
import ai.neargo.shop.food.spi.StoreOperationInternalApi;
import ai.neargo.shop.food.spi.StorePolicyPublicationInternalApi;
import ai.neargo.shop.svc.InternalHttp;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

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
                    .withUserConfiguration(FoodApplication.class, PublicationRoute.class)
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
                        String command = "{\"entityNo\":\"E\",\"storeNo\":\"A\",\"actorNo\":\"trusted-actor\","
                                + "\"idempotencyKey\":\"original-publication\","
                                + "\"policy\":{\"timezone\":\"Asia/Shanghai\","
                                + "\"cutoverMinute\":240,\"effectiveFrom\":\"2026-10-22T12:00:00Z\","
                                + "\"payPolicy\":\"POSTPAY\",\"capabilities\":[],\"version\":0}}";
                        String receipt = mvc.perform(post(StorePolicyPublicationInternalApi.POLICY_CREATE_PATH)
                                        .header(InternalHttp.TOKEN_HEADER,"food-disposable-server-test")
                                        .contentType(MediaType.APPLICATION_JSON).content(command))
                                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCEEDED"))
                                .andExpect(jsonPath("$.version").value(1))
                                .andReturn().getResponse().getContentAsString();
                        mvc.perform(post(StorePolicyPublicationInternalApi.POLICY_CREATE_PATH)
                                        .header(InternalHttp.TOKEN_HEADER,"food-disposable-server-test")
                                        .contentType(MediaType.APPLICATION_JSON).content(command))
                                .andExpect(status().isOk()).andExpect(result -> assertThat(
                                        result.getResponse().getContentAsString()).isEqualTo(receipt));
                        mvc.perform(post(StorePolicyPublicationInternalApi.POLICY_CREATE_PATH)
                                        .header(InternalHttp.TOKEN_HEADER,"food-disposable-server-test")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(command.replace("2026-10-22", "2026-10-23")))
                                .andExpect(status().isConflict());
                        assertThat(sql.queryForObject("SELECT COUNT(*) FROM fnb_store_policy",Integer.class))
                                .isEqualTo(2);
                        assertThat(sql.queryForObject("SELECT COUNT(*) FROM fnb_action_log",Integer.class)).isOne();
                        assertThat(sql.queryForObject("SELECT operator_no FROM fnb_action_log",String.class))
                                .isEqualTo("trusted-actor");
                        assertThat(sql.queryForObject("SELECT COUNT(*) FROM sys_idempotent",Integer.class)).isOne();
                        assertThat(sql.queryForObject("SELECT COUNT(*) FROM sys_outbox",Integer.class)).isOne();
                        String calendar = "{\"entityNo\":\"E\",\"storeNo\":\"A\",\"actorNo\":\"trusted-actor\","
                                + "\"localDate\":\"2026-10-20\",\"idempotencyKey\":\"calendar-create\","
                                + "\"calendar\":{\"mode\":\"CLOSED\",\"intervals\":[],\"version\":0}}";
                        for (int retry=0; retry<2; retry++) {
                            mvc.perform(post(ServiceCalendarInternalApi.SAVE_PATH)
                                            .header(InternalHttp.TOKEN_HEADER,"food-disposable-server-test")
                                            .contentType(MediaType.APPLICATION_JSON).content(calendar))
                                    .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
                        }
                        String update = calendar.replace("calendar-create", "calendar-update")
                                .replace("\"CLOSED\",\"intervals\":[]", "\"OPEN\",\"intervals\":[\"22:00-02:00\"]")
                                .replace("\"version\":0", "\"version\":1");
                        mvc.perform(post(ServiceCalendarInternalApi.SAVE_PATH)
                                        .header(InternalHttp.TOKEN_HEADER,"food-disposable-server-test")
                                        .contentType(MediaType.APPLICATION_JSON).content(update))
                                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
                        mvc.perform(post(ServiceCalendarInternalApi.SAVE_PATH)
                                        .header(InternalHttp.TOKEN_HEADER,"food-disposable-server-test")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(calendar.replace("calendar-create", "calendar-stale")))
                                .andExpect(status().isConflict());
                        assertThat(sql.queryForObject("SELECT COUNT(*) FROM fnb_service_calendar",Integer.class))
                                .isOne();
                        assertThat(sql.queryForObject("SELECT version FROM fnb_service_calendar",Long.class))
                                .isEqualTo(2);
                        assertThat(sql.queryForObject("SELECT policy_no FROM fnb_service_calendar",String.class))
                                .isEqualTo("P1");
                        for (String table:List.of("fnb_action_log","sys_idempotent","sys_outbox")) {
                            assertThat(sql.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class)).isEqualTo(3);
                        }
                    });
        } finally { admin.execute("DROP DATABASE " + database); }
    }
    /** Only a disposable test source/route; production registration remains owned by the platform. */
    @Configuration(proxyBeanMethods = false)
    static class PublicationRoute {
        @Bean UnifiedOutboxFacade policyFacade(DataSource source, PlatformTransactionManager manager,
                ObjectMapper json) {
            var facade = new UnifiedOutboxFacade(json);
            facade.register(new JdbcOutboxStore(JdbcOutboxStore.Source.PLATFORM, source, manager, json), List.of());
            facade.setArchiveOnly(Set.of("PLATFORM:FOOD_STORE_POLICY_PUBLISHED",
                    "PLATFORM:FOOD_SERVICE_CALENDAR_SAVED"));
            return facade;
        }
    }
}
