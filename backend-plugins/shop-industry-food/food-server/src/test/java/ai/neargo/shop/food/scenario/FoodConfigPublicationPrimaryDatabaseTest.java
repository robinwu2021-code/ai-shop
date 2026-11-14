package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.neargo.shop.food.dto.CreateModifierReq;
import ai.neargo.shop.food.dto.ModifierComponentInput;
import ai.neargo.shop.food.dto.UpdateModifierReq;

import ai.neargo.shop.spi.product.GoodsCatalogInternalApi;
import ai.neargo.shop.food.dto.ListModifiersQuery;
import ai.neargo.shop.food.server.FoodApplication;
import ai.neargo.shop.food.service.ConfigRevisionService;
import ai.neargo.shop.food.spi.ModifierInternalApi;
import ai.neargo.shop.food.spi.ConfigRevisionInternalApi;
import ai.neargo.shop.food.spi.OptionGroupInternalApi;
import ai.neargo.shop.food.dto.CreateOptionGroupReq;
import ai.neargo.shop.food.dto.ReplaceOptionGroupItemsReq;
import ai.neargo.shop.food.dto.OptionItemInput;
import ai.neargo.shop.food.dto.SelectionRules;
import ai.neargo.shop.food.dto.PublishConfigReq;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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
import ai.neargo.shop.food.dto.PublishConfigResultVO;
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
class FoodConfigPublicationPrimaryDatabaseTest {
    @Test void typedCreateAndQueriesUseOriginalPrimaryWithoutTouchingGoodsOrOrders() throws Exception {
        String adminUrl = System.getenv("FOOD_TEST_JDBC_URL");
        assertThat(adminUrl).matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/mysql");
        var admin = new JdbcTemplate(new DriverManagerDataSource(adminUrl, "root", ""));
        String database = "food_publication_it_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE " + database);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newCachedThreadPool();
        var bridgeFailure = new AtomicReference<Throwable>();
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
                    .withUserConfiguration(FoodApplication.class)
                    .withPropertyValues("shop.outbox.sources.platform.mode=unified",
                            "shop.outbox.sources.inventory.mode=legacy", "shop.outbox.enabled=false",
                            "spring.datasource.url=" + url, "spring.datasource.username=root",
                            "spring.datasource.password=", "shop.services.internal-token=wire", ai.neargo.shop.food.scope.FoodScopeTestSigning.KEY_PROPERTY,
                            "shop.services.targets.PLATFORM=http://127.0.0.1:" + server.getAddress().getPort(),
                            "spring.autoconfigure.exclude=org.springframework.boot.data.redis.autoconfigure."
                                    + "DataRedisAutoConfiguration,org.springframework.boot.data.redis.autoconfigure."
                                    + "DataRedisRepositoriesAutoConfiguration")
                    .run(c -> {
                        FoodServerProducerAssertions.verify(c);
                        assertThat(c).hasNotFailed().hasSingleBean(ConfigRevisionService.class);
                        var mvc = MockMvcBuilders.webAppContextSetup(c)
                                .addFilters(c.getBean(FilterChainProxy.class)).build();
                        for (String path : List.of(ConfigRevisionInternalApi.MODIFIER_PATH, ConfigRevisionInternalApi.GROUP_PATH,
                                ConfigRevisionInternalApi.LIST_PATH, ConfigRevisionInternalApi.GET_PATH, ConfigRevisionInternalApi.RESOLVE_PATH)) {
                            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                                    .andExpect(status().isUnauthorized());
                            mvc.perform(post(path).header(InternalHttp.TOKEN_HEADER, "wire").with(ai.neargo.shop.food.scope.FoodScopeTestSigning.system())
                                    .contentType(MediaType.APPLICATION_JSON).content("{}"))
                                    .andExpect(status().isBadRequest());
                        }
                        server.createContext("/internal/food/", ex -> {
                            try {
                                var result = mvc.perform(post(ex.getRequestURI().getPath())
                                        .header(InternalHttp.TOKEN_HEADER,
                                                ex.getRequestHeaders().getFirst(InternalHttp.TOKEN_HEADER)).with(ai.neargo.shop.food.scope.FoodScopeTestSigning.system())
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
                        var api = http.client(ServiceName.FOOD, ModifierInternalApi.class, Duration.ofSeconds(5));
                        var publications = http.client(ServiceName.FOOD, ConfigRevisionInternalApi.class, Duration.ofSeconds(5));
                        var options = http.client(ServiceName.FOOD, OptionGroupInternalApi.class, Duration.ofSeconds(5));
                        var now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
                        var first = api.createModifier(new ModifierInternalApi.CreateCommand("E", "A", "operator", "addon",
                                new CreateModifierReq("珍珠", "ADDON", "SKU", List.of())));
                        var command = new ConfigRevisionInternalApi.Command("E", "A", first.configNo(), "operator", "publish",
                                new PublishConfigReq(0L, now, null));
                        PublishConfigResultVO receipt;
                        try {
                            receipt = publications.publishModifier(command);
                        } catch (RuntimeException transportFailure) {
                            if (bridgeFailure.get() != null) throw new AssertionError("Test HTTP bridge failed", bridgeFailure.get());
                            throw transportFailure;
                        }
                        var published = receipt.publication();
                        assertThat(publications.publishModifier(command).publication()).isEqualTo(published);
                        var child = publications.getRevision(new ConfigRevisionInternalApi.Identity("E", "A", published.revisionNo()));
                        assertThat(child.definitionSnapshot()).contains("珍珠", "skuFacts");
                        var combo = api.createModifier(new ModifierInternalApi.CreateCommand("E", "A", "operator", "combo",
                                new CreateModifierReq("双拼", "COMBO", "SKU", List.of(
                                        new ModifierComponentInput(first.configNo(), new BigDecimal("1.50"))))));
                        var comboRevision = publications.publishModifier(new ConfigRevisionInternalApi.Command("E", "A",
                                combo.configNo(), "operator", "publish-combo", new PublishConfigReq(0L, now, null))).publication();
                        var group = options.createOptionGroup(new OptionGroupInternalApi.CreateCommand("E", "A", "operator", "group",
                                new CreateOptionGroupReq("小料", BigDecimal.ONE, new BigDecimal("999999999999.999999"))));
                        options.replaceOptionGroupItems(new OptionGroupInternalApi.ReplaceCommand("E", "A", group.configNo(),
                                "operator", "items", new ReplaceOptionGroupItemsReq(0L, List.of(new OptionItemInput(
                                        "I", "MODIFIER", null, combo.configNo(), BigDecimal.ZERO, BigDecimal.ONE,
                                        BigDecimal.ZERO, 0)), List.of(), new SelectionRules(List.of(), List.of()))));
                        var groupRevision = publications.publishOptionGroup(new ConfigRevisionInternalApi.Command("E", "A",
                                group.configNo(), "operator", "publish-group", new PublishConfigReq(1L, now, null))).publication();
                        assertThat(publications.getRevision(new ConfigRevisionInternalApi.Identity("E", "A", groupRevision.revisionNo()))
                                .definitionSnapshot()).contains(comboRevision.revisionNo(), "999999999999.999999");
                        var scheduled = publications.publishModifier(new ConfigRevisionInternalApi.Command("E", "A", first.configNo(),
                                "operator", "future", new PublishConfigReq(1L, now.plusHours(1), null))).publication();
                        assertThat(scheduled.status()).isEqualTo("SCHEDULED");
                        assertThat(publications.resolveRevision(new ConfigRevisionInternalApi.Resolve("E", "A", "MODIFIER",
                                first.configNo(), now.plusMinutes(1))).publication().revisionNo()).isEqualTo(published.revisionNo());
                        assertThat(publications.resolveRevision(new ConfigRevisionInternalApi.Resolve("E", "A", "MODIFIER",
                                first.configNo(), now.plusHours(1))).publication().revisionNo()).isEqualTo(scheduled.revisionNo());
                        assertThatThrownBy(() -> publications.getRevision(new ConfigRevisionInternalApi.Identity("E", "B",
                                published.revisionNo()))).isInstanceOfSatisfying(ServiceCallException.class,
                                        e -> assertThat(e.statusCode()).isEqualTo(404));
                        var sql = new JdbcTemplate(c.getBean(DataSource.class));
                        assertThat(sql.queryForObject("SELECT COUNT(*) FROM fnb_config_revision", Integer.class)).isEqualTo(4);
                        assertThat(sql.queryForObject("SELECT COUNT(*) FROM fnb_action_log", Integer.class)).isEqualTo(8);
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
