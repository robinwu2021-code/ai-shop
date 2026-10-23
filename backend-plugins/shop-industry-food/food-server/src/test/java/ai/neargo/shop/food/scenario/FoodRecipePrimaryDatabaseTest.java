package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.event.outbox.runtime.JdbcOutboxStore;
import ai.neargo.shop.event.outbox.runtime.UnifiedOutboxFacade;
import ai.neargo.shop.food.dto.ConfigRevisionContentVO;
import ai.neargo.shop.food.dto.CreateRecipeReq;
import ai.neargo.shop.food.dto.ListRecipesQuery;
import ai.neargo.shop.food.dto.PublishConfigReq;
import ai.neargo.shop.food.dto.RepublishConfigReq;
import ai.neargo.shop.food.dto.RecipeAdjustmentInput;
import ai.neargo.shop.food.dto.RecipeLineInput;
import ai.neargo.shop.food.dto.ReplaceRecipeDefinitionReq;
import ai.neargo.shop.food.server.FoodApplication;
import ai.neargo.shop.food.service.ConfigRevisionService;
import ai.neargo.shop.food.service.RecipeService;
import ai.neargo.shop.food.spi.ConfigRevisionInternalApi;
import ai.neargo.shop.food.spi.RecipeInternalApi;
import ai.neargo.shop.food.spi.MenuInternalApi;
import ai.neargo.shop.food.dto.CreateMenuReq;
import ai.neargo.shop.food.dto.UpdateMenuReq;
import ai.neargo.shop.food.dto.ListMenusQuery;
import ai.neargo.shop.food.dto.ReplaceMenuScheduleReq;
import ai.neargo.shop.food.dto.WeekScheduleInput;
import ai.neargo.shop.food.dto.ExceptionInput;
import ai.neargo.shop.food.dto.CreateMenuChannelBindingReq;
import ai.neargo.shop.food.dto.UpdateMenuChannelBindingReq;
import ai.neargo.shop.food.dto.ReplaceMenuCompositionReq;
import ai.neargo.shop.food.dto.ReplaceMenuContextsReq;
import ai.neargo.shop.food.dto.MenuCategoryInput;
import ai.neargo.shop.food.dto.MenuItemInput;
import ai.neargo.shop.food.dto.MenuSkuRecipeInput;
import ai.neargo.shop.food.dto.MenuContextInput;
import ai.neargo.shop.food.dto.DishPresentation;
import ai.neargo.shop.food.dto.MeasurementPolicy;
import ai.neargo.shop.food.dto.SaveDishReq;
import ai.neargo.shop.food.dto.MenuPublicationSnapshot;
import ai.neargo.shop.food.dto.StationPublicationSnapshot;
import ai.neargo.shop.food.dto.StationContracts.CreateStationReq;
import ai.neargo.shop.food.dto.StationContracts.UpdateStationReq;
import ai.neargo.shop.food.dto.StationContracts.ReplaceStationRoutesReq;
import ai.neargo.shop.food.dto.StationContracts.StationRouteInput;
import ai.neargo.shop.food.spi.DishInternalApi;
import ai.neargo.shop.food.spi.StationInternalApi;
import ai.neargo.shop.food.spi.MenuChannelBindingInternalApi;

