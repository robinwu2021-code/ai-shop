package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.neargo.shop.event.outbox.runtime.JdbcOutboxStore;
import ai.neargo.shop.event.outbox.runtime.UnifiedOutboxFacade;
import ai.neargo.shop.food.dto.CreateModifierReq;
import ai.neargo.shop.food.dto.ModifierComponentInput;
import ai.neargo.shop.food.dto.UpdateModifierReq;

import ai.neargo.shop.spi.product.GoodsCatalogInternalApi;
import ai.neargo.shop.food.dto.ListModifiersQuery;
import ai.neargo.shop.food.server.FoodApplication;
import ai.neargo.shop.food.service.ModifierService;
import ai.neargo.shop.food.spi.ModifierInternalApi;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
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

/** Actual frozen migration, primary JSON, service-token ingress and original typed HTTP response shapes. */
@EnabledIfEnvironmentVariable(named="FOOD_TEST_DISPOSABLE", matches="true")
class FoodModifierPrimaryDatabaseTest {
    @Test void typedCreateAndQueriesUseOriginalPrimaryWithoutTouchingGoodsOrOrders() throws Exception {
        String adminUrl = System.getenv("FOOD_TEST_JDBC_URL");
        assertThat(adminUrl).matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/mysql");
        var admin = new JdbcTemplate(new DriverManagerDataSource(adminUrl, "root", ""));
        String database = "food_modifier_it_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE " + database);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext(GoodsCatalogInternalApi.SKU_PATH, ex -> {
            assertThat(ex.getRequestHeaders().getFirst(InternalHttp.TOKEN_HEADER)).isEqualTo("wire");
            String query = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(query).contains("\"entityNo\":\"E\"", "\"storeNo\":\"A\"", "\"skuNo\":\"SKU\"");
            byte[] bytes = "{\"skuNo\":\"SKU\",\"goodsNo\":\"G\",\"entityNo\":\"E\",\"storeNo\":\"A\",\"version\":0}"
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, bytes.length); ex.getResponseBody().write(bytes); ex.close();
        });
        server.start();
        try {
            String url = adminUrl.replace("/mysql", "/" + database);
            var source = new DriverManagerDataSource(url, "root", "");
            var fixture = Path.of(System.getProperty("food.reactor.root"),
                    "food-core/src/test/resources/food-primary-fixture.sql");
            new ResourceDatabasePopulator(new FileSystemResource(fixture)).execute(source);
            Flyway.configure().dataSource(source).locations("classpath:db/industry/food").table("fnb_flyway_history")
                    .baselineOnMigrate(true).baselineVersion("0").load().migrate();
            new WebApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                    .withUserConfiguration(FoodApplication.class, Route.class)
                    .withPropertyValues("spring.datasource.url=" + url, "spring.datasource.username=root",
                            "spring.datasource.password=", "shop.services.internal-token=wire",
                            "shop.services.targets.PLATFORM=http://127.0.0.1:" + server.getAddress().getPort(),
                            "spring.autoconfigure.exclude=org.springframework.boot.data.redis.autoconfigure."
                                    + "DataRedisAutoConfiguration,org.springframework.boot.data.redis.autoconfigure."
                                    + "DataRedisRepositoriesAutoConfiguration")
                    .run(c -> {
                        assertThat(c).hasNotFailed().hasSingleBean(ModifierService.class);
                        var mvc = MockMvcBuilders.webAppContextSetup(c)
                                .addFilters(c.getBean(FilterChainProxy.class)).build();
                        for (String path : List.of(ModifierInternalApi.CREATE_PATH, ModifierInternalApi.UPDATE_PATH,
                                ModifierInternalApi.LIST_PATH)) {
                            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                                    .andExpect(status().isUnauthorized());
                            mvc.perform(post(path).header(InternalHttp.TOKEN_HEADER, "wire")
                                    .contentType(MediaType.APPLICATION_JSON).content("{}"))
                                    .andExpect(status().isBadRequest());
                        }
                        server.createContext("/internal/food/modifiers/", ex -> {
                            try {
                                var result = mvc.perform(post(ex.getRequestURI().getPath())
                                        .header(InternalHttp.TOKEN_HEADER,
                                                ex.getRequestHeaders().getFirst(InternalHttp.TOKEN_HEADER))
                                        .contentType(MediaType.APPLICATION_JSON)
                                                .content(ex.getRequestBody().readAllBytes()))
                                        .andReturn().getResponse();
                                byte[] bytes = result.getContentAsByteArray();
                                ex.getResponseHeaders().add("Content-Type", "application/json");
                                ex.sendResponseHeaders(result.getStatus(), bytes.length);
                                ex.getResponseBody().write(bytes);
                            } catch (Exception failure) { throw new IllegalStateException(failure); }
                            finally { ex.close(); }
                        });
                        var http = new InternalHttp(name -> Optional.of("http://127.0.0.1:"
                                + server.getAddress().getPort()), "wire");
                        var api = http.client(ServiceName.FOOD, ModifierInternalApi.class, Duration.ofSeconds(5));
                        var req = new CreateModifierReq("珍珠", "ADDON", "SKU", List.of());
                        var command = new ModifierInternalApi.CreateCommand("E", "A", "operator", "original", req);
                        var first = api.createModifier(command);
                        assertThat(first.status()).isEqualTo("DRAFT");
                        assertThat(api.createModifier(command)).isEqualTo(first);
                        var combo = api.createModifier(new ModifierInternalApi.CreateCommand("E", "A", "operator", "combo",
                                new CreateModifierReq("双拼", "COMBO", "SKU",
                                        List.of(new ModifierComponentInput(first.configNo(), new BigDecimal("1.5"))))));
                        var update = new ModifierInternalApi.UpdateCommand("E", "A", combo.configNo(), "operator", "update",
                                new UpdateModifierReq("双拼新版", "COMBO", null,
                                        List.of(new ModifierComponentInput(first.configNo(), new BigDecimal("2"))), 0L));
                        assertThat(api.updateModifier(update).version()).isEqualTo(1);
                        assertThat(api.updateModifier(update).version()).isEqualTo(1);
                        var page = api.listModifiers(new ModifierInternalApi.Lookup("E", "A", new ListModifiersQuery(1, 20)));
                        assertThat(page.total()).isEqualTo(2);
                        var view = page.records().stream().filter(v -> combo.configNo().equals(v.configNo()))
                                .findFirst().orElseThrow();
                        assertThat(view.data().salesSkuNo()).isNull();
                        assertThat(view.data().components().getFirst().quantity()).isEqualByComparingTo("2");
                        assertThat(api.listModifiers(new ModifierInternalApi.Lookup("OTHER", "A", null)).records()).isEmpty();
                        assertThatThrownBy(() -> api.updateModifier(new ModifierInternalApi.UpdateCommand("OTHER", "A",
                                first.configNo(), "operator", "foreign", new UpdateModifierReq("bad", "ADDON", null, List.of(), 0L))))
                                .isInstanceOfSatisfying(ServiceCallException.class, e -> assertThat(e.statusCode()).isEqualTo(404));
                        var sql = new JdbcTemplate(c.getBean(DataSource.class));
                        assertThat(sql.queryForObject("SELECT COUNT(*) FROM fnb_modifier", Integer.class)).isEqualTo(2);
                        assertThat(sql.queryForObject("SELECT COUNT(*) FROM fnb_modifier_component", Integer.class)).isOne();
                        for (String table : List.of("fnb_action_log", "sys_outbox", "sys_idempotent")) {
                            assertThat(sql.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class))
                                    .as(table).isEqualTo(3);
                        }
                        for (String table : List.of("prd_goods", "ord_order")) {
                            assertThat(sql.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).isZero();
                        }
                    });
        } finally {
            server.stop(0);
            executor.shutdownNow();
            admin.execute("DROP DATABASE " + database);
        }
    }

    @Configuration(proxyBeanMethods=false)
    static class Route {
        @Bean UnifiedOutboxFacade facade(DataSource source, PlatformTransactionManager manager, ObjectMapper json) {
            var facade = new UnifiedOutboxFacade(json);
            facade.register(new JdbcOutboxStore(JdbcOutboxStore.Source.PLATFORM, source, manager, json), List.of());
            facade.setArchiveOnly(Set.of("PLATFORM:FOOD_MODIFIER_CHANGED"));
            return facade;
        }
    }
}
