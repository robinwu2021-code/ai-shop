package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.neargo.shop.event.outbox.runtime.JdbcOutboxStore;
import ai.neargo.shop.event.outbox.runtime.UnifiedOutboxFacade;
import ai.neargo.shop.food.dto.CreateRecipeReq;
import ai.neargo.shop.food.dto.ListRecipesQuery;
import ai.neargo.shop.food.dto.RecipeLineInput;
import ai.neargo.shop.food.dto.ReplaceRecipeDefinitionReq;
import ai.neargo.shop.food.server.FoodApplication;
import ai.neargo.shop.food.service.RecipeService;
import ai.neargo.shop.food.spi.RecipeInternalApi;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.inventory.MaterialCatalogInternalApi;
import ai.neargo.shop.spi.inventory.MaterialCatalogQueryPort;
import ai.neargo.shop.spi.product.GoodsCatalogInternalApi;
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
import java.util.concurrent.atomic.AtomicReference;
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
class FoodRecipePrimaryDatabaseTest {
    @Test void typedCreateAndQueriesUseOriginalPrimaryWithoutTouchingGoodsOrOrders() throws Exception {
        String adminUrl = System.getenv("FOOD_TEST_JDBC_URL");
        assertThat(adminUrl).matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/mysql");
        var admin = new JdbcTemplate(new DriverManagerDataSource(adminUrl, "root", ""));
        String database = "food_recipe_it_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE " + database);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newCachedThreadPool();
        var bridgeFailure = new AtomicReference<Throwable>();
        server.setExecutor(executor);
        server.createContext(GoodsCatalogInternalApi.SKU_PATH, ex -> {
            assertThat(ex.getRequestHeaders().getFirst(InternalHttp.TOKEN_HEADER)).isEqualTo("wire");
            String query = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var mapper = new ObjectMapper();
            String sku = mapper.readTree(query).get("skuNo").asText();
            assertThat(query).contains("\"entityNo\":\"E\"", "\"storeNo\":\"A\"");
            byte[] bytes = ("{\"skuNo\":\"" + sku
                    + "\",\"goodsNo\":\"G\",\"entityNo\":\"E\",\"storeNo\":\"A\",\"version\":0}")
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, bytes.length); ex.getResponseBody().write(bytes); ex.close();
        });
        server.createContext(MaterialCatalogInternalApi.BATCH_PATH, ex -> {
            assertThat(ex.getRequestHeaders().getFirst(InternalHttp.TOKEN_HEADER)).isEqualTo("wire");
            String query = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(query).contains("\"entityNo\":\"E\"", "\"itemIds\":[\"MILK\"]");
            var fact = new MaterialCatalogQueryPort.MaterialFact("MILK", "E", "牛奶", "KG", 1, "OWN",
                    "BASE_ONLY", "INTEGER_ONLY",
                    MaterialCatalogFacts.definitionHash("MILK", "E", "牛奶", "KG", 1, "OWN"));
            byte[] bytes = new ObjectMapper().writeValueAsBytes(List.of(fact));
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
                        assertThat(c).hasNotFailed().hasSingleBean(RecipeService.class);
                        var mvc = MockMvcBuilders.webAppContextSetup(c)
                                .addFilters(c.getBean(FilterChainProxy.class)).build();
                        for (String path : List.of(RecipeInternalApi.CREATE_PATH, RecipeInternalApi.REPLACE_PATH,
                                RecipeInternalApi.LIST_PATH, RecipeInternalApi.GET_PATH)) {
                            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                                    .andExpect(status().isUnauthorized());
                            mvc.perform(post(path).header(InternalHttp.TOKEN_HEADER, "wire")
                                    .contentType(MediaType.APPLICATION_JSON).content("{}"))
                                    .andExpect(status().isBadRequest());
                        }
                        server.createContext("/internal/food/", ex -> {
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
                            } catch (Throwable failure) {
                                bridgeFailure.compareAndSet(null, failure);
                                ex.sendResponseHeaders(500, -1);
                            }
                            finally { ex.close(); }
                        });
                        var http = new InternalHttp(name -> Optional.of("http://127.0.0.1:"
                                + server.getAddress().getPort()), "wire");
                        var api = http.client(ServiceName.FOOD, RecipeInternalApi.class, Duration.ofSeconds(5));
                        var create = new RecipeInternalApi.CreateCommand("E", "A", "operator", "create",
                                new CreateRecipeReq("TEA", "DEFAULT", BigDecimal.ONE, "EA"));
                        var first = api.createRecipe(create);
                        assertThat(api.createRecipe(create)).isEqualTo(first);
                        var id = first.recipe().recipeNo();
                        var definition = new ReplaceRecipeDefinitionReq(0L, BigDecimal.ONE, "EA",
                                List.of(new RecipeLineInput(null, "MILK", new BigDecimal("0.123456"), "KG",
                                        new BigDecimal("0.123456"))));
                        var update = new RecipeInternalApi.UpdateCommand("E", "A", id, "operator", "edit", definition);
                        var changed = api.replaceDefinition(update);
                        assertThat(api.replaceDefinition(update)).isEqualTo(changed);
                        var view = api.getRecipe(new RecipeInternalApi.GetQuery("E", "A", id)).recipe();
                        assertThat(view.lines().getFirst().qty()).isEqualByComparingTo("0.123456");
                        assertThat(view.configVersion()).isEqualTo(1);
                        assertThat(api.listRecipes(new RecipeInternalApi.Lookup("E", "A",
                                new ListRecipesQuery(1, 20, "TEA", "DRAFT"))).records()).containsExactly(view);
                        assertThat(api.listRecipes(new RecipeInternalApi.Lookup("OTHER", "A", null)).records())
                                .isEmpty();
                        assertThatThrownBy(() -> api.getRecipe(new RecipeInternalApi.GetQuery("E", "B", id)))
                                .isInstanceOfSatisfying(ServiceCallException.class,
                                        e -> assertThat(e.statusCode()).isEqualTo(404));
                        var sql = new JdbcTemplate(c.getBean(DataSource.class));
                        assertThat(sql.queryForObject("SELECT material_definition_hash FROM fnb_recipe_line",
                                String.class)).isEqualTo(MaterialCatalogFacts.definitionHash(
                                        "MILK", "E", "牛奶", "KG", 1, "OWN"));
                        assertThat(sql.queryForObject("SELECT COUNT(*) FROM fnb_recipe_line "
                                + "WHERE conversion_ref IS NOT NULL", Integer.class)).isZero();
                        for (String table : List.of("fnb_recipe", "fnb_recipe_line")) {
                            assertThat(sql.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).isOne();
                        }
                        for (String table : List.of("fnb_action_log", "sys_outbox", "sys_idempotent")) {
                            assertThat(sql.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).isEqualTo(2);
                        }
                        assertThat(bridgeFailure.get()).isNull();
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
            facade.setArchiveOnly(Set.of("PLATFORM:FOOD_RECIPE_CHANGED"));
            return facade;
        }
    }
}
