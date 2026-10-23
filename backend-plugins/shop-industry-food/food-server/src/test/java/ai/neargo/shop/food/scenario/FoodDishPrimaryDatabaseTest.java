package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.neargo.shop.food.dto.DishPresentation;
import ai.neargo.shop.food.dto.DishCatalogItemVO;
import ai.neargo.shop.food.dto.DishDetailResultVO;
import ai.neargo.shop.food.dto.DishResultVO;
import ai.neargo.shop.food.dto.ListDishesQuery;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.MeasurementPolicy;
import ai.neargo.shop.food.dto.SaveDishReq;
import ai.neargo.shop.food.server.FoodApplication;
import ai.neargo.shop.food.service.DishService;
import ai.neargo.shop.food.spi.DishInternalApi;
import ai.neargo.shop.spi.product.GoodsCatalogInternalApi;
import ai.neargo.shop.spi.product.SpecValueCatalogInternalApi;
import ai.neargo.shop.spi.product.SpecValueCatalogQueryPort;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
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

/** Exercises the actual V1 JSON columns, standalone assembly and both original typed HTTP boundaries. */
@EnabledIfEnvironmentVariable(named="FOOD_TEST_DISPOSABLE", matches="true")
class FoodDishPrimaryDatabaseTest {
    @Test void typedSaveUsesActualPrimaryDishAuditAndOutboxWithoutTouchingCoreTables() throws Exception {
        String adminUrl = System.getenv("FOOD_TEST_JDBC_URL");
        assertThat(adminUrl).matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/mysql");
        var admin = new JdbcTemplate(new DriverManagerDataSource(adminUrl, "root", ""));
        String database = "food_dish_it_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE " + database);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        var calls = new AtomicInteger();
        server.createContext(GoodsCatalogInternalApi.LOOKUP_PATH, ex -> {
            calls.incrementAndGet();
            assertThat(ex.getRequestHeaders().getFirst(InternalHttp.TOKEN_HEADER)).isEqualTo("wire");
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(body).contains("\"entityNo\":\"E\"", "\"storeNo\":\"A\"");
            if (body.contains("FOREIGN")) {
                ex.sendResponseHeaders(404, -1);
            } else {
                byte[] response = """
                        {"goodsNo":"G","entityNo":"E","storeNo":"A","title":"原商品",
                         "cover":"base-cover","baseOnSale":false,"auditStatus":"PENDING","version":1,
                         "skus":[{"skuNo":"SKU","spec":"中杯","version":1}]}
                        """.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(200, response.length);
                ex.getResponseBody().write(response);
            }
            ex.close();
        });
        server.createContext(GoodsCatalogInternalApi.BATCH_PATH, ex -> {
            calls.incrementAndGet();
            assertThat(ex.getRequestHeaders().getFirst(InternalHttp.TOKEN_HEADER)).isEqualTo("wire");
            assertThat(new String(ex.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)).contains("G");
            byte[] response="""
                    [{"goodsNo":"G","entityNo":"E","storeNo":"A","title":"原商品","cover":"base-cover",
                      "baseOnSale":false,"auditStatus":"PENDING","version":1}]
                    """.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type","application/json");
            ex.sendResponseHeaders(200,response.length);ex.getResponseBody().write(response);ex.close();
        });
        var specCalls = new AtomicInteger();
        server.createContext(SpecValueCatalogInternalApi.BATCH_PATH, ex -> {
            specCalls.incrementAndGet();
            assertThat(ex.getRequestHeaders().getFirst(InternalHttp.TOKEN_HEADER)).isEqualTo("wire");
            assertThat(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                    .contains("\"entityNo\":\"E\"", "\"valueNos\":[\"V\"]");
            byte[] response = """
                    [{"valueNo":"V","dimNo":"D","label":"半糖","dimName":"糖度",
                      "valueType":"ENUM","usageType":"PROP","valueScope":"MERCHANT","valueEntityNo":"E",
                      "dimScope":"PLATFORM","dimEntityNo":"PLATFORM","valueVersion":2,"dimVersion":1}]
                    """.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, response.length); ex.getResponseBody().write(response); ex.close();
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
                        assertThat(c).hasNotFailed().hasSingleBean(DishService.class)
                                .hasSingleBean(SpecValueCatalogQueryPort.class);
                        assertThat(c.getBean(SpecValueCatalogQueryPort.class).getValues("E", List.of("V")))
                                .extracting(value -> value.label()).containsExactly("半糖");
                        var mvc = MockMvcBuilders.webAppContextSetup(c)
                                .addFilters(c.getBean(FilterChainProxy.class)).build();
                        mvc.perform(post(DishInternalApi.SAVE_PATH).contentType(MediaType.APPLICATION_JSON)
                                .content("{}")).andExpect(status().isUnauthorized());
                        mvc.perform(post(DishInternalApi.SAVE_PATH).header(InternalHttp.TOKEN_HEADER, "wire")
                                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                                .andExpect(status().isBadRequest());
                        server.createContext("/internal/food/dishes/", ex -> {
                            try {
                                var result = mvc.perform(post(ex.getRequestURI().getPath())
                                        .header(InternalHttp.TOKEN_HEADER,
                                                ex.getRequestHeaders().getFirst(InternalHttp.TOKEN_HEADER))
                                        .contentType(MediaType.APPLICATION_JSON).content(ex.getRequestBody().readAllBytes()))
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
                        var api = http.client(ServiceName.FOOD, DishInternalApi.class, Duration.ofSeconds(5));
                        DishService remote = new DishService() {
                            @Override public DishResultVO saveDish(String entity,String store,String goods,String actor,
                                    SaveDishReq request,String key) {
                                return api.saveDish(new DishInternalApi.SaveCommand(entity,store,goods,actor,key,request));
                            }
                            @Override public PageData<DishCatalogItemVO> listDishes(String entity,String store,ListDishesQuery query) {
                                return api.listDishes(new DishInternalApi.Lookup(entity,store,query));
                            }
                            @Override public DishDetailResultVO getDish(String entity,String store,String goods) {
                                return api.getDish(new DishInternalApi.Identity(entity,store,goods));
                            }
                        };
                        var presentation = new DishPresentation("奶茶", List.of(), List.of(), List.of(), List.of("含奶"));
                        var req = new SaveDishReq("SINGLE", 60, presentation,
                                new MeasurementPolicy("EA", "COUNT", 0, null), null);
                        var first = remote.saveDish("E", "A", "G", "operator", req, "original");
                        assertThat(first.dish().presentation().displayName()).isEqualTo("奶茶");
                        assertThat(remote.saveDish("E", "A", "G", "operator", req, "original")).isEqualTo(first);
                        var update = new SaveDishReq("SINGLE", 0, new DishPresentation(null, null, null, null, null),
                                req.measurement(), 0L);
                        var edited = remote.saveDish("E", "A", "G", "operator", update, "edit");
                        assertThat(edited.dish().presentation().displayName()).isNull();
                        assertThat(edited.dish().version()).isEqualTo(1);
                        assertThatThrownBy(() -> remote.saveDish("E", "A", "G", "operator", update, "stale"))
                                .isInstanceOfSatisfying(ServiceCallException.class,
                                        e -> assertThat(e.statusCode()).isEqualTo(409));
                        assertThatThrownBy(() -> remote.saveDish("E", "A", "FOREIGN", "operator", req, "foreign"))
                                .isInstanceOfSatisfying(ServiceCallException.class,
                                        e -> assertThat(e.statusCode()).isEqualTo(404));
                        var sql = new JdbcTemplate(c.getBean(DataSource.class));
                        sql.update("INSERT INTO fnb_option_group(entity_no,group_no,name,min_qty,max_qty,status,selection_rules,"
                                +"created_at,created_by,updated_at,updated_by) VALUES('E','OG','糖度',1,1,'DRAFT','{}',NOW(3),'SYSTEM',NOW(3),'SYSTEM')");
                        sql.update("INSERT INTO fnb_dish_option_group(entity_no,goods_no,group_no,sort_no,created_at,created_by,updated_at,updated_by) "
                                +"VALUES('E','G','OG',0,NOW(3),'SYSTEM',NOW(3),'SYSTEM')");
                        sql.update("INSERT INTO fnb_option_group_item(entity_no,option_item_no,group_no,source_kind,spec_value_no,min_qty,max_qty,default_qty,sort_no,"
                                +"created_at,created_by,updated_at,updated_by) VALUES('E','OI','OG','SPEC_VALUE','SUGAR',0,1,1,0,NOW(3),'SYSTEM',NOW(3),'SYSTEM')");
                        var page=remote.listDishes("E","A",new ListDishesQuery(1,20,"原商品",null));
                        assertThat(page.total()).isEqualTo(1);
                        assertThat(page.records().getFirst().product().cover()).isEqualTo("base-cover");
                        var detail=remote.getDish("E","A","G");
                        assertThat(detail.optionGroups().getFirst().data().items().getFirst().optionItemNo()).isEqualTo("OI");
                        assertThat(detail.coreCatalog().skus()).hasSize(1);
                        assertThat(sql.queryForObject("SELECT JSON_EXTRACT(presentation,'$.displayName') "
                                + "FROM fnb_dish WHERE goods_no='G'", String.class)).isEqualTo("null");
                        assertThat(sql.queryForObject("SELECT COUNT(*) FROM fnb_dish", Integer.class)).isOne();
                        for (String table : List.of("fnb_action_log", "sys_outbox", "sys_idempotent")) {
                            assertThat(sql.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).isEqualTo(2);
                        }
                        // The existing fixture includes two one-column sentinels, not real product/order tables.
                        for (String table : List.of("prd_goods", "ord_order")) {
                            assertThat(sql.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).isZero();
                            assertThat(sql.queryForObject("SELECT COUNT(*) FROM information_schema.columns "
                                    + "WHERE table_schema=DATABASE() AND table_name=?", Integer.class, table)).isOne();
                        }
                    });
            assertThat(calls.get()).isEqualTo(7);
            assertThat(specCalls).hasValue(1);
        } finally {
            server.stop(0);
            executor.shutdownNow();
            admin.execute("DROP DATABASE " + database);
        }
    }

}
