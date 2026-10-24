package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.neargo.shop.food.dto.CreateOptionGroupReq;
import ai.neargo.shop.food.dto.OptionItemInput;
import ai.neargo.shop.food.dto.ReplaceOptionGroupItemsReq;
import ai.neargo.shop.food.dto.SelectionRules;
import ai.neargo.shop.spi.product.SpecValueCatalogInternalApi;
import ai.neargo.shop.food.dto.ListOptionGroupsQuery;
import ai.neargo.shop.food.server.FoodApplication;
import ai.neargo.shop.food.service.OptionGroupService;
import ai.neargo.shop.food.spi.OptionGroupInternalApi;
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
class FoodOptionGroupPrimaryDatabaseTest {
    @Test void typedCreateAndQueriesUseOriginalPrimaryWithoutTouchingGoodsOrOrders() throws Exception {
        String adminUrl = System.getenv("FOOD_TEST_JDBC_URL");
        assertThat(adminUrl).matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/mysql");
        var admin = new JdbcTemplate(new DriverManagerDataSource(adminUrl, "root", ""));
        String database = "food_group_it_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE " + database);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext(SpecValueCatalogInternalApi.BATCH_PATH, ex -> {
            assertThat(ex.getRequestHeaders().getFirst(InternalHttp.TOKEN_HEADER)).isEqualTo("wire");
            String number = "V".repeat(64);
            String body = "[{\"valueNo\":\"" + number + "\",\"dimNo\":\"D\",\"label\":\"半糖\","
                    + "\"dimName\":\"糖度\",\"valueType\":\"ENUM\",\"usageType\":\"PROP\","
                    + "\"valueScope\":\"MERCHANT\",\"valueEntityNo\":\"E\",\"dimScope\":\"PLATFORM\","
                    + "\"dimEntityNo\":null,\"valueVersion\":0,\"dimVersion\":0}]";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
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
                        assertThat(c).hasNotFailed().hasSingleBean(OptionGroupService.class);
                        var mvc = MockMvcBuilders.webAppContextSetup(c)
                                .addFilters(c.getBean(FilterChainProxy.class)).build();
                        for (String path : List.of(OptionGroupInternalApi.CREATE_PATH, OptionGroupInternalApi.GET_PATH,
                                OptionGroupInternalApi.LIST_PATH, OptionGroupInternalApi.REPLACE_PATH)) {
                            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                                    .andExpect(status().isUnauthorized());
                            mvc.perform(post(path).header(InternalHttp.TOKEN_HEADER, "wire")
                                    .contentType(MediaType.APPLICATION_JSON).content("{}"))
                                    .andExpect(status().isBadRequest());
                        }
                        server.createContext("/internal/food/option-groups/", ex -> {
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
                        var api = http.client(ServiceName.FOOD, OptionGroupInternalApi.class, Duration.ofSeconds(5));
                        var req = new CreateOptionGroupReq("糖度", BigDecimal.ZERO, BigDecimal.ONE);
                        var command = new OptionGroupInternalApi.CreateCommand("E", "A", "operator", "original", req);
                        var first = api.createOptionGroup(command);
                        assertThat(first.configNo()).isNotBlank();
                        assertThat(first.status()).isEqualTo("DRAFT");
                        assertThat(api.createOptionGroup(command)).isEqualTo(first);
                        var replacement = new ReplaceOptionGroupItemsReq(0L, List.of(new OptionItemInput("I",
                                "SPEC_VALUE", "V".repeat(64), null, BigDecimal.ZERO, BigDecimal.ONE,
                                BigDecimal.ZERO, 0)), List.of(), new SelectionRules(List.of(), List.of()));
                        var replaceCommand = new OptionGroupInternalApi.ReplaceCommand("E", "A", first.configNo(),
                                "operator", "replace", replacement);
                        assertThat(api.replaceOptionGroupItems(replaceCommand).version()).isEqualTo(1);
                        assertThat(api.replaceOptionGroupItems(replaceCommand).version()).isEqualTo(1);
                        var detail = api.getOptionGroup(
                                new OptionGroupInternalApi.Identity("E", "A", first.configNo()));
                        assertThat(detail.config().data().name()).isEqualTo("糖度");
                        assertThat(detail.config().revision()).isEqualTo(1);
                        assertThat(detail.config().data().items().getFirst().specValueNo()).isEqualTo("V".repeat(64));
                        var page = api.listOptionGroups(new OptionGroupInternalApi.Lookup("E", "A",
                                new ListOptionGroupsQuery(1, 20)));
                        assertThat(page.total()).isOne();
                        assertThat(page.records()).containsExactly(detail.config());
                        assertThatThrownBy(() -> api.getOptionGroup(
                                new OptionGroupInternalApi.Identity("OTHER", "B", first.configNo())))
                                .isInstanceOfSatisfying(ServiceCallException.class,
                                        e -> assertThat(e.statusCode()).isEqualTo(404));
                        assertThatThrownBy(() -> api.createOptionGroup(new OptionGroupInternalApi.CreateCommand(
                                "E", "A", "operator", "original",
                                new CreateOptionGroupReq("冰度", BigDecimal.ZERO, BigDecimal.ONE))))
                                .isInstanceOfSatisfying(ServiceCallException.class,
                                        e -> assertThat(e.statusCode()).isEqualTo(409));
                        var sql = new JdbcTemplate(c.getBean(DataSource.class));
                        assertThat(sql.queryForObject("SELECT JSON_LENGTH(selection_rules) FROM fnb_option_group",
                                Integer.class)).isEqualTo(2);
                        assertThat(sql.queryForObject("SELECT COUNT(*) FROM fnb_option_group", Integer.class)).isOne();
                        for (String table : List.of("fnb_action_log",
                                "sys_outbox", "sys_idempotent")) {
                            assertThat(sql.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class))
                                    .as(table).isEqualTo(2);
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

}
