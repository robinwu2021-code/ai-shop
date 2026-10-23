package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.neargo.shop.food.dto.BundleChoiceInput;
import ai.neargo.shop.food.dto.BundleGroupInput;
import ai.neargo.shop.food.dto.CreateBundleReq;
import ai.neargo.shop.food.dto.ReplaceBundleDefinitionReq;
import ai.neargo.shop.food.dto.ListBundlesQuery;
import ai.neargo.shop.food.server.FoodApplication;
import ai.neargo.shop.food.service.BundleService;
import ai.neargo.shop.food.spi.BundleInternalApi;
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
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

/** Actual frozen migration, primary JSON, service-token ingress and original typed HTTP response shapes. */
@EnabledIfEnvironmentVariable(named="FOOD_TEST_DISPOSABLE", matches="true")
class FoodBundlePrimaryDatabaseTest {
    @Test void typedCreateAndQueriesUseOriginalPrimaryWithoutTouchingGoodsOrOrders() throws Exception {
        String adminUrl = System.getenv("FOOD_TEST_JDBC_URL");
        assertThat(adminUrl).matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/mysql");
        var admin = new JdbcTemplate(new DriverManagerDataSource(adminUrl, "root", ""));
        String database = "food_bundle_it_" + UUID.randomUUID().toString().replace("-", "");
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
                    .withUserConfiguration(FoodApplication.class)
                    .withPropertyValues("shop.outbox.sources.platform.mode=unified",
                            "shop.outbox.sources.inventory.mode=legacy", "shop.outbox.enabled=false",
                            "spring.datasource.url=" + url, "spring.datasource.username=root",
                            "spring.datasource.password=", "shop.services.internal-token=wire",
                            "shop.services.targets.PLATFORM=http://127.0.0.1:" + server.getAddress().getPort(),
                            "spring.autoconfigure.exclude=org.springframework.boot.data.redis.autoconfigure."
                                    + "DataRedisAutoConfiguration,org.springframework.boot.data.redis.autoconfigure."
                                    + "DataRedisRepositoriesAutoConfiguration")
                    .run(c -> {
                        FoodServerProducerAssertions.verify(c);
                        assertThat(c).hasNotFailed().hasSingleBean(BundleService.class);
                        var mvc = MockMvcBuilders.webAppContextSetup(c)
                                .addFilters(c.getBean(FilterChainProxy.class)).build();
                        for (String path : List.of(BundleInternalApi.CREATE_PATH, BundleInternalApi.REPLACE_PATH,
                                BundleInternalApi.LIST_PATH, BundleInternalApi.GET_PATH)) {
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
                        var api = http.client(ServiceName.FOOD, BundleInternalApi.class, Duration.ofSeconds(5));
                        var create = new BundleInternalApi.CreateCommand("E", "A", "operator", "create",
                                new CreateBundleReq("MEAL"));
                        var first = api.createBundle(create);
                        assertThat(api.createBundle(create)).isEqualTo(first);
                        var definition = new ReplaceBundleDefinitionReq(0L,
                                List.of(new BundleGroupInput("G1", "配餐", BigDecimal.ONE,
                                        new BigDecimal("999999999999.999999"), "BASE", 0)),
                                List.of(new BundleChoiceInput("C1", "G1", "SIDE", new BigDecimal("999999999.999"),
                                        "DIFF_BASE", BigDecimal.ZERO, BigDecimal.ZERO,
                                        new BigDecimal("999999999999.999999"), 0)));
                        var replace = new BundleInternalApi.UpdateCommand("E", "A", "MEAL", "operator",
                                "replace", definition);
                        assertThat(api.replaceDefinition(replace).version()).isEqualTo(1);
                        assertThat(api.replaceDefinition(replace).version()).isEqualTo(1);
                        var view = api.getBundle(new BundleInternalApi.GetQuery("E", "A", "MEAL")).config();
                        assertThat(view.data().groups().getFirst().maxQty())
                                .isEqualByComparingTo("999999999999.999999");
                        assertThat(view.data().choices().getFirst().unitsPerPick())
                                .isEqualByComparingTo("999999999.999");
                        assertThat(view.data().configVersion()).isEqualTo(1);
                        assertThat(api.listBundles(new BundleInternalApi.Lookup("E", "A", new ListBundlesQuery(1, 20)))
                                .records()).containsExactly(view);
                        assertThat(api.listBundles(new BundleInternalApi.Lookup("OTHER", "A", null)).records())
                                .isEmpty();
                        assertThatThrownBy(() -> api.getBundle(new BundleInternalApi.GetQuery("OTHER", "A", "MEAL")))
                                .isInstanceOfSatisfying(ServiceCallException.class,
                                        e -> assertThat(e.statusCode()).isEqualTo(404));
                        assertThatThrownBy(() -> api.createBundle(new BundleInternalApi.CreateCommand("E", "A",
                                "operator",
                                "nested", new CreateBundleReq("BASE"))))
                                .isInstanceOfSatisfying(ServiceCallException.class,
                                        e -> assertThat(e.statusCode()).isEqualTo(400));
                        var sql = new JdbcTemplate(c.getBean(DataSource.class));
                        for (String table : List.of("fnb_bundle", "fnb_bundle_group", "fnb_bundle_choice")) {
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

}