import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.inventory.MaterialCatalogInternalApi;
import ai.neargo.shop.spi.inventory.MaterialCatalogQueryPort;
import ai.neargo.shop.spi.product.GoodsCatalogInternalApi;
import ai.neargo.shop.spi.product.SpecValueCatalogInternalApi;
import ai.neargo.shop.spi.product.SpecValueCatalogQueryPort;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.IntStream;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
        var goodsUnavailable = new AtomicBoolean();
        var expectedUpstreamFailures = new AtomicInteger();
        server.createContext(GoodsCatalogInternalApi.LOOKUP_PATH, ex -> {
            assertThat(ex.getRequestHeaders().getFirst(InternalHttp.TOKEN_HEADER)).isEqualTo("wire");
            if (goodsUnavailable.get()) { ex.sendResponseHeaders(503, -1); ex.close(); return; }
            String query = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(query).contains("\"entityNo\":\"E\"", "\"storeNo\":\"A\"", "\"goodsNo\":\"G\"");
            byte[] bytes = ("{\"goodsNo\":\"G\",\"entityNo\":\"E\",\"storeNo\":\"A\","
                    + "\"title\":\"Original tea\",\"cover\":\"base-cover\",\"baseOnSale\":true,"
                    + "\"auditStatus\":\"APPROVED\",\"version\":0,"
                    + "\"skus\":[{\"skuNo\":\"TEA\",\"spec\":\"Cup\",\"version\":0}]}")
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, bytes.length); ex.getResponseBody().write(bytes); ex.close();
        });
        server.createContext(GoodsCatalogInternalApi.SKU_PATH, ex -> {
            assertThat(ex.getRequestHeaders().getFirst(InternalHttp.TOKEN_HEADER)).isEqualTo("wire");
            if (goodsUnavailable.get()) { ex.sendResponseHeaders(503, -1); ex.close(); return; }
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
        server.createContext(SpecValueCatalogInternalApi.BATCH_PATH, ex -> {
            assertThat(ex.getRequestHeaders().getFirst(InternalHttp.TOKEN_HEADER)).isEqualTo("wire");
            byte[] query = ex.getRequestBody().readAllBytes();
            assertThat(new String(query, StandardCharsets.UTF_8)).contains("V1");
            byte[] bytes = new ObjectMapper().writeValueAsBytes(List.of(new SpecValueCatalogQueryPort.SpecValueFact(
                    "V1", "D", "No sugar", "Sugar", "ENUM", "PROP", "MERCHANT", "E", "PLATFORM", null, 0, 0)));
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, bytes.length); ex.getResponseBody().write(bytes); ex.close();
        });
        var frozen = new AtomicReference<ConfigRevisionContentVO>();
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
                            "shop.food.recipe-publication-enabled=true",
                            "shop.food.menu-publication-enabled=true",
                            "shop.food.station-publication-enabled=true",
                            "shop.services.targets.PLATFORM=http://127.0.0.1:" + server.getAddress().getPort(),
                            "spring.autoconfigure.exclude=org.springframework.boot.data.redis.autoconfigure."
                                    + "DataRedisAutoConfiguration,org.springframework.boot.data.redis.autoconfigure."
                                    + "DataRedisRepositoriesAutoConfiguration")
                    .run(c -> {
                        assertThat(c).hasNotFailed().hasSingleBean(RecipeService.class);
                        var mvc = MockMvcBuilders.webAppContextSetup(c)
                                .addFilters(c.getBean(FilterChainProxy.class)).build();
                        for (String path : List.of(RecipeInternalApi.CREATE_PATH, RecipeInternalApi.REPLACE_PATH,
                                RecipeInternalApi.LIST_PATH, RecipeInternalApi.GET_PATH,
                                MenuInternalApi.CREATE_PATH, MenuInternalApi.UPDATE_PATH,
                                MenuInternalApi.LIST_PATH, MenuInternalApi.GET_PATH, MenuInternalApi.SCHEDULE_PATH,
                                MenuInternalApi.COMPOSITION_PATH, MenuInternalApi.CONTEXTS_PATH,
                                StationInternalApi.CREATE_PATH, StationInternalApi.UPDATE_PATH,
                                StationInternalApi.ROUTES_PATH, MenuChannelBindingInternalApi.CREATE_PATH,
                                ConfigRevisionInternalApi.MENU_PATH, ConfigRevisionInternalApi.STATION_PATH)) {
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
                                if (goodsUnavailable.get()
                                        && failure.getCause() instanceof ServiceCallException upstream
                                        && upstream.statusCode() == 503) {
                                    expectedUpstreamFailures.incrementAndGet();
                                } else {
                                    bridgeFailure.compareAndSet(null, failure);
                                }
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
                        sql.update("INSERT INTO fnb_option_group(entity_no,created_at,created_by,updated_at,updated_by,"
                                + "group_no,name,min_qty,max_qty,status,selection_rules) "
                                + "VALUES('E',CURRENT_TIMESTAMP(3),"
                                + "'operator',"
                                + "CURRENT_TIMESTAMP(3),'operator','SUGAR','Sugar',0,1,'DRAFT','{}')");
                        sql.update("INSERT INTO fnb_option_group_item(entity_no,created_at,created_by,updated_at,"
                                + "updated_by,option_item_no,group_no,source_kind,spec_value_no,min_qty,max_qty,"
                                + "default_qty,sort_no) VALUES('E',CURRENT_TIMESTAMP(3),'operator',"
                                + "CURRENT_TIMESTAMP(3),"
                                + "'operator','NO_SUGAR','SUGAR','SPEC_VALUE','V1',0,1,0,0)");
                        var baseLine = view.lines().getFirst();
                        var adjustmentDefinition = new ReplaceRecipeDefinitionReq(1L, BigDecimal.ONE, "EA",
                                List.of(new RecipeLineInput(baseLine.lineNo(), baseLine.itemRef(), baseLine.qty(),
                                        baseLine.unit(), baseLine.lossRate())),
                                List.of(new RecipeAdjustmentInput(null, "NO_SUGAR", baseLine.lineNo(), "SET",
                                        BigDecimal.ZERO, "KG")));
                        var adjustmentCommand = new RecipeInternalApi.UpdateCommand("E", "A", id, "operator",
                                "adjust", adjustmentDefinition);
                        var adjusted = api.replaceDefinition(adjustmentCommand);
                        assertThat(adjusted.recipe().adjustments()).hasSize(1);
                        assertThat(adjusted.recipe().adjustments().getFirst().qty()).isEqualByComparingTo("0");
                        assertThat(api.replaceDefinition(adjustmentCommand)).isEqualTo(adjusted);
                        assertThat(api.getRecipe(new RecipeInternalApi.GetQuery("E", "A", id))).isEqualTo(adjusted);
                        assertThat(api.listRecipes(new RecipeInternalApi.Lookup("E", "A", null)).records())
                                .containsExactly(adjusted.recipe());
                        assertThat(sql.queryForObject("SELECT COUNT(*) FROM fnb_recipe_adjustment", Integer.class))
                                .isOne();
                        var revisions = http.client(ServiceName.FOOD, ConfigRevisionInternalApi.class,
                                Duration.ofSeconds(5));
                        var now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
                        sql.update("UPDATE fnb_option_group SET selection_rules='{\"mutexSets\":[],\"requires\":[]}'");
                        sql.update("INSERT INTO fnb_dish_option_group(entity_no,created_at,created_by,updated_at,"
                                + "updated_by,goods_no,group_no,sort_no) VALUES('E',CURRENT_TIMESTAMP(3),'operator',"
                                + "CURRENT_TIMESTAMP(3),'operator','G','SUGAR',0)");
                        revisions.publishOptionGroup(new ConfigRevisionInternalApi.Command("E", "A", "SUGAR",
                                "operator", "group-publish", new PublishConfigReq(0L, now, null)));
                        var publish = new ConfigRevisionInternalApi.Command("E", "A", id, "operator", "publish",
                                new PublishConfigReq(2L, now, null));
                        var publication = revisions.publishRecipe(publish);
                        assertThat(revisions.publishRecipe(publish)).isEqualTo(publication);
                        var content = revisions.getRevision(new ConfigRevisionInternalApi.Identity(
                                "E", "A", publication.publication().revisionNo()));
                        frozen.set(content);
                        var tree = new ObjectMapper().readTree(content.definitionSnapshot());
                        assertThat(tree.get("contentSchema").asInt()).isEqualTo(3);
                        assertThat(tree.get("recipe").get("lines").get(0).get("qty").isString()).isTrue();
                        assertThat(tree.get("recipe").get("lines").get(0).get("qty").asText()).isEqualTo("0.123456");
                        assertThat(tree.get("optionGroups").size()).isEqualTo(1);
                        assertThat(revisions.resolveRevision(new ConfigRevisionInternalApi.Resolve("E", "A",
                                "RECIPE", id, now.plusMinutes(1))).publication().revisionNo())
                                .isEqualTo(publication.publication().revisionNo());
                        assertThatThrownBy(() -> revisions.getRevision(new ConfigRevisionInternalApi.Identity(
                                "E", "B", publication.publication().revisionNo())))
                                .isInstanceOfSatisfying(ServiceCallException.class,
                                        e -> assertThat(e.statusCode()).isEqualTo(404));
                        menusOverHttp(http, sql, goodsUnavailable, id);
                        stationsOverHttp(http, goodsUnavailable);
                        channelDraftOverHttp(http);
                        assertThat(bridgeFailure.get()).isNull();
                        assertThat(expectedUpstreamFailures).hasValue(1);
                        for (String table : List.of("prd_goods", "ord_order")) {
                            assertThat(sql.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).isZero();
                        }
                    });
            new WebApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                    .withUserConfiguration(FoodApplication.class, Route.class)
                    .withPropertyValues("spring.datasource.url=" + url, "spring.datasource.username=root",
                            "spring.datasource.password=", "shop.services.internal-token=wire",
                            "spring.autoconfigure.exclude=org.springframework.boot.data.redis.autoconfigure."
                                    + "DataRedisAutoConfiguration,org.springframework.boot.data.redis.autoconfigure."
                                    + "DataRedisRepositoriesAutoConfiguration")
                    .run(c -> {
                        assertThat(c).hasNotFailed();
                        var service = c.getBean(ConfigRevisionService.class);
                        var before = frozen.get();
                        assertThat(service.getRevision("E", "A", before.publication().revisionNo())).isEqualTo(before);
                        assertThatThrownBy(() -> service.publishRecipe("E", "A", before.publication().configNo(),
                                "operator", new PublishConfigReq(3L,
                                        OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS), null),
                                "closed"))
                                .isInstanceOfSatisfying(BizException.class,
                                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
                    });
        } finally {
            server.stop(0);
            executor.shutdownNow();
            admin.execute("DROP DATABASE " + database);
        }
    }

    private static void menusOverHttp(InternalHttp http, JdbcTemplate sql, AtomicBoolean unavailable,
            String recipeNo) {
        sql.update("INSERT INTO fnb_store_policy(entity_no,created_at,created_by,updated_at,updated_by,policy_no,"
                + "store_no,timezone,cutover_minute,effective_from,pay_policy,capabilities,settings) "
                + "VALUES('E',CURRENT_TIMESTAMP(3),'operator',CURRENT_TIMESTAMP(3),'operator','MENU_POLICY',"
                + "'A','Asia/Shanghai',240,'2026-01-01 00:00:00','PREPAY','[]','{}')");
        var api = http.client(ServiceName.FOOD, MenuInternalApi.class, Duration.ofSeconds(5));
        var create = new MenuInternalApi.CreateCommand("E", "A", "operator", "menu-create",
                new CreateMenuReq("午餐%_特别", "TIME", "Asia/Shanghai", 10, "MEAL"));
        var first = api.createMenu(create);
        assertThat(api.createMenu(create)).isEqualTo(first);
        var id = first.configNo();
        assertThat(api.getMenu(new MenuInternalApi.GetQuery("E", "A", id)).config().name()).isEqualTo("午餐%_特别");
        var update = new MenuInternalApi.UpdateCommand("E", "A", id, "operator", "menu-edit",
                new UpdateMenuReq("晚餐%_特别", "TIME", "Asia/Shanghai", 20, null, 0L));
        var edited = api.updateMenu(update);
        assertThat(api.updateMenu(update)).isEqualTo(edited);
        assertThat(edited.menu().version()).isEqualTo(1);
        assertThat(edited.menu().exclusiveGroup()).isNull();
        assertThat(api.listMenus(new MenuInternalApi.Lookup("E", "A",
                new ListMenusQuery(1, 20, "DRAFT", "%_"))).records()).containsExactly(edited.menu());
        assertThat(api.listMenus(new MenuInternalApi.Lookup("E", "B", null)).records()).isEmpty();
        assertThatThrownBy(() -> api.getMenu(new MenuInternalApi.GetQuery("E", "B", id)))
                .isInstanceOfSatisfying(ServiceCallException.class, failure -> assertThat(failure.statusCode())
                        .isEqualTo(404));
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM fnb_menu_store", Integer.class)).isEqualTo(1);
        assertThat(sql.queryForObject("SELECT exclusive_group FROM fnb_menu", String.class)).isNull();
        var rules = new ReplaceMenuScheduleReq("SCHEDULE", 1L,
                List.of(new WeekScheduleInput(1, "22:00", "02:00", 1)),
                List.of(new ExceptionInput("2026-10-19", "CLOSED", null, null, 0)),
                "2026-10-01", "2026-10-31");
        var command = new MenuInternalApi.ScheduleCommand("E", "A", id, "operator", "menu-schedule", rules);
        var scheduled = api.replaceSchedule(command);
        assertThat(api.replaceSchedule(command)).isEqualTo(scheduled);
        assertThat(scheduled.menu().version()).isEqualTo(2);
        assertThat(api.getMenu(new MenuInternalApi.GetQuery("E", "A", id)).schedule()).isEqualTo(scheduled.schedule());
        assertThat(sql.queryForObject("SELECT end_day_offset FROM fnb_menu_schedule", Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> api.replaceSchedule(new MenuInternalApi.ScheduleCommand("E", "B", id,
                "operator", "bad-store", rules))).isInstanceOfSatisfying(ServiceCallException.class,
                        failure -> assertThat(failure.statusCode()).isEqualTo(404));
        var cleared = api.replaceSchedule(new MenuInternalApi.ScheduleCommand("E", "A", id, "operator", "clear",
                new ReplaceMenuScheduleReq("SCHEDULE", 2L, List.of(), List.of(), null, null)));
        assertThat(cleared.schedule().weekSchedules()).isEmpty();
        assertThat(api.replaceSchedule(command)).isEqualTo(scheduled);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM fnb_menu_schedule", Integer.class)).isZero();
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM fnb_menu_exception", Integer.class)).isZero();

        var dishes = http.client(ServiceName.FOOD, DishInternalApi.class, Duration.ofSeconds(5));
        var dish = dishes.saveDish(new DishInternalApi.SaveCommand("E", "A", "G", "operator", "native-dish",
                new SaveDishReq("SINGLE", 30, new DishPresentation("Native tea", List.of(), List.of(), List.of(),
                        List.of()), new MeasurementPolicy("EA", "COUNT", 0, null), null)));
        assertThat(dish.dish().presentation().displayName()).isEqualTo("Native tea");
        var composition = new ReplaceMenuCompositionReq("COMPOSITION", 3L, List.of(new MenuCategoryInput(null,
                "Tea", 0, List.of(new MenuItemInput(null, "G", 0, true, "EXPLICIT", List.of("TEA"),
                        "INHERIT", List.of(), List.of(new MenuSkuRecipeInput("TEA", recipeNo)))))));
        var compositionCommand = new MenuInternalApi.CompositionCommand("E", "A", id, "operator",
                "native-composition", composition);
        var composed = api.replaceComposition(compositionCommand);
        assertThat(api.replaceComposition(compositionCommand)).isEqualTo(composed);
        assertThat(composed.menu().version()).isEqualTo(4);
        var item = composed.composition().categories().getFirst().items().getFirst();
        assertThat(item.menuItemNo()).isNotBlank();
        assertThat(item.skuRecipes()).containsExactly(new MenuSkuRecipeInput("TEA", recipeNo));
        assertThatThrownBy(() -> api.replaceComposition(new MenuInternalApi.CompositionCommand("E", "B", id,
                "operator", "native-foreign-composition", composition)))
                .isInstanceOfSatisfying(ServiceCallException.class, e -> assertThat(e.statusCode()).isEqualTo(404));
        var contexts = new ReplaceMenuContextsReq("CONTEXT", 4L,
                List.of(new MenuContextInput("POS", "DINE_IN", null, null)));
        var contextCommand = new MenuInternalApi.ContextsCommand("E", "A", id, "operator", "native-context",
                contexts);
        var scoped = api.replaceContexts(contextCommand);
        assertThat(api.replaceContexts(contextCommand)).isEqualTo(scoped);
        assertThat(scoped.menu().version()).isEqualTo(5);
        var complete = api.getMenu(new MenuInternalApi.GetQuery("E", "A", id));
        assertThat(complete.composition()).isEqualTo(composed.composition());
        assertThat(complete.contexts()).isEqualTo(scoped.contexts());
        assertThat(complete.schedule().weekSchedules()).isEmpty();
        var revisions = http.client(ServiceName.FOOD, ConfigRevisionInternalApi.class, Duration.ofSeconds(5));
        var publish = new ConfigRevisionInternalApi.Command("E", "A", id, "operator", "native-menu-publish",
                new PublishConfigReq(5L, OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS), null));
        var published = revisions.publishMenu(publish);
        var identity = new ConfigRevisionInternalApi.Identity("E", "A", published.publication().revisionNo());
        var content = revisions.getRevision(identity);
        var snapshot = new ObjectMapper().readValue(content.definitionSnapshot(), MenuPublicationSnapshot.class);
        assertThat(snapshot.contentSchema()).isEqualTo(4);
        assertThat(snapshot.sourceVersion()).isEqualTo(5);
        assertThat(revisions.resolveRevision(new ConfigRevisionInternalApi.Resolve("E", "A", "MENU", id,
                published.publication().effectiveAt().plusSeconds(1)))).isEqualTo(content);
        assertThat(snapshot.menu().definition().composition()).isEqualTo(composed.composition());
        assertThat(snapshot.menu().definition().contexts()).isEqualTo(scoped.contexts());
        assertThat(snapshot.menu().dishes()).containsExactly(dish.dish());
        assertThat(snapshot.menu().dependencies()).extracting(d -> d.configKind())
                .contains("OPTION_GROUP", "RECIPE");
        assertThat(snapshot.goodsFacts().getFirst().goodsNo()).isEqualTo("G");
        assertThat(new ObjectMapper().readTree(content.definitionSnapshot()).get("goodsFacts").get(0)
                .has("price")).isFalse();
        assertThat(sql.queryForObject("SELECT published_revision_no FROM fnb_menu WHERE menu_no=?", String.class,
                id)).isEqualTo(published.publication().revisionNo());
        var draft = api.updateMenu(new MenuInternalApi.UpdateCommand("E", "A", id, "operator", "native-next-draft",
                new UpdateMenuReq("Next draft", "TIME", "Asia/Shanghai", 20, null, 6L)));
        assertThat(draft.menu().version()).isEqualTo(7);
        assertThat(revisions.getRevision(identity)).isEqualTo(content);
        assertThatThrownBy(() -> revisions.getRevision(new ConfigRevisionInternalApi.Identity("E", "B",
                published.publication().revisionNo())))
                .isInstanceOfSatisfying(ServiceCallException.class, e -> assertThat(e.statusCode()).isEqualTo(404));
        unavailable.set(true);
        try {
            assertThat(revisions.publishMenu(publish)).isEqualTo(published);
            assertThatThrownBy(() -> revisions.publishMenu(new ConfigRevisionInternalApi.Command("E", "A", id,
                    "operator", "native-upstream-outage", new PublishConfigReq(7L,
                            OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS), null))))
                    .isInstanceOf(ServiceCallException.class);
        } finally { unavailable.set(false); }
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM fnb_config_revision WHERE config_kind='MENU' "
                + "AND config_no=?", Integer.class, id)).isOne();
        // Exercise the real revision timeline query and immutable rollback over typed HTTP.
        api.updateMenu(new MenuInternalApi.UpdateCommand("E", "A", id, "operator", "native-exclusive",
                new UpdateMenuReq("Exclusive meal", "TIME", "Asia/Shanghai", 20, "MEAL", 7L)));
        var allWeek = IntStream.rangeClosed(1, 7)
                .mapToObj(day -> new WeekScheduleInput(day, "00:00", "00:00", 1)).toList();
        api.replaceSchedule(new MenuInternalApi.ScheduleCommand("E", "A", id, "operator", "native-allweek",
                new ReplaceMenuScheduleReq("SCHEDULE", 8L, allWeek, List.of(), null, null)));
        var effective = publish.publication().effectiveAt().plusSeconds(1);
        revisions.publishMenu(new ConfigRevisionInternalApi.Command("E", "A", id, "operator", "native-allweek-pub",
                new PublishConfigReq(9L, effective, null)));
        String competing = api.createMenu(new MenuInternalApi.CreateCommand("E", "A", "operator",
                "native-competing", new CreateMenuReq("Same priority meal", "TIME", "Asia/Shanghai", 20, "MEAL")))
                .configNo();
        api.replaceComposition(new MenuInternalApi.CompositionCommand("E", "A", competing, "operator",
                "native-competing-items", new ReplaceMenuCompositionReq("COMPOSITION", 0L,
                        List.of(new MenuCategoryInput(null, "Tea", 0, List.of(new MenuItemInput(null, "G", 0,
                                true, "EXPLICIT", List.of("TEA"), "REPLACE", List.of(), List.of())))))));
        api.replaceContexts(new MenuInternalApi.ContextsCommand("E", "A", competing, "operator",
                "native-competing-context", new ReplaceMenuContextsReq("CONTEXT", 1L, contexts.contexts())));
        api.replaceSchedule(new MenuInternalApi.ScheduleCommand("E", "A", competing, "operator",
                "native-competing-schedule", new ReplaceMenuScheduleReq("SCHEDULE", 2L, allWeek,
                        List.of(), null, null)));
        assertThatThrownBy(() -> revisions.publishMenu(new ConfigRevisionInternalApi.Command("E", "A", competing,
                "operator", "native-conflict", new PublishConfigReq(3L, effective, null))))
                .isInstanceOfSatisfying(ServiceCallException.class, e -> assertThat(e.statusCode()).isEqualTo(409));
        var restored = revisions.republishRevision(new ConfigRevisionInternalApi.RepublishCommand("E", "A",
                published.publication().revisionNo(), "operator", "native-menu-restore",
                new RepublishConfigReq(10L, effective.plusSeconds(1), "Restore meal", null)));
        assertThat(revisions.getRevision(new ConfigRevisionInternalApi.Identity("E", "A",
                restored.publication().revisionNo())).definitionSnapshot()).isEqualTo(content.definitionSnapshot());
        assertThat(api.getMenu(new MenuInternalApi.GetQuery("E", "A", id)).schedule().weekSchedules())
                .hasSize(7);
        assertThat(revisions.getRevision(identity)).isEqualTo(content);
    }

    private static void stationsOverHttp(InternalHttp http, AtomicBoolean unavailable) {
        var api = http.client(ServiceName.FOOD, StationInternalApi.class, Duration.ofSeconds(5));
        var create = new StationInternalApi.CreateCommand("E", "A", "operator", "native-station-create",
                new CreateStationReq("Tea bar", "BAR"));
        var created = api.createStation(create);
        assertThat(api.createStation(create)).isEqualTo(created);
        String id = created.configNo();
        var update = new StationInternalApi.UpdateCommand("E", "A", id, "operator", "native-station-edit",
                new UpdateStationReq(0L, "Service bar", "BAR", true));
        var edited = api.updateStation(update);
        assertThat(api.updateStation(update)).isEqualTo(edited);
        assertThat(edited.version()).isEqualTo(1);
        assertThat(api.getStation(new StationInternalApi.GetQuery("E", "A", id)).config().name())
                .isEqualTo("Service bar");
        assertThat(api.listStations(new StationInternalApi.Lookup("E", "A", 1, 20)).records())
                .extracting(station -> station.configNo()).containsExactly(id);
        assertThatThrownBy(() -> api.getStation(new StationInternalApi.GetQuery("E", "B", id)))
                .isInstanceOfSatisfying(ServiceCallException.class, e -> assertThat(e.statusCode()).isEqualTo(404));
        var routes = new StationInternalApi.RoutesCommand("E", "A", "TEA", "operator", "native-routes",
                new ReplaceStationRoutesReq(0L, List.of(new StationRouteInput(id, "MAKE"))));
        assertThat(api.replaceRoutes(routes).version()).isEqualTo(1);
        assertThat(api.getRoutes(new StationInternalApi.GetQuery("E", "A", "TEA")).routes())
                .containsExactly(new StationRouteInput(id, "MAKE"));
        var revisions = http.client(ServiceName.FOOD, ConfigRevisionInternalApi.class, Duration.ofSeconds(5));
        var publish = new ConfigRevisionInternalApi.Command("E", "A", id, "operator", "native-station-publish",
                new PublishConfigReq(1L, OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS), null));
        var published = revisions.publishStation(publish);
        var identity = new ConfigRevisionInternalApi.Identity("E", "A", published.publication().revisionNo());
        var content = revisions.getRevision(identity);
        var snapshot = new ObjectMapper().readValue(content.definitionSnapshot(), StationPublicationSnapshot.class);
        assertThat(snapshot.contentSchema()).isEqualTo(5);
        assertThat(snapshot.station().name()).isEqualTo("Service bar");
        assertThat(revisions.resolveRevision(new ConfigRevisionInternalApi.Resolve("E", "A", "STATION", id,
                published.publication().effectiveAt().plusSeconds(1)))).isEqualTo(content);
        assertThat(snapshot.routes()).containsExactly(new StationPublicationSnapshot.Route("TEA", "MAKE", 1));
        assertThat(snapshot.skuFacts().getFirst().goodsNo()).isEqualTo("G");
        assertThat(api.getStation(new StationInternalApi.GetQuery("E", "A", id)).config().publishedRevisionNo())
                .isEqualTo(published.publication().revisionNo());
        api.updateStation(new StationInternalApi.UpdateCommand("E", "A", id, "operator", "native-station-draft",
                new UpdateStationReq(2L, "Renamed draft", "BAR", true)));
        api.replaceRoutes(new StationInternalApi.RoutesCommand("E", "A", "TEA", "operator", "native-clear-routes",
                new ReplaceStationRoutesReq(1L, List.of())));
        assertThat(revisions.getRevision(identity)).isEqualTo(content);
        unavailable.set(true);
        try {
            assertThat(revisions.publishStation(publish)).isEqualTo(published);
            assertThat(api.replaceRoutes(routes).version()).isEqualTo(1);
        } finally { unavailable.set(false); }
    }

    private static void channelDraftOverHttp(InternalHttp http) {
        var menus = http.client(ServiceName.FOOD, MenuInternalApi.class, Duration.ofSeconds(5));
        String id = menus.createMenu(new MenuInternalApi.CreateCommand("E", "A", "operator", "channel-menu",
                new CreateMenuReq("Channel draft", "TIME", "Asia/Shanghai", 0, null))).configNo();
        var api = http.client(ServiceName.FOOD, MenuChannelBindingInternalApi.class, Duration.ofSeconds(5));
        var create = new MenuChannelBindingInternalApi.CreateCommand("E", "A", "operator", "channel-draft",
                new CreateMenuChannelBindingReq(id, "KEETA", "PLANNED", "PLANNED"));
        var binding = api.createBinding(create);
        assertThat(api.createBinding(create)).isEqualTo(binding);
        assertThat(binding.status()).isEqualTo("DRAFT");
        assertThatThrownBy(() -> api.updateBinding(new MenuChannelBindingInternalApi.UpdateCommand("E", "A",
                binding.bindingNo(), "operator", "channel-activate", new UpdateMenuChannelBindingReq(0L,
                        "ACTIVE", "PLANNED"))))
                .isInstanceOfSatisfying(ServiceCallException.class, e -> assertThat(e.statusCode()).isEqualTo(409));
        assertThat(api.getBinding(new MenuChannelBindingInternalApi.GetQuery("E", "A", binding.bindingNo())))
                .isEqualTo(binding);
        assertThatThrownBy(() -> api.getBinding(new MenuChannelBindingInternalApi.GetQuery("E", "B",
                binding.bindingNo())))
                .isInstanceOfSatisfying(ServiceCallException.class, e -> assertThat(e.statusCode()).isEqualTo(404));
    }

    @Configuration(proxyBeanMethods=false)
    static class Route {
        @Bean UnifiedOutboxFacade facade(DataSource source, PlatformTransactionManager manager, ObjectMapper json) {
            var facade = new UnifiedOutboxFacade(json);
            facade.register(new JdbcOutboxStore(JdbcOutboxStore.Source.PLATFORM, source, manager, json), List.of());
            facade.setArchiveOnly(Set.of("PLATFORM:FOOD_RECIPE_CHANGED", "PLATFORM:FOOD_CONFIG_PUBLISHED",
                    "PLATFORM:FOOD_MENU_CHANGED", "PLATFORM:FOOD_DISH_CHANGED", "PLATFORM:FOOD_STATION_CHANGED",
                    "PLATFORM:FOOD_MENU_CHANNEL_BINDING_CHANGED"));
            return facade;
        }
    }
}
