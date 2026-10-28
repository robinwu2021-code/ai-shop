package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.event.outbox.runtime.UnifiedOutboxFacade;
import ai.neargo.shop.food.dto.CreateRecipeReq;
import ai.neargo.shop.food.dto.ListRecipesQuery;
import ai.neargo.shop.food.dto.OptionItemInput;
import ai.neargo.shop.food.dto.RecipeAdjustmentInput;
import ai.neargo.shop.food.dto.RecipeLineInput;
import ai.neargo.shop.food.dto.ReplaceOptionGroupItemsReq;
import ai.neargo.shop.food.dto.ReplaceRecipeDefinitionReq;
import ai.neargo.shop.food.dto.SelectionRules;
import ai.neargo.shop.food.mapper.FoodMappers.ActionLogMapper;
import ai.neargo.shop.food.mapper.FoodMappers.DishMapper;
import ai.neargo.shop.food.mapper.FoodMappers.DishOptionGroupMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ModifierMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OptionGroupItemMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OptionGroupMapper;
import ai.neargo.shop.food.mapper.FoodMappers.RecipeAdjustmentMapper;
import ai.neargo.shop.food.mapper.FoodMappers.RecipeLineMapper;
import ai.neargo.shop.food.mapper.FoodMappers.RecipeMapper;
import ai.neargo.shop.food.service.RecipeService;
import ai.neargo.shop.food.service.impl.OptionGroupServiceImpl;
import ai.neargo.shop.food.service.impl.RecipeServiceImpl;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.inventory.MaterialCatalogQueryPort;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** Real primary tables and atomic outbox verify editable recipes without any stock command. */
class RecipeServiceTest extends FoodWriteGuardFixture {
    void run(BiConsumer<AssertableApplicationContext, Fixture> action) {
        runner().withUserConfiguration(StorePolicyPublicationTest.Publication.class, Recipes.class).run(c -> {
            var f = prepare(c);
            StorePolicyPublicationTest.createPolicyTables(f);
            if ("true".equals(System.getenv("FOOD_TEST_DISPOSABLE"))) {
                new ResourceDatabasePopulator(new ClassPathResource(
                        "db/industry/food/V4__recipe_draft_tables.sql")).execute(f.source());
            } else {
                String common = "id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_no VARCHAR(32) DEFAULT 'MAIN',"
                        + "entity_no VARCHAR(32),created_at DATETIME(3),created_by VARCHAR(32),updated_at DATETIME(3),"
                        + "updated_by VARCHAR(32),version BIGINT DEFAULT 0,deleted INT DEFAULT 0,";
                f.sql().execute("CREATE TABLE fnb_recipe (" + common + "recipe_no VARCHAR(32) UNIQUE,"
                        + "store_no VARCHAR(32),sku_no VARCHAR(32),recipe_code VARCHAR(32),yield_qty DECIMAL(18,6),"
                        + "yield_unit VARCHAR(16),config_version BIGINT,published_revision_no VARCHAR(32),"
                        + "status VARCHAR(16),UNIQUE(store_no,sku_no,recipe_code))");
                f.sql().execute("CREATE TABLE fnb_recipe_line (" + common + "line_no VARCHAR(32) UNIQUE,"
                        + "recipe_no VARCHAR(32),item_ref VARCHAR(32),qty DECIMAL(18,6),unit VARCHAR(16),"
                        + "conversion_ref VARCHAR(64),material_definition_hash VARCHAR(64),"
                        + "loss_rate DECIMAL(12,6),sort_no INT,UNIQUE(recipe_no,line_no))");
            }
            if ("true".equals(System.getenv("FOOD_TEST_DISPOSABLE"))) {
                new ResourceDatabasePopulator(new ClassPathResource(
                        "db/industry/food/V5__recipe_adjustment_table.sql")).execute(f.source());
            } else {
                f.sql().execute("CREATE TABLE fnb_recipe_adjustment (id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                        + "tenant_no VARCHAR(32) DEFAULT 'MAIN',entity_no VARCHAR(32),created_at DATETIME(3),"
                        + "created_by VARCHAR(32),updated_at DATETIME(3),updated_by VARCHAR(32),"
                        + "version BIGINT DEFAULT 0,deleted INT DEFAULT 0,adjustment_no VARCHAR(32) UNIQUE,"
                        + "recipe_no VARCHAR(32),option_item_no VARCHAR(32),target_line_no VARCHAR(32),"
                        + "action VARCHAR(16),qty DECIMAL(18,6),unit VARCHAR(16),sort_no INT,"
                        + "option_identity_hash VARCHAR(64),"
                        + "UNIQUE(recipe_no,option_item_no,target_line_no))");
            }
            c.getBean(UnifiedOutboxFacade.class).setArchiveOnly(Set.of("PLATFORM:FOOD_RECIPE_CHANGED"));
            action.accept(c, f);
        });
    }
    static BigDecimal n(String v) { return new BigDecimal(v); }
    static CreateRecipeReq create(String sku, String code) { return new CreateRecipeReq(sku, code, n("1"), "EA"); }
    static RecipeLineInput line(String id, String item, String qty, String unit) {
        return new RecipeLineInput(id, item, n(qty), unit, n("0.05"));
    }
    static ReplaceRecipeDefinitionReq definition(long version, RecipeLineInput... lines) {
        return new ReplaceRecipeDefinitionReq(version, n("1.00"), "EA", List.of(lines));
    }
    static void code(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BizException.class,
                e -> assertThat(e.errorCode()).isEqualTo(code));
    }

    @Test void foreignRecipeKeepsIndependentMaterialAuthorityWhileUsingMarketAwareSaleSku() {
        run((c, f) -> {
            var source = new CatalogCurrencyBusinessTest.Sources();
            MaterialCatalogQueryPort materials = (entity, ids) -> List.of(
                    new MaterialCatalogQueryPort.MaterialFact("MILK", entity, "Milk", "KG", 1, "OWN",
                            "BASE_ONLY", "INTEGER_ONLY",
                            MaterialCatalogFacts.definitionHash("MILK", entity, "Milk", "KG", 1, "OWN")));
            var service = new RecipeServiceImpl(c.getBean(RecipeMapper.class), c.getBean(RecipeLineMapper.class),
                    c.getBean(ActionLogMapper.class), c.getBean(IdempotencyService.class),
                    c.getBean("foodOutboxPublisher", OutboxPublisher.class), c.getBean(ObjectMapper.class),
                    c.getBean(PlatformTransactionManager.class), source.goods, materials,
                    c.getBean(RecipeAdjustmentMapper.class), c.getBean(OptionGroupItemMapper.class),
                    c.getBean(OptionGroupMapper.class), source.currencies);
            var recipe = service.createRecipe("E", "A", "actor", create("TEA", "DEFAULT"), "foreign-create")
                    .recipe();
            var updated = service.replaceDefinition("E", "A", recipe.recipeNo(), "actor",
                    definition(0, line(null, "MILK", "0.125", "KG")), "foreign-edit").recipe();
            assertThat(updated.lines()).hasSize(1);
            assertThat(updated.lines().getFirst().materialDefinitionHash()).isEqualTo(
                    MaterialCatalogFacts.definitionHash("MILK", "E", "Milk", "KG", 1, "OWN"));
            source.corrupt = true;
            assertThatThrownBy(() -> service.replaceDefinition("E", "A", recipe.recipeNo(), "actor",
                    definition(1, line(null, "MILK", "0.25", "KG")), "corrupt"))
                    .isInstanceOf(BizException.class);
            assertThat(f.sql().queryForObject("SELECT version FROM fnb_recipe", Long.class)).isEqualTo(1);
        });
    }

    @Test void createsAndEditsExactBaseUnitLinesWithGeneratedIdentitiesAndRepeatedMaterials() {
        run((c, f) -> {
            var s = c.getBean(RecipeService.class);
            var first = s.createRecipe("E", "A", "actor", create("TEA", "DEFAULT"), "create").recipe();
            assertThat(first.recipeNo()).isNotBlank().hasSizeLessThanOrEqualTo(32);
            assertThat(first.lines()).isEmpty();
            var result = s.replaceDefinition("E", "A", first.recipeNo(), "actor", definition(0,
                    line(null, "MILK", "0.125000", "KG"), line(null, "MILK", "0.010", "KG")), "edit").recipe();
            assertThat(result.version()).isEqualTo(1);
            assertThat(result.configVersion()).isEqualTo(1);
            assertThat(result.lines()).hasSize(2);
            assertThat(result.lines()).extracting(v -> v.lineNo()).doesNotHaveDuplicates();
            assertThat(result.lines().getFirst().qty()).isEqualByComparingTo("0.125");
            assertThat(result.lines().getFirst().materialDefinitionHash()).isEqualTo(
                    MaterialCatalogFacts.definitionHash("MILK", "E", "牛奶", "KG", 1, "OWN"));
            assertThat(s.getRecipe("E", "A", first.recipeNo()).recipe()).isEqualTo(result);
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_recipe_line WHERE conversion_ref IS NOT NULL",
                    Integer.class)).isZero();
            for (String table : List.of("sys_idempotent", "sys_outbox", "fnb_action_log")) {
                assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM " + table, Integer.class))
                        .as(table).isEqualTo(2);
            }
        });
    }

    @Test void rejectsForeignMissingMaterialWrongUnitsPrecisionAndInventedLineKeysWithoutChangingDraft() {
        run((c, f) -> {
            var s = c.getBean(RecipeService.class);
            var id = s.createRecipe("E", "A", "actor", create("TEA", "DEFAULT"), "create").recipe().recipeNo();
            for (var bad : List.of(line(null, "MISSING", "1", "KG"), line(null, "MILK", "1", "G"),
                    line(null, "MILK", "0.0000001", "KG"), line(null, "MILK", "1000000000000", "KG"),
                    line(null, "MILK", "0", "KG"))) {
                code(() -> s.replaceDefinition("E", "A", id, "actor", definition(0, bad), "bad"),
                        ErrorCode.BAD_REQUEST);
            }
            code(() -> s.replaceDefinition("E", "A", id, "actor", definition(0,
                    line("invented", "MILK", "1", "KG")), "bad-id"), ErrorCode.CONFLICT);
            assertThat(s.getRecipe("E", "A", id).recipe().version()).isZero();
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_recipe_line", Integer.class)).isZero();
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM sys_outbox", Integer.class)).isOne();
        });
    }

    @Test void scopesEveryHeadAndPageToEntityStoreAndUsesSameFiltersForTotals() {
        run((c, f) -> {
            var s = c.getBean(RecipeService.class);
            var a = s.createRecipe("E", "A", "actor", create("TEA", "DEFAULT"), "a").recipe();
            s.createRecipe("E", "A", "actor", create("TEA", "NO_MILK"), "b");
            s.createRecipe("E", "A", "actor", create("COFFEE", "DEFAULT"), "c");
            s.createRecipe("E", "B", "actor", create("TEA", "DEFAULT"), "d");
            code(() -> s.getRecipe("E", "B", a.recipeNo()), ErrorCode.NOT_FOUND);
            code(() -> s.getRecipe("OTHER", "A", a.recipeNo()), ErrorCode.NOT_FOUND);
            var page = s.listRecipes("E", "A", new ListRecipesQuery(2, 1, "TEA", "DRAFT"));
            assertThat(page.total()).isEqualTo(2);
            assertThat(page.records()).hasSize(1);
            assertThat(s.listRecipes("E", "A", new ListRecipesQuery(1, 20, null, "ACTIVE")).total()).isZero();
            code(() -> s.createRecipe("E", "A", "actor", create("TEA", "DEFAULT"), "dup"), ErrorCode.CONFLICT);
        });
    }

    @Test void completedReceiptsReplayWithoutAuthorityAndStaleOrChangedCommandsConflict() {
        run((c, f) -> {
            var s = c.getBean(RecipeService.class);
            var state = c.getBean(State.class);
            var first = s.createRecipe("E", "A", "actor", create("TEA", "DEFAULT"), "create");
            var req = definition(0, line(null, "MILK", "0.1", "KG"));
            var saved = s.replaceDefinition("E", "A", first.recipe().recipeNo(), "actor", req, "edit");
            state.outage.set(true);
            assertThat(s.createRecipe("E", "A", "actor", create("TEA", "DEFAULT"), "create")).isEqualTo(first);
            assertThat(s.replaceDefinition("E", "A", first.recipe().recipeNo(), "actor", req, "edit")).isEqualTo(saved);
            assertThat(s.getRecipe("E", "A", first.recipe().recipeNo())).isEqualTo(saved);
            state.outage.set(false);
            code(() -> s.replaceDefinition("E", "A", first.recipe().recipeNo(), "actor", req, "stale"),
                    ErrorCode.CONFLICT);
            code(() -> s.createRecipe("E", "A", "actor", create("OTHER", "DEFAULT"), "create"), ErrorCode.CONFLICT);
        });
    }

    @Test void outboxFailureRollsBackHeadLinesAuditAndIdempotencyAndAllowsSameKeyRetry() {
        run((c, f) -> {
            var s = c.getBean(RecipeService.class);
            var state = c.getBean(State.class);
            state.fail.set(true);
            assertThatThrownBy(() -> s.createRecipe("E", "A", "actor", create("TEA", "DEFAULT"), "create"))
                    .isInstanceOf(IllegalStateException.class);
            for (String table : List.of("fnb_recipe", "sys_idempotent", "sys_outbox", "fnb_action_log")) {
                assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).as(table).isZero();
            }
            state.fail.set(false);
            var head = s.createRecipe("E", "A", "actor", create("TEA", "DEFAULT"), "create").recipe();
            state.fail.set(true);
            assertThatThrownBy(() -> s.replaceDefinition("E", "A", head.recipeNo(), "actor",
                    definition(0, line(null, "MILK", "1", "KG")), "edit")).isInstanceOf(IllegalStateException.class);
            assertThat(s.getRecipe("E", "A", head.recipeNo()).recipe()).isEqualTo(head);
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_recipe_line", Integer.class)).isZero();
        });
    }

    @Test void invalidOriginalFactsAndAuthorityOutagesCannotWriteOrBecomeEmptySuccess() {
        run((c, f) -> {
            var service = c.getBean(RecipeService.class);
            var state = c.getBean(State.class);
            state.badSku.set(true);
            assertThatThrownBy(() -> service.createRecipe("E", "A", "actor", create("TEA", "DEFAULT"), "bad"))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_recipe", Integer.class)).isZero();
            state.badSku.set(false);
            var original = service.createRecipe("E", "A", "actor", create("TEA", "DEFAULT"), "create");
            state.badMaterial.set(true);
            assertThatThrownBy(() -> service.replaceDefinition("E", "A", original.recipe().recipeNo(), "actor",
                    definition(0, line(null, "MILK", "1", "KG")), "bad-material"))
                    .isInstanceOf(IllegalStateException.class);
            state.outage.set(true);
            assertThatThrownBy(() -> service.createRecipe("E", "A", "actor", create("TEA", "OTHER"), "offline"))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(service.getRecipe("E", "A", original.recipe().recipeNo())).isEqualTo(original);
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_recipe_line", Integer.class)).isZero();
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM sys_idempotent", Integer.class)).isOne();
        });
    }

    @Test
    @EnabledIfEnvironmentVariable(named="FOOD_TEST_DISPOSABLE", matches="true")
    void nativeConcurrentDefinitionUpdatesHaveOneWinnerAndOneConflict() {
        run((c, f) -> {
            var service = c.getBean(RecipeService.class);
            var id = service.createRecipe("E", "A", "actor", create("TEA", "DEFAULT"), "create")
                    .recipe().recipeNo();
            var start = new CountDownLatch(1);
            try (var workers = Executors.newFixedThreadPool(2)) {
                var tasks = List.of("1", "2").stream().map(qty -> workers.submit(() -> {
                    start.await();
                    try {
                        service.replaceDefinition("E", "A", id, "actor", definition(0,
                                line(null, "MILK", qty, "KG")), "race-" + qty);
                        return "OK";
                    } catch (BizException failure) { return failure.errorCode().name(); }
                })).toList();
                start.countDown();
                assertThat(List.of(tasks.get(0).get(20, TimeUnit.SECONDS), tasks.get(1).get(20, TimeUnit.SECONDS)))
                        .containsExactlyInAnyOrder("OK", "CONFLICT");
            } catch (Exception failure) { throw new AssertionError(failure); }
            assertThat(service.getRecipe("E", "A", id).recipe().version()).isEqualTo(1);
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM sys_outbox", Integer.class)).isEqualTo(2);
        });
    }

    static ReplaceRecipeDefinitionReq adjusted(ObjectMapper json, long version, RecipeLineInput target,
            String extra) {
        String base = json.writeValueAsString(definition(version, target));
        return json.readValue(base.substring(0, base.length() - 1) + ",\"adjustments\":" + extra + "}",
                ReplaceRecipeDefinitionReq.class);
    }
    static String adjustment(String id, String option, String line, String action, String qty, String unit) {
        return "[{" + (id == null ? "" : "\"adjustmentNo\":\"" + id + "\",")
                + "\"optionItemNo\":\"" + option + "\",\"targetLineNo\":\"" + line
                + "\",\"action\":\"" + action + "\",\"qty\":" + qty + ",\"unit\":\"" + unit + "\"}]";
    }
    static void options(Fixture f) {
        String common = "id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_no VARCHAR(32) DEFAULT 'MAIN',"
                + "entity_no VARCHAR(32),created_at DATETIME(3),created_by VARCHAR(32),updated_at DATETIME(3),"
                + "updated_by VARCHAR(32),version BIGINT DEFAULT 0,deleted INT DEFAULT 0,";
        f.sql().execute("CREATE TABLE fnb_option_group (" + common + "group_no VARCHAR(32) UNIQUE,"
                + "name VARCHAR(64),min_qty DECIMAL(18,6),max_qty DECIMAL(18,6),"
                + "published_revision_no VARCHAR(32),status VARCHAR(16),selection_rules TEXT)");
        f.sql().execute("CREATE TABLE fnb_option_group_item (" + common + "option_item_no VARCHAR(32) UNIQUE,"
                + "group_no VARCHAR(32),source_kind VARCHAR(16),spec_value_no VARCHAR(64),modifier_no VARCHAR(32),"
                + "min_qty DECIMAL(18,6),max_qty DECIMAL(18,6),default_qty DECIMAL(18,6),sort_no INT)");
        f.sql().update("INSERT INTO fnb_option_group(entity_no,group_no,name,min_qty,max_qty,status) "
                + "VALUES('E','SUGAR','糖量',0,1,'DRAFT'),('OTHER','FOREIGN','外部',0,1,'DRAFT')");
        f.sql().update("INSERT INTO fnb_option_group_item(entity_no,option_item_no,group_no,source_kind,"
                + "spec_value_no,min_qty,max_qty,default_qty,sort_no) VALUES"
                + "('E','NO_SUGAR','SUGAR','SPEC_VALUE','V1',0,1,0,0),"
                + "('OTHER','BAD','FOREIGN','SPEC_VALUE','V2',0,1,0,0)");
    }

    @Test void adjustmentsSaveReplayPreserveAndClearWithoutLosingBaseLineIdentity() {
        run((c, f) -> {
            var service = c.getBean(RecipeService.class);
            var json = c.getBean(ObjectMapper.class);
            options(f);
            var id = service.createRecipe("E", "A", "actor", create("TEA", "DEFAULT"), "create")
                    .recipe().recipeNo();
            var base = service.replaceDefinition("E", "A", id, "actor",
                    definition(0, line(null, "MILK", "1", "KG")), "base").recipe().lines().getFirst();
            var input = line(base.lineNo(), "MILK", "1", "KG");
            var req = adjusted(json, 1, input, adjustment(null, "NO_SUGAR", base.lineNo(), "SET", "0", "KG"));
            var saved = service.replaceDefinition("E", "A", id, "actor", req, "adjust");
            assertThat(json.writeValueAsString(saved)).contains("\"action\":\"SET\"");
            assertThat(f.sql().queryForObject("SELECT qty FROM fnb_recipe_adjustment", BigDecimal.class))
                    .isEqualByComparingTo("0");
            String adjustmentNo = f.sql().queryForObject("SELECT adjustment_no FROM fnb_recipe_adjustment",
                    String.class);
            c.getBean(State.class).outage.set(true);
            assertThat(service.replaceDefinition("E", "A", id, "actor", req, "adjust")).isEqualTo(saved);
            c.getBean(State.class).outage.set(false);
            var preserved = service.replaceDefinition("E", "A", id, "actor", definition(2, input), "old-client");
            assertThat(json.writeValueAsString(preserved)).contains(adjustmentNo);
            code(() -> service.replaceDefinition("E", "A", id, "actor", definition(3), "orphan"),
                    ErrorCode.CONFLICT);
            var clear = service.replaceDefinition("E", "A", id, "actor", adjusted(json, 3, input, "[]"), "clear");
            assertThat(clear.recipe().version()).isEqualTo(4);
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_recipe_adjustment", Integer.class)).isZero();
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM sys_outbox", Integer.class)).isEqualTo(5);
        });
    }

    @Test void adjustmentScopeUnitsActionsAndIdsFailWithoutMutationAndOutboxFailureRollsBack() {
        run((c, f) -> {
            var service = c.getBean(RecipeService.class);
            var json = c.getBean(ObjectMapper.class);
            options(f);
            var id = service.createRecipe("E", "A", "actor", create("TEA", "DEFAULT"), "create")
                    .recipe().recipeNo();
            var base = service.replaceDefinition("E", "A", id, "actor",
                    definition(0, line(null, "MILK", "1", "KG")), "base").recipe().lines().getFirst();
            var input = line(base.lineNo(), "MILK", "1", "KG");
            for (String bad : List.of(adjustment(null, "BAD", base.lineNo(), "SET", "0", "KG"),
                    adjustment(null, "MISSING", base.lineNo(), "SET", "0", "KG"),
                    adjustment(null, "NO_SUGAR", "foreign-line", "SET", "0", "KG"),
                    adjustment(null, "NO_SUGAR", base.lineNo(), "SET", "0", "G"),
                    adjustment(null, "NO_SUGAR", base.lineNo(), "REMOVE", "1", "KG"),
                    adjustment(null, "NO_SUGAR", base.lineNo(), "SET", "-1", "KG"),
                    adjustment(null, "NO_SUGAR", base.lineNo(), "ADD", "0.0000001", "KG"))) {
                code(() -> service.replaceDefinition("E", "A", id, "actor", adjusted(json, 1, input, bad), "bad"),
                        ErrorCode.BAD_REQUEST);
            }
            code(() -> service.replaceDefinition("E", "A", id, "actor", adjusted(json, 1, input,
                    adjustment("invented", "NO_SUGAR", base.lineNo(), "SET", "0", "KG")), "bad-id"),
                    ErrorCode.CONFLICT);
            c.getBean(State.class).fail.set(true);
            assertThatThrownBy(() -> service.replaceDefinition("E", "A", id, "actor", adjusted(json, 1, input,
                    adjustment(null, "NO_SUGAR", base.lineNo(), "REMOVE", "null", "KG")), "rollback"))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(service.getRecipe("E", "A", id).recipe().version()).isEqualTo(1);
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_recipe_adjustment", Integer.class)).isZero();
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM sys_outbox", Integer.class)).isEqualTo(2);
        });
    }

    @Test void adjustmentIdentityCannotRebindAndDifferentMutuallyExclusiveOptionsRemainIndependent() {
        run((c, f) -> {
            var service = c.getBean(RecipeService.class);
            var json = c.getBean(ObjectMapper.class);
            options(f);
            f.sql().update("INSERT INTO fnb_option_group_item(entity_no,option_item_no,group_no,source_kind,"
                    + "spec_value_no,min_qty,max_qty,default_qty,sort_no) "
                    + "VALUES('E','LESS_SUGAR','SUGAR','SPEC_VALUE','V3',0,1,0,1)");
            var id = service.createRecipe("E", "A", "actor", create("TEA", "DEFAULT"), "create")
                    .recipe().recipeNo();
            var initial = service.replaceDefinition("E", "A", id, "actor", definition(0,
                    line(null, "MILK", "1", "KG"), line(null, "MILK", "2", "KG")), "base").recipe();
            var first = initial.lines().getFirst();
            var second = initial.lines().get(1);
            var baseLines = initial.lines().stream().map(a -> line(a.lineNo(), a.itemRef(), a.qty().toPlainString(),
                    a.unit())).toList();
            var rules = List.of(new RecipeAdjustmentInput(null, "NO_SUGAR", first.lineNo(), "SET", n("0"), "KG"),
                    new RecipeAdjustmentInput(null, "LESS_SUGAR", first.lineNo(), "SET", n("0.5"), "KG"));
            var saved = service.replaceDefinition("E", "A", id, "actor",
                    new ReplaceRecipeDefinitionReq(1L, n("1"), "EA", baseLines, rules), "rules").recipe();
            assertThat(saved.adjustments()).hasSize(2);
            var noSugar = saved.adjustments().getFirst();
            for (var bad : List.of(
                    new RecipeAdjustmentInput(noSugar.adjustmentNo(), "LESS_SUGAR", first.lineNo(),
                            "SET", n("0"), "KG"),
                    new RecipeAdjustmentInput(noSugar.adjustmentNo(), "NO_SUGAR", second.lineNo(),
                            "SET", n("0"), "KG"))) {
                code(() -> service.replaceDefinition("E", "A", id, "actor",
                        new ReplaceRecipeDefinitionReq(2L, n("1"), "EA", baseLines, List.of(bad)), "rebind"),
                        ErrorCode.CONFLICT);
            }
            var duplicate = List.of(rules.getFirst(), rules.getFirst());
            code(() -> service.replaceDefinition("E", "A", id, "actor",
                    new ReplaceRecipeDefinitionReq(2L, n("1"), "EA", baseLines, duplicate), "duplicate"),
                    ErrorCode.BAD_REQUEST);
            var added = service.replaceDefinition("E", "A", id, "actor",
                    new ReplaceRecipeDefinitionReq(2L, n("1"), "EA", baseLines, List.of(new RecipeAdjustmentInput(
                            noSugar.adjustmentNo(), "NO_SUGAR", first.lineNo(), "ADD", n("0.012345"), "KG"))), "add");
            assertThat(added.recipe().adjustments().getFirst().adjustmentNo()).isEqualTo(noSugar.adjustmentNo());
            var removed = service.replaceDefinition("E", "A", id, "actor",
                    new ReplaceRecipeDefinitionReq(3L, n("1"), "EA", baseLines, List.of(new RecipeAdjustmentInput(
                            noSugar.adjustmentNo(), "NO_SUGAR", first.lineNo(), "REMOVE", null, "KG"))), "remove");
            assertThat(removed.recipe().adjustments().getFirst().qty()).isNull();
            assertThat(service.getRecipe("E", "A", id)).isEqualTo(removed);
            f.sql().update("UPDATE fnb_option_group SET status='DISABLED' WHERE group_no='SUGAR'");
            code(() -> service.replaceDefinition("E", "A", id, "actor",
                    new ReplaceRecipeDefinitionReq(4L, n("1"), "EA", baseLines), "disabled"), ErrorCode.BAD_REQUEST);
            assertThat(service.getRecipe("E", "A", id)).isEqualTo(removed);
        });
    }

    @Test
    @EnabledIfEnvironmentVariable(named="FOOD_TEST_DISPOSABLE", matches="true")
    void nativeAdjustmentChecksRejectMalformedActionQuantityAndOrder() {
        run((c, f) -> {
            String insert = "INSERT INTO fnb_recipe_adjustment(entity_no,created_at,created_by,updated_at,updated_by,"
                    + "adjustment_no,recipe_no,option_item_no,target_line_no,action,qty,unit,"
                    + "option_identity_hash,sort_no) "
                    + "VALUES('E',CURRENT_TIMESTAMP(3),'actor',CURRENT_TIMESTAMP(3),'actor',?,?,?,?,?,?,'KG','"
                    + "a".repeat(64) + "',?)";
            for (String action : List.of("UNKNOWN", "REMOVE", "SET", "ADD")) {
                BigDecimal qty = switch (action) {
                    case "SET" -> null;
                    case "ADD" -> n("-1");
                    default -> n("0");
                };
                checkConstraint(() -> f.sql().update(insert, "BAD", "R", "O", "L", action, qty, 0));
            }
            checkConstraint(() -> f.sql().update(insert, "BAD", "R", "O", "L", "SET", n("0"), -1));
            f.sql().update(insert, "A1", "R", "O", "L", "SET", n("0"), 0);
            assertThatThrownBy(() -> f.sql().update(insert, "A2", "R", "O", "L", "ADD", n("1"), 1))
                    .isInstanceOf(DataIntegrityViolationException.class);
            f.sql().update(insert, "A2", "R", "O2", "L", "REMOVE", null, 1);
        });
    }

    @Test void actualOptionDeleteAndRecreateCannotSilentlyRebindSavedAdjustmentIdentity() {
        run((c, f) -> {
            options(f);
            f.sql().update("UPDATE fnb_option_group SET selection_rules='{}'");
            f.sql().execute("CREATE TABLE fnb_dish_option_group(id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + "tenant_no VARCHAR(32) DEFAULT 'MAIN',entity_no VARCHAR(32),created_at DATETIME(3),"
                    + "created_by VARCHAR(32),updated_at DATETIME(3),updated_by VARCHAR(32),"
                    + "version BIGINT DEFAULT 0,deleted INT DEFAULT 0,goods_no VARCHAR(32),"
                    + "group_no VARCHAR(32),sort_no INT)");
            var template = c.getBean(SqlSessionTemplate.class);
            for (var mapper : List.of(DishMapper.class, DishOptionGroupMapper.class, ModifierMapper.class)) {
                if (!template.getConfiguration().hasMapper(mapper)) template.getConfiguration().addMapper(mapper);
            }
            var sources = new OptionGroupServiceTest.Sources();
            var groups = new OptionGroupServiceImpl(c.getBean(OptionGroupMapper.class),
                    c.getBean(OptionGroupItemMapper.class), c.getBean(ActionLogMapper.class),
                    c.getBean(IdempotencyService.class), c.getBean("foodOutboxPublisher", OutboxPublisher.class),
                    c.getBean(ObjectMapper.class), c.getBean(PlatformTransactionManager.class),
                    template.getMapper(DishMapper.class), template.getMapper(DishOptionGroupMapper.class),
                    template.getMapper(ModifierMapper.class), sources, sources);
            c.getBean(UnifiedOutboxFacade.class).setArchiveOnly(
                    Set.of("PLATFORM:FOOD_RECIPE_CHANGED", "PLATFORM:FOOD_OPTION_GROUP_CHANGED"));
            var service = c.getBean(RecipeService.class);
            var json = c.getBean(ObjectMapper.class);
            var id = service.createRecipe("E", "A", "actor", create("TEA", "DEFAULT"), "create")
                    .recipe().recipeNo();
            var base = service.replaceDefinition("E", "A", id, "actor",
                    definition(0, line(null, "MILK", "1", "KG")), "base").recipe().lines().getFirst();
            var input = line(base.lineNo(), "MILK", "1", "KG");
            var req = adjusted(json, 1, input, adjustment(null, "NO_SUGAR", base.lineNo(), "SET", "0", "KG"));
            var saved = service.replaceDefinition("E", "A", id, "actor", req, "adjust");
            var unchanged = new OptionItemInput("NO_SUGAR", "SPEC_VALUE", "V1", null,
                    n("0"), n("1"), n("0"), 0);
            groups.replaceOptionGroupItems("E", "A", "SUGAR", "actor",
                    new ReplaceOptionGroupItemsReq(0L, List.of(unchanged), List.of(),
                            new SelectionRules(List.of(), List.of())), "same-identity");
            var retained = service.replaceDefinition("E", "A", id, "actor", definition(2, input), "safe-preserve");
            assertThat(retained.recipe().adjustments()).isEqualTo(saved.recipe().adjustments());
            groups.replaceOptionGroupItems("E", "A", "SUGAR", "actor",
                    new ReplaceOptionGroupItemsReq(1L, List.of(), List.of(),
                            new SelectionRules(List.of(), List.of())), "delete-option");
            var recreated = new OptionItemInput("NO_SUGAR", "SPEC_VALUE", "V99", null,
                    n("0"), n("1"), n("0"), 0);
            groups.replaceOptionGroupItems("E", "A", "SUGAR", "actor",
                    new ReplaceOptionGroupItemsReq(2L, List.of(recreated), List.of(),
                            new SelectionRules(List.of(), List.of())), "recreate-option");
            assertThat(f.sql().queryForObject("SELECT spec_value_no FROM fnb_option_group_item "
                    + "WHERE option_item_no='NO_SUGAR'", String.class)).isEqualTo("V99");
            assertThat(service.replaceDefinition("E", "A", id, "actor", req, "adjust")).isEqualTo(saved);
            code(() -> service.replaceDefinition("E", "A", id, "actor", definition(3, input), "preserve"),
                    ErrorCode.CONFLICT);
            var previous = saved.recipe().adjustments().getFirst();
            code(() -> service.replaceDefinition("E", "A", id, "actor", adjusted(json, 3, input,
                    adjustment(previous.adjustmentNo(), "NO_SUGAR", base.lineNo(), "SET", "0", "KG")), "explicit"),
                    ErrorCode.CONFLICT);
            assertThat(service.getRecipe("E", "A", id)).isEqualTo(retained);
            var accepted = service.replaceDefinition("E", "A", id, "actor", adjusted(json, 3, input,
                    adjustment(null, "NO_SUGAR", base.lineNo(), "SET", "0", "KG")), "new-identity");
            assertThat(accepted.recipe().adjustments().getFirst().adjustmentNo())
                    .isNotEqualTo(previous.adjustmentNo());
            assertThat(accepted.recipe().adjustments().getFirst().optionIdentityHash())
                    .isNotEqualTo(previous.optionIdentityHash());
        });
    }

    private static void checkConstraint(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(DataAccessException.class, failure ->
                assertThat(failure.getMostSpecificCause()).isInstanceOfSatisfying(SQLException.class, sql ->
                        assertThat(sql.getErrorCode()).isIn(3819, 4025)));
    }

    static class State {
        final AtomicBoolean fail = new AtomicBoolean();
        final AtomicBoolean badSku = new AtomicBoolean();
        final AtomicBoolean badMaterial = new AtomicBoolean();
        final AtomicBoolean outage = new AtomicBoolean();
    }
    @Configuration(proxyBeanMethods=false)
    static class Recipes {
        @Bean State recipeState() { return new State(); }
        @Bean MapperFactoryBean<RecipeMapper> recipeHeads(@Qualifier("foodSqlSessionTemplate") SqlSessionTemplate t) {
            var b = new MapperFactoryBean<>(RecipeMapper.class); b.setSqlSessionTemplate(t); return b;
        }
        @Bean MapperFactoryBean<RecipeLineMapper> recipeLines(
                @Qualifier("foodSqlSessionTemplate") SqlSessionTemplate t) {
            var b = new MapperFactoryBean<>(RecipeLineMapper.class); b.setSqlSessionTemplate(t); return b;
        }
        @Bean MapperFactoryBean<RecipeAdjustmentMapper> recipeAdjustments(
                @Qualifier("foodSqlSessionTemplate") SqlSessionTemplate t) {
            var b = new MapperFactoryBean<>(RecipeAdjustmentMapper.class); b.setSqlSessionTemplate(t); return b;
        }
        @Bean MapperFactoryBean<OptionGroupItemMapper> recipeOptionItems(
                @Qualifier("foodSqlSessionTemplate") SqlSessionTemplate t) {
            var b = new MapperFactoryBean<>(OptionGroupItemMapper.class); b.setSqlSessionTemplate(t); return b;
        }
        @Bean MapperFactoryBean<OptionGroupMapper> recipeOptionGroups(
                @Qualifier("foodSqlSessionTemplate") SqlSessionTemplate t) {
            var b = new MapperFactoryBean<>(OptionGroupMapper.class); b.setSqlSessionTemplate(t); return b;
        }
        @Bean RecipeService recipeService(RecipeMapper heads, RecipeLineMapper lines, ActionLogMapper actions,
                IdempotencyService idem, @Qualifier("foodOutboxPublisher") OutboxPublisher outbox,
                ObjectMapper json, PlatformTransactionManager manager, State state, RecipeAdjustmentMapper adjustments,
                OptionGroupItemMapper items, OptionGroupMapper groups) {
            var catalog = mock(GoodsCatalogQueryPort.class);
            when(catalog.getSku(anyString(), anyString(), anyString())).thenAnswer(i -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                if (state.outage.get()) throw new IllegalStateException("authority unavailable");
                return new GoodsCatalogQueryPort.SkuIdentityFact(i.getArgument(2), "G",
                        state.badSku.get() ? "OTHER" : i.getArgument(0),
                        i.getArgument(1), 0);
            });
            MaterialCatalogQueryPort materials = (entity, ids) -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                if (state.outage.get()) throw new IllegalStateException("inventory unavailable");
                String owner = state.badMaterial.get() ? "OTHER" : entity;
                return ids.contains("MILK") ? List.of(new MaterialCatalogQueryPort.MaterialFact("MILK", owner,
                        "牛奶", "KG", 1, "OWN", "BASE_ONLY", "INTEGER_ONLY",
                        MaterialCatalogFacts.definitionHash("MILK", owner, "牛奶", "KG", 1, "OWN"))) : List.of();
            };
            OutboxPublisher checked = (event, options) -> {
                var receipt = outbox.publish(event, options);
                if (state.fail.get()) throw new IllegalStateException("after-recipe-outbox");
                return receipt;
            };
            return new RecipeServiceImpl(heads, lines, actions, idem, checked, json, manager, catalog, materials,
                    adjustments, items, groups);
        }
    }
}
